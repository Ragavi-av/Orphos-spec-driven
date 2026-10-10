package io.orphos.eip.dataingestion.vo;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Request/response shapes for the data-source connection admin API. */
public final class DataSourceDtos {

    private DataSourceDtos() {}

    /**
     * Source kinds.
     * <ul>
     *   <li>{@code GENERIC_JDBC} accepts any JDBC driver on the classpath
     *       (including ODBC-JDBC bridge drivers mounted into /app/drivers).</li>
     *   <li>{@code REST} is a plain single-endpoint REST source.</li>
     *   <li>{@code REST_OPENAPI} is an "enterprise system" REST source described
     *       by an uploaded OpenAPI/Swagger spec — its operations become
     *       selectable objects in the mapping UI.</li>
     *   <li>{@code ZOHO_ERP} is a first-class Zoho connector (OAuth2 + Zoho
     *       module endpoints) built on top of the same OpenAPI/config model.</li>
     * </ul>
     */
    public enum SourceKind { POSTGRESQL, SQLSERVER, GENERIC_JDBC, REST, REST_OPENAPI, ZOHO_ERP }

    /** {@code OAUTH2} adds refresh-token grant (e.g. Zoho); the acquired bearer is sent as {@code Authorization}. */
    public enum RestAuthType { NONE, BASIC, BEARER, API_KEY, OAUTH2 }

    /** True for source kinds that talk HTTP rather than JDBC. */
    public static boolean isHttpKind(SourceKind kind) {
        return kind == SourceKind.REST || kind == SourceKind.REST_OPENAPI || kind == SourceKind.ZOHO_ERP;
    }

    /** True for the enterprise-system kinds whose objects/config live in {@code configJson}. */
    public static boolean isErpKind(SourceKind kind) {
        return kind == SourceKind.REST_OPENAPI || kind == SourceKind.ZOHO_ERP;
    }

    /**
     * {@code secret} is the RAW credential (DB password / bearer token / API key),
     * accepted only on write and immediately exchanged for a vault reference —
     * it is never persisted or echoed back. Leave blank on update to keep the
     * previously stored credential.
     */
    public record SaveDataSourceRequest(
            @NotBlank String name,
            @NotBlank String kind,
            // JDBC kinds
            String jdbcUrl,
            String driverClass,
            String username,
            // REST kind
            String baseUrl,
            String authType,
            String authHeaderName,
            String testPath,
            // shared
            String secret,
            Integer maxPoolSize,
            boolean enabled,
            /**
             * Enterprise-system (REST_OPENAPI / ZOHO_ERP) configuration as JSON —
             * see {@code io.orphos.eip.dataingestion.vo.ErpConfig}. Carries the
             * parsed OpenAPI objects, pagination style, incremental field, and
             * non-secret OAuth params (dataCenter, clientId, scope). Never carries
             * a raw secret — the OAuth {@code clientSecret}/{@code refreshToken}
             * bundle travels in {@code secret} and is exchanged for a vault ref.
             */
            String configJson) {}

    /** {@code hasCredential} tells the admin UI whether a secret is already stored, without exposing it. */
    public record DataSourceView(
            String id,
            String name,
            String kind,
            String jdbcUrl,
            String driverClass,
            String username,
            String baseUrl,
            String authType,
            String authHeaderName,
            String testPath,
            boolean hasCredential,
            Integer maxPoolSize,
            boolean enabled,
            String configJson,     // ERP/OpenAPI config (no secrets); "" for JDBC/plain-REST sources
            String lastTestAt,
            String lastTestStatus,
            String lastTestError,
            String createdAt,
            String updatedAt) {}

    /** Ad-hoc (pre-save) connection test: same shape as save, nothing persisted. */
    public record TestConnectionRequest(
            @NotBlank String kind,
            String jdbcUrl,
            String driverClass,
            String username,
            String baseUrl,
            String authType,
            String authHeaderName,
            String testPath,
            String secret,
            String configJson) {}

    /** Outcome of a connection test. {@code detail} is e.g. the database product/version. */
    public record TestResult(boolean ok, String message, String detail, long latencyMs) {}

    public record ListResponse(List<DataSourceView> dataSources) {}
}
