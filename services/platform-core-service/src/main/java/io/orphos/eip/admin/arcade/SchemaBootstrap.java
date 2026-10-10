package io.orphos.eip.admin.arcade;

import io.orphos.eip.core.arcadedb.ArcadeConnectionResolver;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates admin-owned vertex types in ArcadeDB on first boot.
 * Idempotent (CREATE ... IF NOT EXISTS), safe to re-run.
 *
 * <p>We deliberately keep these as document types (not vertex types) where there are
 * no graph relationships - they are operational config, not knowledge-graph nodes.</p>
 */
@Component
@Order(SchemaBootstrap.ORDER_SCHEMA)
public class SchemaBootstrap implements ApplicationRunner {

    /** Order constants shared with downstream runners (e.g. DemoTenantBootstrap). */
    public static final int ORDER_SCHEMA = Ordered.HIGHEST_PRECEDENCE + 100;
    public static final int ORDER_DEMO_TENANT = ORDER_SCHEMA + 100;

    private static final Logger log = LogManager.getLogger(SchemaBootstrap.class);

    private static final List<String> DOCUMENT_TYPES = List.of(
            // --- Multi-tenancy ---
            "Tenant",              // tenant registry (uuid, domain, status, isolation, realm, db)
            // --- Auth ---
            "AuthMethod",          // global config: which auth methods are enabled
            "IdentityProvider",    // per-IdP config (OIDC/SAML/LDAP)
            "LocalUser",           // self-managed user records (hashed credentials)
            "UserAuthBinding",     // links a user identity to its current auth method
            "AuthEvent",           // login attempts (analytics + security monitoring)
            "AuthAuditLog",        // admin-level audit (config changes)
            "RoleResourceAccess",  // RBAC access map: (role, resourceKey) -> allow/deny grant
            // --- Branding ---
            "BrandingProfile",     // active + draft branding profile
            "BrandingAsset",       // binary blobs (logos, backgrounds)
            "Theme",               // named themes (light/dark/custom)
            "UiLabel",             // per-tenant UI text overrides (lang + labelKey -> value)
            "UiLabelHistory",      // audit trail of UiLabel changes (old/new value, actor, source)
            // --- Email ---
            "EmailConfig",         // SMTP settings (secret reference, not raw)
            "EmailTemplate",       // mustache templates
            "EmailEvent",          // delivery + open/bounce tracking
            "NotificationPref",    // per-user notification preferences
            "EmailConfigBackup",   // snapshot for restore
            // --- Event-communication framework (admin-console-service) ---
            "NotificationRule",    // event type -> audience (platform/tenant admin, all users, role)
            "PlatformEvent",       // received events + dispatch outcome (event log)
            // --- Dashboard layout (widget selection + order) ---
            "DashboardLayoutDefault", // tenant-wide default layout (one per tenant)
            "DashboardLayoutUser",    // per-user override (one per tenantId+userId)
            // --- Schema migrations (one row per applied migration, per DB) ---
            "SchemaMigration",     // version, name, appliedAt, status, errorMessage
            // --- External DB connections registry (platform-scoped audit mirror;
            //     the connections.json file is the source of truth) ---
            "DbConnection",           // one per named connection (audit + last-test outcome)
            "DbConnectionAssignment", // one per service (serviceId -> connection + db/schema)
            // --- OKG (ontology knowledge graph) ingestion — okg-ingest-service ---
            // OkgConnection stays a DOCUMENT: it is per-(tenant,source) config
            // (Jira base URL/email + vault-ref API token), not a graph node. The
            // ingested entities (OkgProject/OkgPerson/OkgIssue) and their
            // issue-to-issue relationship (OkgIssueLink) are graph types — see
            // GRAPH_VERTEX_TYPES / GRAPH_EDGE_TYPES below.
            "OkgConnection"        // one per (tenant, source) — Jira base URL/email + vault-ref API token
    );

    /**
     * OKG ingestion entities modelled as GRAPH types rather than documents.
     * Their whole purpose is traversal — issue → linked issues, dependency
     * chains, "what blocks X" — so the entities are ArcadeDB vertices and the
     * directed relationship between two issues is a real edge (see
     * {@code OkgGraphRepository}). Earlier builds created these as DOCUMENT
     * types; {@link #ensureGraphType} converts an existing EMPTY document type
     * to the graph kind on boot so upgraded databases pick up the change.
     */
    private static final List<String> GRAPH_VERTEX_TYPES = List.of(
            "OkgProject",   // ingested project (externalId = e.g. Jira project key)
            "OkgPerson",    // ingested person (externalId only — no email/PII persisted)
            "OkgIssue"      // ingested issue/ticket
    );
    private static final List<String> GRAPH_EDGE_TYPES = List.of(
            "OkgIssueLink"  // directed issue→issue relationship (blocks/blockedBy/relatesTo/duplicates)
    );
    private static final List<String> GRAPH_INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS ON OkgProject (tenantId, externalId) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON OkgProject (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON OkgPerson (tenantId, externalId) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON OkgPerson (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON OkgIssue (tenantId, externalId) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON OkgIssue (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON OkgIssue (tenantId, projectExternalId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON OkgIssueLink (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON OkgIssueLink (tenantId, fromExternalId, toExternalId, linkType) UNIQUE"
    );

    private static final List<String> INDEXES = List.of(
            // Tenant lookup keys — domain hit per request, id hit per JWT validation
            "CREATE INDEX IF NOT EXISTS ON Tenant (id) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Tenant (domain) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Tenant (status) NOTUNIQUE",
            // Per-tenant unique business keys. The compound (tenantId, X) UNIQUE
            // shape lets two tenants both have, e.g., a BrandingProfile named
            // "default" without colliding — a single-column UNIQUE here would
            // make the second tenant's INSERT fail. The legacy single-column
            // indexes from v1 (pre-multi-tenancy) are dropped by migration V2.
            "CREATE INDEX IF NOT EXISTS ON LocalUser (tenantId, email) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON IdentityProvider (tenantId, name) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON BrandingProfile (tenantId, name) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Theme (tenantId, name) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON EmailTemplate (tenantId, code) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON EmailConfig (tenantId, name) UNIQUE",
            // hot lookup columns
            "CREATE INDEX IF NOT EXISTS ON AuthEvent (createdAt) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON AuthEvent (userKey) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON EmailEvent (createdAt) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON BrandingAsset (profileId) NOTUNIQUE",
            // tenantId on every tenant-scoped doc type. Every query in this
            // service should include WHERE tenantId = ?; these indexes make
            // sure those filters are O(log n), not O(n).
            "CREATE INDEX IF NOT EXISTS ON AuthMethod (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON IdentityProvider (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON LocalUser (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON UserAuthBinding (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON AuthEvent (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON AuthAuditLog (tenantId) NOTUNIQUE",
            // RBAC access map — one row per (tenant, role, resourceKey); read as a
            // set per tenant and toggled per (role, resourceKey).
            "CREATE INDEX IF NOT EXISTS ON RoleResourceAccess (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON RoleResourceAccess (tenantId, role, resourceKey) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON BrandingProfile (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON BrandingAsset (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Theme (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON UiLabel (tenantId, lang, labelKey) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON UiLabel (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON UiLabelHistory (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON UiLabelHistory (changedAt) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON EmailConfig (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON EmailTemplate (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON EmailEvent (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON NotificationPref (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON EmailConfigBackup (tenantId) NOTUNIQUE",
            // Event-communication framework: rules matched by (tenantId, eventType)
            // per dispatch; the cross-tenant fan-out for platform-scoped events
            // filters by eventType alone.
            "CREATE INDEX IF NOT EXISTS ON NotificationRule (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON NotificationRule (eventType) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON PlatformEvent (tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON PlatformEvent (createdAt) NOTUNIQUE",
            // Dashboard layout — one default per tenant, one override per (tenant, user).
            "CREATE INDEX IF NOT EXISTS ON DashboardLayoutDefault (tenantId) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON DashboardLayoutUser (tenantId, userId) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON DashboardLayoutUser (tenantId) NOTUNIQUE",
            // Migration registry: lookup by version inside a given DB. Not
            // tenant-scoped — SchemaMigration rows live in the DB they apply to
            // (per-DB for DEDICATED, shared orphos for SHARED). Version is
            // unique within a DB but the index is NOTUNIQUE because ArcadeDB
            // unique indexes span the whole class across all DBs of the same
            // server, and we deliberately want the same version recorded once
            // per DB.
            "CREATE INDEX IF NOT EXISTS ON SchemaMigration (version) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON SchemaMigration (appliedAt) NOTUNIQUE",
            // External DB connections registry — platform-global config, one row
            // per connection id / per service id (not tenant-scoped).
            "CREATE INDEX IF NOT EXISTS ON DbConnection (id) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON DbConnectionAssignment (serviceId) UNIQUE"
    );

    private final ArcadeDbTemplate arcade;

    @org.springframework.beans.factory.annotation.Value("${arcadedb.database}")
    private String defaultDatabase;

    private final ArcadeConnectionResolver resolver;

    /**
     * Optional — used to CREATE the platform database on the platform ArcadeDB if
     * it does not exist yet. The bundled ArcadeDB starts empty and nothing else
     * reliably creates {@code orphos} (the schema-init script is skipped when the
     * container is already running), so platform-core self-creates it here instead
     * of failing every startup query with "Database not available". Field-injected
     * so the constructor (and test subclasses) stay unchanged.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.orphos.eip.admin.tenant.arcade.ArcadeProvisioningClient arcadeProvisioning;

    /**
     * The environment carries the schema mode contributed by the connections
     * binder ({@code eip.connections.schema-mode}). When this service is assigned
     * an {@code EXISTING}-mode connection, the operator manages the database's
     * schema and the boot-time bootstrap below must not create it or run DDL.
     * Field-injected so the constructor (and test subclasses) stay unchanged.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    public SchemaBootstrap(ArcadeDbTemplate arcade, ArcadeConnectionResolver resolver) {
        this.arcade = arcade;
        this.resolver = resolver;
    }

    @Override
    public void run(ApplicationArguments args) {
        // EXISTING schema mode: the assigned connection points at a pre-provisioned,
        // operator-managed database. Do not create it and do not apply any DDL —
        // that is what protects a populated external database from platform writes.
        // (Only the boot path is gated; the public applyTo(...) overloads used by
        // tenant provisioning to build fresh DEDICATED databases are unaffected.)
        if (io.orphos.eip.core.dbregistry.SchemaModeGuard.isExisting(environment)) {
            log.info("Schema bootstrap skipped for database '{}': assigned connection uses schemaMode=EXISTING "
                    + "(operator-managed schema). The platform will neither create the database nor apply DDL.",
                    defaultDatabase);
            return;
        }
        // Ensure the platform database exists before applying the schema — the
        // types/indexes below are created INSIDE it. connectionId null → the
        // platform ArcadeDB (arcadedb.* coords). Idempotent (no-op if present).
        if (arcadeProvisioning != null) {
            try {
                arcadeProvisioning.ensureDatabase(null, defaultDatabase);
            } catch (Exception e) {
                log.warn("Could not ensure platform database '{}' exists: {} — schema bootstrap may fail",
                        defaultDatabase, e.getMessage());
            }
        }
        applyTo(defaultDatabase);
    }

    private ArcadeDbTemplate templateFor(String connectionId) {
        if (connectionId == null || connectionId.isBlank() || resolver == null) {
            return arcade;
        }
        return resolver.templateFor(connectionId);
    }

    /**
     * Applies the full admin schema (doc types + indexes) to {@code databaseName}.
     * Idempotent via {@code IF NOT EXISTS}. Called by {@code TenantService}
     * after provisioning a fresh DEDICATED-mode database so the new database
     * has the same doc types as {@code orphos}.
     */
    public void applyTo(String databaseName) {
        applyTo(arcade, databaseName);
    }

    /**
     * Applies the schema to {@code databaseName} on the ArcadeDB server of the
     * given connection ({@code connectionId} null → the platform ArcadeDB). Used
     * by {@code TenantService} to create the schema in a DEDICATED tenant's
     * chosen ArcadeDB.
     */
    public void applyTo(String connectionId, String databaseName) {
        applyTo(templateFor(connectionId), databaseName);
    }

    /**
     * Applies the schema DDL using an explicit template — the routing-agnostic
     * core. Callers that already hold a routed template (e.g. a
     * {@code TenantMigration} handed the resolved template) use this so the DDL
     * lands on the same server the migration is recorded against.
     */
    public void applyTo(ArcadeDbTemplate template, String databaseName) {
        for (String t : DOCUMENT_TYPES) {
            try {
                template.commandNoResultOn(databaseName,
                        "CREATE DOCUMENT TYPE " + t + " IF NOT EXISTS", Map.of());
            } catch (Exception e) {
                log.warn("Could not create document type {} in '{}': {}", t, databaseName, e.getMessage());
            }
        }
        declareIndexedProperties(template, databaseName, INDEXES);
        for (String idx : INDEXES) {
            try {
                template.commandNoResultOn(databaseName, idx, Map.of());
            } catch (Exception e) {
                log.warn("Index DDL failed on '{}' ({}): {}", databaseName, idx, e.getMessage());
            }
        }
        // OKG ingestion graph types (vertices + edge). Created after the document
        // types/indexes above; ensureGraphType() also migrates a leftover empty
        // DOCUMENT type of the same name to the correct graph kind.
        for (String v : GRAPH_VERTEX_TYPES) ensureGraphType(template, databaseName, v, "vertex");
        for (String e : GRAPH_EDGE_TYPES)   ensureGraphType(template, databaseName, e, "edge");
        declareIndexedProperties(template, databaseName, GRAPH_INDEXES);
        for (String idx : GRAPH_INDEXES) {
            try {
                template.commandNoResultOn(databaseName, idx, Map.of());
            } catch (Exception e) {
                log.warn("Graph index DDL failed on '{}' ({}): {}", databaseName, idx, e.getMessage());
            }
        }
        log.info("Schema bootstrap complete on '{}': {} document types + {} vertex + {} edge ensured.",
                databaseName, DOCUMENT_TYPES.size(), GRAPH_VERTEX_TYPES.size(), GRAPH_EDGE_TYPES.size());
    }

    /**
     * Declares every property the given index DDL refers to, so the indexes can actually be
     * built.
     *
     * <p>ArcadeDB refuses to index an undeclared property — {@code "Cannot create the index on
     * type 'Tenant.id' because the property does not exist"} — and {@code CREATE DOCUMENT TYPE}
     * declares none: the types are schema-less, so rows carry fields the schema never mentions.
     * Without this step EVERY index statement below fails, which is how the whole set came to be
     * missing on databases created by earlier builds.</p>
     *
     * <p>The property list is derived from the index DDL rather than hand-maintained alongside
     * it, so an index can never be added without its properties.</p>
     */
    private void declareIndexedProperties(ArcadeDbTemplate template, String databaseName, List<String> indexes) {
        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        for (String idx : indexes) {
            Matcher m = INDEX_TARGET.matcher(idx);
            if (!m.find()) {
                log.warn("Could not parse index DDL to declare its properties: {}", idx);
                continue;
            }
            for (String prop : m.group(2).split(",")) {
                if (!prop.isBlank()) {
                    declared.add(m.group(1) + "." + prop.trim());
                }
            }
        }
        for (String qualified : declared) {
            try {
                template.commandNoResultOn(databaseName,
                        "CREATE PROPERTY " + qualified + " IF NOT EXISTS "
                                + PROPERTY_TYPES.getOrDefault(qualified, "STRING"),
                        Map.of());
            } catch (Exception e) {
                log.warn("Could not declare property {} in '{}': {}", qualified, databaseName, e.getMessage());
            }
        }
    }

    /** Pulls the type and its property list out of {@code ... ON <Type> (<p1>, <p2>) UNIQUE}. */
    private static final Pattern INDEX_TARGET =
            Pattern.compile("\\bON\\s+(\\w+)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Datatypes for indexed properties that are NOT strings. Everything platform-core persists is
     * text — ids, tenant ids and timestamps all go in as ISO-8601 strings — except the migration
     * version, which {@code MigrationRunner} writes from {@code TenantMigration.version()}, an int.
     */
    private static final Map<String, String> PROPERTY_TYPES = Map.of("SchemaMigration.version", "INTEGER");

    /**
     * Ensures {@code typeName} exists in {@code databaseName} as the requested
     * graph {@code kind} ("vertex" or "edge"). If a type of that name already
     * exists as the WRONG kind — e.g. a leftover DOCUMENT from before these
     * were modelled as graph types — it is dropped and recreated, but ONLY when
     * it holds zero records, so a populated type is never silently destroyed
     * (that case is logged and left for a real data migration).
     */
    private void ensureGraphType(ArcadeDbTemplate template, String databaseName, String typeName, String kind) {
        try {
            List<Map<String, Object>> rows = template.queryOn(databaseName,
                    "SELECT type, records FROM schema:types WHERE name = :n", Map.of("n", typeName));
            if (!rows.isEmpty()) {
                String currentKind = String.valueOf(rows.get(0).get("type"));
                if (kind.equalsIgnoreCase(currentKind)) return; // already the right kind
                long records = toLong(rows.get(0).get("records"));
                if (records > 0) {
                    log.warn("Type {} in '{}' is a {} holding {} records but should be a {} — "
                            + "leaving as-is; a data migration is required to convert it.",
                            typeName, databaseName, currentKind, records, kind);
                    return;
                }
                template.commandNoResultOn(databaseName, "DROP TYPE " + typeName, Map.of());
                log.info("Dropped empty {} type {} in '{}' to recreate it as a {}.",
                        currentKind, typeName, databaseName, kind);
            }
            template.commandNoResultOn(databaseName,
                    "CREATE " + kind.toUpperCase() + " TYPE " + typeName + " IF NOT EXISTS", Map.of());
        } catch (Exception ex) {
            log.warn("Could not ensure {} type {} in '{}': {}", kind, typeName, databaseName, ex.getMessage());
        }
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }
}
