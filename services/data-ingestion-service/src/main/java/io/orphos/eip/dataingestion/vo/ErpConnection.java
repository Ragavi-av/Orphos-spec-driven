package io.orphos.eip.dataingestion.vo;

import io.orphos.eip.dataingestion.vo.DataSourceDtos.RestAuthType;

import java.util.Map;

/**
 * Everything the HTTP read path needs for one enterprise-system source: its
 * identity (for token caching), base URL, auth details, the <b>resolved</b>
 * credential (already fetched from the vault by the caller), and the parsed
 * {@link ErpConfig}. Deliberately immutable and secret-carrying only for the
 * duration of a request/job — never persisted.
 */
public record ErpConnection(
        String sourceId,
        String baseUrl,
        RestAuthType authType,
        String authHeaderName,
        String username,
        String secret,        // resolved: OAuth {clientSecret,refreshToken} JSON, or bearer/api-key/basic password
        ErpConfig config) {

    /** Build from a stored {@code IngestDataSource} row plus its resolved secret. */
    public static ErpConnection from(Map<String, Object> row, String resolvedSecret) {
        RestAuthType auth;
        try {
            auth = RestAuthType.valueOf(str(row, "authType").isBlank() ? "NONE" : str(row, "authType").toUpperCase());
        } catch (IllegalArgumentException e) {
            auth = RestAuthType.NONE;
        }
        return new ErpConnection(
                str(row, "id"),
                str(row, "baseUrl"),
                auth,
                str(row, "authHeaderName"),
                str(row, "username"),
                resolvedSecret,
                ErpConfig.parse(str(row, "configJson")));
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }
}
