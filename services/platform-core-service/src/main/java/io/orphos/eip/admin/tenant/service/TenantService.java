package io.orphos.eip.admin.tenant.service;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.arcade.SchemaBootstrap;
import io.orphos.eip.admin.dbconnections.service.DbConnectionService;
import io.orphos.eip.admin.migration.MigrationRunner;
import io.orphos.eip.admin.tenant.arcade.ArcadeProvisioningClient;
import io.orphos.eip.admin.tenant.dto.TenantDtos;
import io.orphos.eip.admin.tenant.fuseki.FusekiProvisioningClient;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.IsolationMode;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.model.TenantStatus;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business rules for the tenant aggregate. Owns validation, ID generation,
 * default-value selection, and status-transition rules. Keycloak realm
 * provisioning and dedicated-ArcadeDB creation are intentionally NOT done
 * here — they'll land in a follow-up step (A.4 / A.5). The fields are
 * populated here so the schema is forward-compatible.
 */
@Service
public class TenantService {

    private static final Logger log = LogManager.getLogger(TenantService.class);

    private final TenantRepository tenants;
    private final TenantLookupServiceImpl lookupCache;
    private final KeycloakProvisioningClient keycloak;
    private final ArcadeProvisioningClient arcade;
    private final FusekiProvisioningClient fuseki;
    private final SchemaBootstrap schemaBootstrap;
    private final ArcadeDbTemplate arcadeClient;
    private final MigrationRunner migrationRunner;
    /** Nullable — used only to pre-validate a chosen DEDICATED ArcadeDB connection. */
    private final DbConnectionService dbConnections;

    /**
     * DB name for SHARED-mode tenants (the existing single ArcadeDB).
     * DEDICATED tenants get {@code eip_tenant_{uuid}} computed dynamically.
     */
    @Value("${eip.tenant.shared-database:orphos}")
    private String sharedDatabase;

    /**
     * Realm prefix used when computing the Keycloak realm name from a tenant
     * UUID. Override per-environment if a different naming scheme is required.
     */
    @Value("${eip.tenant.realm-prefix:tenant-}")
    private String realmPrefix;

    public TenantService(TenantRepository tenants,
                         TenantLookupServiceImpl lookupCache,
                         KeycloakProvisioningClient keycloak,
                         ArcadeProvisioningClient arcade,
                         FusekiProvisioningClient fuseki,
                         SchemaBootstrap schemaBootstrap,
                         ArcadeDbTemplate arcadeClient,
                         MigrationRunner migrationRunner,
                         DbConnectionService dbConnections) {
        this.tenants = tenants;
        this.lookupCache = lookupCache;
        this.keycloak = keycloak;
        this.arcade = arcade;
        this.fuseki = fuseki;
        this.schemaBootstrap = schemaBootstrap;
        this.arcadeClient = arcadeClient;
        this.migrationRunner = migrationRunner;
        this.dbConnections = dbConnections;
    }

    /** Create a new tenant. UUID is generated; status starts {@link TenantStatus#ACTIVE}. */
    public Tenant create(TenantDtos.CreateTenantRequest req) {
        String domain = req.domain().trim().toLowerCase();

        tenants.findByDomain(domain).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Domain '" + domain + "' is already registered.");
        });

        UUID id = UUID.randomUUID();
        // New tenants default to DEDICATED (their own ArcadeDB + Fuseki dataset);
        // callers can still explicitly request SHARED.
        IsolationMode mode = req.isolationMode() != null ? req.isolationMode() : IsolationMode.DEDICATED;
        String database = (mode == IsolationMode.DEDICATED)
                ? "eip_tenant_" + id.toString().replace("-", "")
                : sharedDatabase;
        // The chosen ArcadeDB connection (DEDICATED only). Blank / the bundled id
        // collapse to null == the platform ArcadeDB (today's behaviour).
        String connId = (mode == IsolationMode.DEDICATED)
                ? normalizeConnectionId(req.arcadeConnectionId())
                : null;
        // Pre-flight: fail fast with 400 (not a mid-provision 502) if the chosen
        // connection is unknown or not an ArcadeDB connection.
        if (connId != null && dbConnections != null && !dbConnections.isKnownArcadeConnection(connId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown or non-ArcadeDB connection: " + connId);
        }
        String realm = realmPrefix + id;
        Instant now = Instant.now();

        Tenant t = new Tenant(
                id, domain, req.displayName().trim(),
                TenantStatus.ACTIVE, mode, database, connId, realm,
                now, now);

        tenants.insert(t);
        lookupCache.evict(id, domain);

        // Provision external resources. Both calls are idempotent so a retry
        // after a partial failure is safe. If either fails, soft-delete the
        // Tenant row so the caller sees a clean error and a re-POST with the
        // same domain hits the unique-index path on next attempt.
        try {
            keycloak.ensureRealm(realm, t.displayName(), id.toString());
            if (mode == IsolationMode.DEDICATED) {
                provisionDedicatedDatabase(connId, database);
            }
        } catch (Exception e) {
            log.error("Provisioning failed for tenant {} (mode={}) — rolling back DB row",
                    id, mode, e);
            try {
                tenants.updateStatus(id, TenantStatus.DELETED);
                lookupCache.evict(id, domain);
            } catch (Exception rollbackErr) {
                log.error("Rollback of tenant {} also failed — manual cleanup required", id, rollbackErr);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to provision tenant resources: " + e.getMessage(), e);
        }

        log.info("Created tenant id={} domain={} mode={} db={} realm={}",
                id, domain, mode, database, realm);
        return t;
    }

    /**
     * Creates the dedicated ArcadeDB database AND the dedicated Fuseki dataset
     * for a DEDICATED-mode tenant, then applies the admin schema. Both stores
     * use the SAME name string (the tenant's {@code arcadeDatabase} field) so
     * graph-proxy can route by it without an extra lookup.
     *
     * <p>Idempotent on all three legs:
     * <ul>
     *   <li>{@code arcade.ensureDatabase} no-ops if the DB exists</li>
     *   <li>{@code fuseki.ensureDataset} no-ops if the dataset exists</li>
     *   <li>{@code schemaBootstrap.applyTo} uses {@code CREATE ... IF NOT EXISTS}
     *       for every type and index</li>
     * </ul>
     *
     * <p>If Fuseki provisioning fails after ArcadeDB succeeds, the caller's
     * rollback marks the Tenant DELETED — the orphan ArcadeDB is left for
     * the next manual cleanup (same trade-off as the Keycloak realm orphan).</p>
     */
    private void provisionDedicatedDatabase(String connectionId, String database) {
        arcade.ensureDatabase(connectionId, database);
        fuseki.ensureDataset(database);   // Fuseki stays single-server (out of scope)
        schemaBootstrap.applyTo(connectionId, database);
        // Record the baseline (and apply any newer migrations registered on
        // the service) in the tenant's SchemaMigration table, on the chosen
        // ArcadeDB server. Idempotent — a re-run does nothing.
        migrationRunner.runPending(connectionId, database);
    }

    public Tenant get(UUID id) {
        return tenants.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id));
    }

    public List<Tenant> list() {
        return tenants.listActive();
    }

    /**
     * Apply a partial update. Only fields present in {@code req} are mutated.
     * Status transitions are validated; arbitrary jumps (e.g. DELETED → ACTIVE)
     * are rejected — undeletion is a support-only operation.
     */
    public Tenant update(UUID id, TenantDtos.UpdateTenantRequest req) {
        Tenant current = get(id);

        if (req.domain() != null) {
            String newDomain = req.domain().trim().toLowerCase();
            if (!newDomain.equals(current.domain())) {
                tenants.findByDomain(newDomain).ifPresent(other -> {
                    if (!other.id().equals(id)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Domain '" + newDomain + "' is already registered.");
                    }
                });
                tenants.updateDomain(id, newDomain);
            }
        }

        if (req.displayName() != null && !req.displayName().isBlank()) {
            tenants.updateDisplayName(id, req.displayName().trim());
        }

        if (req.status() != null && req.status() != current.status()) {
            assertLegalTransition(current.status(), req.status());
            tenants.updateStatus(id, req.status());
        }

        lookupCache.evict(id, current.domain());
        if (req.domain() != null) {
            lookupCache.evict(id, req.domain().trim().toLowerCase());
        }
        return get(id);
    }

    /** Soft-delete. Marks status DELETED; row is retained for audit and undeletion. */
    public void softDelete(UUID id) {
        Tenant t = get(id);
        if (t.status() == TenantStatus.DELETED) return;
        tenants.updateStatus(id, TenantStatus.DELETED);
        lookupCache.evict(id, t.domain());
        log.info("Soft-deleted tenant id={} domain={}", id, t.domain());
    }

    /**
     * Hard-delete: irreversibly release every external resource owned by the
     * tenant, then drop the Tenant row itself.
     *
     * <p>Order matters: external resources first (most→least recoverable), the
     * Tenant row last. If a step fails, the row is left soft-deleted so a
     * retry can pick up where the previous attempt failed — every external
     * call is idempotent for that reason.</p>
     *
     * <p>SHARED-mode tenants don't have a dedicated database or dataset to
     * drop, but their data DOES live in the shared {@code orphos} +
     * {@code <urn:eip:tenant:{uuid}>} graph. When {@code cascadeSharedData}
     * is true, this method DELETEs every {@code WHERE tenantId = :id} row
     * across the tenant-scoped doc types AND drops the named graph in Fuseki.
     * Default false (the data is preserved as orphan rows so the tenant can
     * theoretically be recreated with the same UUID and recover).</p>
     *
     * <p>Demo tenant ({@code keycloakRealm == eip} + {@code arcadeDatabase ==
     * orphos}) is REJECTED — destroying it would brick the demo environment.</p>
     *
     * @param cascadeSharedData if true and the tenant is SHARED, also wipes
     *        its row-level data and named-graph triples. Ignored for DEDICATED.
     */
    public void hardDelete(UUID id, boolean cascadeSharedData) {
        Tenant t = tenants.findByIdIncludingDeleted(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id));

        // Guardrail: refuse to nuke the demo tenant.
        if ("eip".equals(t.keycloakRealm()) && "orphos".equals(t.arcadeDatabase())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Refusing to hard-delete the demo tenant — it owns shared infrastructure. " +
                    "Soft-delete only, or change the demo tenant's identifiers first.");
        }

        log.warn("Hard-deleting tenant id={} domain={} mode={} db={} realm={} cascadeShared={}",
                id, t.domain(), t.isolationMode(), t.arcadeDatabase(), t.keycloakRealm(),
                cascadeSharedData);

        // 1. Drop external resources. Each call is idempotent (no-op if absent).
        if (t.isolationMode() == IsolationMode.DEDICATED) {
            tryRelease("Fuseki dataset " + t.arcadeDatabase(),
                    () -> fuseki.dropDataset(t.arcadeDatabase()));
            tryRelease("ArcadeDB database " + t.arcadeDatabase(),
                    () -> arcade.dropDatabase(t.arcadeConnectionId(), t.arcadeDatabase()));
        } else if (cascadeSharedData) {
            tryRelease("Shared-data wipe for tenant " + id,
                    () -> wipeSharedTenantData(id));
            tryRelease("Fuseki named-graph wipe for tenant " + id,
                    () -> fuseki.executeUpdate(t.arcadeDatabase(),
                            "DROP SILENT GRAPH <urn:eip:tenant:" + id + ">"));
        }
        tryRelease("Keycloak realm " + t.keycloakRealm(),
                () -> keycloak.deleteRealm(t.keycloakRealm()));

        // 2. Finally drop the Tenant row + evict caches.
        tenants.hardDelete(id);
        lookupCache.evict(id, t.domain());
        log.warn("Hard-delete complete for tenant id={} domain={}", id, t.domain());
    }

    /**
     * Wipes shared-orphos rows for the given tenant. Used by hard-delete
     * with cascade. Each statement is parameter-bound; doc types that don't
     * exist on a fresh DB are skipped with a debug log.
     */
    private void wipeSharedTenantData(UUID tenantId) {
        for (String docType : SHARED_TENANT_SCOPED_TYPES) {
            try {
                arcadeClient.commandNoResult(
                        "DELETE FROM " + docType + " WHERE tenantId = :tenantId",
                        java.util.Map.of("tenantId", tenantId.toString()));
            } catch (Exception e) {
                log.debug("Skipped wipe on doc type {}: {}", docType, e.getMessage());
            }
        }
    }

    /**
     * Document types that may carry tenant-scoped data in the shared
     * {@code orphos}. Mirrors {@code DemoTenantBootstrap.TENANT_SCOPED_TYPES}.
     */
    private static final java.util.List<String> SHARED_TENANT_SCOPED_TYPES = java.util.List.of(
            "AuthMethod", "IdentityProvider", "LocalUser", "UserAuthBinding",
            "AuthEvent", "AuthAuditLog",
            "BrandingProfile", "BrandingAsset", "Theme",
            "EmailConfig", "EmailTemplate", "EmailEvent",
            "NotificationPref", "EmailConfigBackup");

    /** Runs work; logs and absorbs any exception so partial-failure rollback can complete. */
    private static void tryRelease(String resource, Runnable work) {
        try {
            work.run();
        } catch (Exception e) {
            log.warn("Could not release {}: {} — manual cleanup may be required",
                    resource, e.getMessage());
        }
    }

    /**
     * Normalises a requested ArcadeDB connection id: blank or the bundled id
     * ({@code arcadedb-bundled}) collapse to {@code null}, meaning "use the
     * platform ArcadeDB" — the default, credential-free path.
     */
    static String normalizeConnectionId(String connectionId) {
        if (connectionId == null) return null;
        String c = connectionId.trim();
        if (c.isEmpty() || c.equals("arcadedb-bundled")) return null;
        return c;
    }

    private static void assertLegalTransition(TenantStatus from, TenantStatus to) {
        // Legal: ACTIVE ⇄ SUSPENDED, ACTIVE → DELETED, SUSPENDED → DELETED.
        // Illegal: DELETED → anything (undeletion is a separate support op).
        if (from == TenantStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot transition from DELETED via the standard API.");
        }
    }
}
