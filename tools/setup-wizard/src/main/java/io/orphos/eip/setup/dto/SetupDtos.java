package io.orphos.eip.setup.dto;

import java.util.List;

/**
 * The wizard's data contract. One outer {@link SetupConfig} carries everything
 * the six steps collect; it is serialized verbatim to {@code orphos.config.json}
 * and projected into {@code .env} by the config writer.
 */
public final class SetupDtos {

    private SetupDtos() {}

    /** The complete set of setup decisions. */
    public record SetupConfig(
            // Step 1 — identity
            String instanceName,
            String instanceSlug,
            String primaryDomain,
            boolean demoMode,
            // Step 2 — databases (ArcadeDB and Keycloak's Postgres are independent)
            boolean bundledArcade,      // false → external ArcadeDB (see arcade)
            boolean bundledPostgres,    // false → external Keycloak Postgres (see postgres.kc*)
            ExternalDb arcade,          // external ArcadeDB — used when bundledArcade == false
            ExternalPostgres postgres,  // external Postgres (Keycloak + optional Infisical)
            // Step 3 — secrets
            Secrets secrets,
            // Step 4 — functionality
            List<String> functionalities,
            // Step 5 — AI
            AiConfig ai,
            // Step 6 — first tenant
            FirstTenant firstTenant) {}

    /** An externally-hosted database the operator points the platform at. */
    public record ExternalDb(String url, String user, String password) {}

    /**
     * External Postgres for the infra components (Keycloak's DB, and optionally
     * Infisical's). Only used when {@code bundledDb == false}. {@code kcJdbcUrl}
     * is a JDBC URL (jdbc:postgresql://host:5432/keycloak); {@code infisicalUri}
     * is a libpq URI (postgres://user:pass@host:5432/infisical). Either may be
     * blank to keep that component on the bundled Postgres.
     */
    public record ExternalPostgres(String kcJdbcUrl, String kcUser, String kcPassword, String infisicalUri,
                                   boolean shareKeycloak) {}

    /**
     * The one-time secrets. Any blank field is filled with a strong generated
     * value at install time so an operator can accept the defaults wholesale.
     */
    public record Secrets(
            String keycloakAdminPassword,
            String arcadedbPassword,
            String postgresPassword,        // Keycloak's bundled Postgres (eip-postgres)
            String infisicalDbPassword,     // Infisical's own bundled Postgres (eip-infisical-db)
            String fusekiAdminPassword,
            String internalServiceToken,
            String infisicalEncryptionKey,
            String infisicalAuthSecret,
            String infisicalAdminPassword) {}

    /** Local (Ollama) + external AI configuration. */
    public record AiConfig(
            String localModelMode,          // SINGLE | MULTIPLE
            String singleModel,             // chosen model when SINGLE
            List<String> extraModels,       // chosen model values when MULTIPLE
            List<AiProviderConn> providers) {}  // zero or more external provider connections

    /** One configured external AI provider connection. */
    public record AiProviderConn(String provider, String apiKey, String model) {}

    /** A selectable local model (catalogue entry). */
    public record LocalModel(String value, String label) {}

    /** A selectable external provider (catalogue entry; env-var names stay server-side). */
    public record AiProvider(String key, String label, String defaultModel) {}

    /** The first tenant, seeded from the instance identity. */
    public record FirstTenant(
            String displayName,
            String domain,
            String adminEmail,
            String adminPassword,
            boolean loadDemoData) {}

    // ── responses ───────────────────────────────────────────────────────────

    /** Request to bring the platform up. mode: "full" (start.sh) or "functionality" (startmodules.sh). */
    public record RunRequest(String mode, List<String> functionalities) {}

    /**
     * Whether a prior install exists and the secrets to seed the Secrets step
     * with. When {@code priorInstall} is true, {@code secrets} are the values
     * read from the existing {@code .env} (reuse them — they match the volumes);
     * otherwise they are freshly generated for a clean machine.
     */
    public record ExistingInstall(boolean priorInstall, String envPath, Secrets secrets) {}

    /** One selectable functionality (mirrors the startmodules.sh catalogue). */
    public record Functionality(
            String key,
            String description,
            String group,            // "app" | "mcp" | "agent" — for UI grouping
            List<String> services,
            boolean needsAi) {}

    /** Result of a live DB connectivity/authorization probe. */
    public record ValidationResult(boolean ok, String message, long elapsedMs) {}

    /** Request to create an ArcadeDB database (empty, or with the default KG structure). */
    public record CreateDbRequest(String url, String user, String password, String database, boolean withSchema) {}

    /** Outcome of a database-create action. */
    public record CreateDbResult(boolean ok, boolean created, boolean schemaApplied, String message) {}

    /** The resolved install plan derived from a config's functionality selection. */
    public record InstallPlan(
            List<String> functionalities,
            List<String> services,   // core UI + resolved extras, de-duplicated
            boolean needsAi,
            int containerCount) {}

    /** Outcome of writing the config artifacts. */
    public record InstallResult(
            boolean ok,
            String message,
            String envPath,
            String configPath,
            String envBackupPath,
            InstallPlan plan) {}
}
