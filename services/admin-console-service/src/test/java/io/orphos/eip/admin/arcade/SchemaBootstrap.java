package io.orphos.eip.admin.arcade;

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

/**
 * Test-scoped copy of platform-core-service's {@code SchemaBootstrap} — needed
 * so admin-console-service's isolation tests run against a real schema
 * (compound tenantId+name UNIQUE indexes etc.) without depending on another
 * Maven module's test sources. In production, platform-core-service is the
 * only service that runs this at boot (see docker-compose's
 * {@code depends_on: platform-core: condition: service_healthy} on every
 * other app service) — the shared {@code orphos} database it bootstraps is
 * already ready by the time admin-console-service handles real requests.
 *
 * <p>Creates admin-owned vertex types in ArcadeDB on first boot.
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
            // --- Event-communication framework ---
            "NotificationRule",    // event type -> audience (platform/tenant admin, all users, role)
            "PlatformEvent",       // received events + dispatch outcome (event log)
            // --- Dashboard layout (widget selection + order) ---
            "DashboardLayoutDefault", // tenant-wide default layout (one per tenant)
            "DashboardLayoutUser",    // per-user override (one per tenantId+userId)
            // --- Schema migrations (one row per applied migration, per DB) ---
            "SchemaMigration"      // version, name, appliedAt, status, errorMessage
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
            "CREATE INDEX IF NOT EXISTS ON SchemaMigration (appliedAt) NOTUNIQUE"
    );

    private final ArcadeDbTemplate arcade;

    @org.springframework.beans.factory.annotation.Value("${arcadedb.database}")
    private String defaultDatabase;

    public SchemaBootstrap(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    @Override
    public void run(ApplicationArguments args) {
        applyTo(defaultDatabase);
    }

    /**
     * Applies the full admin schema (doc types + indexes) to {@code databaseName}.
     * Idempotent via {@code IF NOT EXISTS}. Called by {@code TenantService}
     * after provisioning a fresh DEDICATED-mode database so the new database
     * has the same doc types as {@code orphos}.
     */
    public void applyTo(String databaseName) {
        for (String t : DOCUMENT_TYPES) {
            try {
                arcade.commandNoResultOn(databaseName,
                        "CREATE DOCUMENT TYPE " + t + " IF NOT EXISTS", Map.of());
            } catch (Exception e) {
                log.warn("Could not create document type {} in '{}': {}", t, databaseName, e.getMessage());
            }
        }
        for (String idx : INDEXES) {
            try {
                arcade.commandNoResultOn(databaseName, idx, Map.of());
            } catch (Exception e) {
                log.debug("Index DDL skipped on '{}' ({}): {}", databaseName, idx, e.getMessage());
            }
        }
        log.info("Schema bootstrap complete on '{}': {} types ensured.",
                databaseName, DOCUMENT_TYPES.size());
    }
}
