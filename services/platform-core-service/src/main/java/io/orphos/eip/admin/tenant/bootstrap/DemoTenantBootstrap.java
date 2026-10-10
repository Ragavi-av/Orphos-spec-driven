package io.orphos.eip.admin.tenant.bootstrap;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.arcade.SchemaBootstrap;
import io.orphos.eip.admin.tenant.fuseki.FusekiProvisioningClient;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.IsolationMode;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.model.TenantStatus;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Backfills the existing single-tenant {@code orphos} data with the
 * {@code demo} tenant identifier and ensures the demo {@link Tenant} row
 * exists. Idempotent — safe to re-run on every boot.
 *
 * <p>Ordering: must fire AFTER {@link SchemaBootstrap} so the {@code Tenant}
 * doc type and {@code tenantId} indexes already exist. The {@code @Order}
 * declaration on both runners makes the sequence explicit.</p>
 *
 * <p>This runner is the bridge between the pre-multi-tenant codebase and the
 * post-multi-tenant codebase. Once every service has been migrated to filter
 * by {@code tenantId} (Phase 2 of the rollout), this runner becomes a no-op
 * on warm databases — the UPDATE statements find zero rows.</p>
 *
 * <p>See ADR-001 §4 (demo tenant) and the
 * {@code .kiro/specs/multi-tenant-platform/} directory for context.</p>
 */
@Component
@Order(SchemaBootstrap.ORDER_DEMO_TENANT)
public class DemoTenantBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(DemoTenantBootstrap.class);

    /**
     * Tenant-scoped doc types that get a {@code tenantId} stamp during the
     * one-time backfill. The {@code Tenant} doc itself is intentionally absent
     * (it's the tenant registry, not tenant-scoped data).
     */
    private static final List<String> TENANT_SCOPED_TYPES = List.of(
            "AuthMethod", "IdentityProvider", "LocalUser", "UserAuthBinding",
            "AuthEvent", "AuthAuditLog",
            "BrandingProfile", "BrandingAsset", "Theme",
            "EmailConfig", "EmailTemplate", "EmailEvent",
            "NotificationPref", "EmailConfigBackup");

    private final ArcadeDbTemplate arcade;
    private final TenantRepository tenants;
    private final KeycloakProvisioningClient keycloak;
    private final FusekiProvisioningClient fuseki;

    @Value("${eip.tenant.demo.id}")
    private String demoIdStr;

    @Value("${eip.tenant.demo.domain:localhost}")
    private String demoDomain;

    @Value("${eip.tenant.demo.display-name:Demo Tenant}")
    private String demoDisplayName;

    @Value("${eip.tenant.demo.realm:eip}")
    private String demoRealm;

    @Value("${eip.tenant.demo.database:orphos}")
    private String demoDatabase;

    @Value("${eip.tenant.demo.bootstrap-enabled:true}")
    private boolean enabled;

    /**
     * Whether to load the demo <em>sample data</em> on top of the tenant. When
     * {@code true} (default, preserving existing behaviour) the runner stamps the
     * demo {@code tenantId} onto existing {@code orphos} rows and copies the
     * Fuseki default graph into the demo named graph. When {@code false} the
     * tenant is still created (row + Keycloak realm/admin) but starts CLEAN —
     * this is the setup wizard's "load demo data = off" path, turning the demo
     * tenant into a real, empty application tenant. Bound from the
     * {@code EIP_TENANT_SEED_SAMPLE_DATA} env var written by orphos-setup.
     */
    @Value("${eip.tenant.demo.seed-sample-data:true}")
    private boolean seedSampleData;

    /**
     * Whether to also patch the demo Keycloak realm's client scope so its
     * tokens carry {@code tenant_id={demoId}}. Defaults true; set false to
     * skip Keycloak entirely in environments where it's not reachable yet.
     */
    @Value("${eip.tenant.demo.patch-realm:true}")
    private boolean patchRealm;

    /**
     * First-tenant administrator credentials from the setup wizard. When set,
     * this email + password become the real admin login for the tenant realm
     * (with the {@code admin} role) instead of the bootstrap-grade admin/admin.
     * Blank preserves the admin/admin fallback. See {@code ConfigWriter}'s
     * {@code EIP_TENANT_ADMIN_EMAIL}/{@code EIP_TENANT_ADMIN_PASSWORD}.
     */
    @Value("${eip.tenant.demo.admin-email:}")
    private String adminEmail;

    @Value("${eip.tenant.demo.admin-password:}")
    private String adminPassword;

    /**
     * Whether to migrate existing Fuseki default-graph triples into the demo
     * tenant's named graph (urn:eip:tenant:{demoId}). Required for the
     * SPARQL rewriter in graph-proxy to find data when the demo tenant queries
     * via the SHARED-mode path. Defaults true; set false in environments
     * where Fuseki isn't deployed yet.
     */
    @Value("${eip.tenant.demo.migrate-fuseki:true}")
    private boolean migrateFuseki;

    public DemoTenantBootstrap(ArcadeDbTemplate arcade, TenantRepository tenants,
                               KeycloakProvisioningClient keycloak,
                               FusekiProvisioningClient fuseki) {
        this.arcade = arcade;
        this.tenants = tenants;
        this.keycloak = keycloak;
        this.fuseki = fuseki;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Demo tenant bootstrap disabled (eip.tenant.demo.bootstrap-enabled=false)");
            return;
        }

        UUID demoId;
        try {
            demoId = UUID.fromString(demoIdStr);
        } catch (IllegalArgumentException e) {
            log.error("Invalid eip.tenant.demo.id='{}' — must be a valid UUID. Skipping demo bootstrap.", demoIdStr);
            return;
        }

        // The tenant registry row and its Keycloak realm/admin are ALWAYS ensured
        // so there is a usable tenant to sign in to, clean or not.
        ensureDemoTenantRow(demoId);
        if (patchRealm) {
            patchDemoRealm(demoId);
        }
        // Sample data (stamping existing orphos rows + copying Fuseki triples
        // into the demo named graph) is loaded ON TOP only when requested. With
        // it off, the tenant starts empty — a real application tenant.
        if (seedSampleData) {
            backfillTenantId(demoId);
            if (migrateFuseki) {
                migrateFusekiToNamedGraph(demoId);
            }
        } else {
            log.info("Clean-tenant mode (eip.tenant.demo.seed-sample-data=false): tenant '{}' created "
                    + "without sample data — skipping tenantId backfill and Fuseki graph migration.",
                    demoDisplayName);
        }
    }

    /**
     * Copies all default-graph triples in the {@code orphos} Fuseki dataset
     * into the demo tenant's named graph ({@code urn:eip:tenant:{demoId}}).
     *
     * <p>This is the Fuseki counterpart of {@link #backfillTenantId} — it lets
     * SHARED-mode SPARQL queries (which graph-proxy rewrites to scope to
     * {@code FROM <urn:eip:tenant:{demoId}>}) actually find data without
     * forcing existing producers to migrate their writes.</p>
     *
     * <p>Idempotency: SPARQL's {@code ADD DEFAULT TO <named>} dedupes triples
     * in the destination (RDF graphs are sets, not bags), so re-running this
     * on every boot just no-ops once the migration has happened. The default
     * graph is intentionally NOT cleared — that would require all current
     * writers to switch to the named graph in lockstep, which is a separate
     * workstream.</p>
     *
     * <p>Best-effort: a missing or unreachable Fuseki logs a warning but does
     * not fail the bootstrap.</p>
     */
    private void migrateFusekiToNamedGraph(UUID demoId) {
        String targetGraph = "urn:eip:tenant:" + demoId;
        String sparql = "ADD DEFAULT TO <" + targetGraph + ">";
        try {
            fuseki.executeUpdate(demoDatabase, sparql);
            log.info("Fuseki migration: copied default-graph triples in '{}' into <{}> "
                    + "(idempotent — subsequent runs are no-ops)",
                    demoDatabase, targetGraph);
        } catch (Exception e) {
            log.warn("Could not migrate Fuseki default-graph data for demo tenant: {} — "
                    + "SHARED-mode SPARQL via graph-proxy will return empty until this "
                    + "succeeds.", e.getMessage());
        }
    }

    /**
     * Ensures the demo tenant's Keycloak realm exists and carries the
     * {@code tenant} client scope so its tokens include {@code tenant_id={demoId}}.
     *
     * <p>The realm is now <b>owned by this provisioner</b>: {@link
     * KeycloakProvisioningClient#ensureRealm} creates it from the realm template
     * when missing (the demo realm is no longer pre-seeded via Keycloak's
     * {@code --import-realm}), and idempotently patches the tenant scope, standard
     * client scopes, default admin, and default SSO when it already exists. The
     * realm name comes from {@code eip.tenant.demo.realm} (driven by the single
     * {@code KEYCLOAK_REALM} env var). Best-effort: Keycloak being unreachable
     * does NOT fail the bootstrap, since Arcade-only smoke tests should still work.</p>
     */
    private void patchDemoRealm(UUID demoId) {
        try {
            keycloak.ensureRealm(demoRealm, demoDisplayName, demoId.toString());
            log.info("Ensured Keycloak realm '{}' (created from template if missing) with tenant_id claim mapper (value={})",
                    demoRealm, demoId);
        } catch (Exception e) {
            log.warn("Could not ensure Keycloak realm '{}': {} — "
                    + "the demo tenant's login/tokens will not work until this is resolved.",
                    demoRealm, e.getMessage());
        }

        // Seed the first-tenant admin in the demo realm so the login works for
        // the pre-existing tenant. With wizard credentials configured this is
        // the operator's own email + password (admin role); blank falls back to
        // bootstrap-grade admin/admin. Existing admins are adopted, not
        // duplicated (idempotent).
        try {
            keycloak.ensureRealmAdminUser(demoRealm, demoDisplayName, adminEmail, adminPassword, "admin");
        } catch (Exception e) {
            log.warn("Could not seed tenant admin in demo realm '{}': {}",
                    demoRealm, e.getMessage());
        }
    }

    private void ensureDemoTenantRow(UUID demoId) {
        Optional<Tenant> existing = tenants.findByIdIncludingDeleted(demoId);
        if (existing.isPresent()) {
            // Reconcile the mutable display name so a changed instance name
            // (EIP_TENANT_DEMO_DISPLAY_NAME, e.g. from the setup wizard) lands on
            // the pre-existing demo row instead of being ignored. The domain and
            // UUID are stable identifiers and are intentionally left untouched.
            Tenant current = existing.get();
            if (demoDisplayName != null && !demoDisplayName.isBlank()
                    && !demoDisplayName.equals(current.displayName())) {
                try {
                    tenants.updateDisplayName(demoId, demoDisplayName);
                    log.info("Reconciled demo tenant {} display name '{}' -> '{}'",
                            demoId, current.displayName(), demoDisplayName);
                } catch (Exception e) {
                    log.warn("Could not reconcile demo tenant display name: {}", e.getMessage());
                }
            } else {
                log.debug("Demo tenant {} already present with current name — nothing to reconcile", demoId);
            }
            return;
        }
        Instant now = Instant.now();
        Tenant demo = new Tenant(
                demoId, demoDomain, demoDisplayName,
                TenantStatus.ACTIVE, IsolationMode.SHARED,
                demoDatabase, null, demoRealm,
                now, now);
        try {
            tenants.insert(demo);
            log.info("Inserted demo tenant id={} domain={} realm={} db={}",
                    demoId, demoDomain, demoRealm, demoDatabase);
        } catch (Exception e) {
            // Likely a unique-index conflict caused by a domain collision — log
            // and continue rather than aborting startup. Operators can resolve
            // the conflict by editing eip.tenant.demo.domain.
            log.warn("Could not insert demo tenant (already exists or domain conflict): {}", e.getMessage());
        }
    }

    private void backfillTenantId(UUID demoId) {
        int totalStamped = 0;
        for (String type : TENANT_SCOPED_TYPES) {
            try {
                // ArcadeDB SQL: missing properties read as NULL, so this
                // matches both "never had tenantId" and "tenantId explicitly null".
                List<Map<String, Object>> result = arcade.command(
                        "UPDATE " + type + " SET tenantId = :t WHERE tenantId IS NULL RETURN count",
                        Map.of("t", demoId.toString()));
                int n = readCount(result);
                if (n > 0) {
                    log.info("Backfilled tenantId on {} {} row(s)", n, type);
                    totalStamped += n;
                }
            } catch (Exception e) {
                // Doc types that don't exist yet (first boot on a brand-new DB)
                // get logged at debug — there's nothing to stamp.
                log.debug("Skipped tenantId backfill on {}: {}", type, e.getMessage());
            }
        }
        if (totalStamped > 0) {
            log.info("Demo tenant backfill complete: {} rows stamped across {} doc types",
                    totalStamped, TENANT_SCOPED_TYPES.size());
        } else {
            log.debug("Demo tenant backfill: nothing to stamp (warm database).");
        }
    }

    @SuppressWarnings("unchecked")
    private static int readCount(List<Map<String, Object>> result) {
        if (result == null || result.isEmpty()) return 0;
        Object count = result.get(0).get("count");
        if (count instanceof Number n) return n.intValue();
        return 0;
    }
}
