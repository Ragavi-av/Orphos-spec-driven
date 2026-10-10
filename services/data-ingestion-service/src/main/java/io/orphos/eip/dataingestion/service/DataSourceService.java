package io.orphos.eip.dataingestion.service;

import io.orphos.eip.dataingestion.dao.ConnectionTester;
import io.orphos.eip.dataingestion.dao.DataSourcePools;
import io.orphos.eip.dataingestion.repository.DataSourceRepository;
import io.orphos.eip.dataingestion.vo.DataSourceDtos;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.DataSourceView;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.RestAuthType;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SaveDataSourceRequest;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SourceKind;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.TestResult;
import io.orphos.eip.dataingestion.vo.ErpConfig;
import io.orphos.eip.dataingestion.util.ZohoPreset;
import io.orphos.eip.dataingestion.component.ErpTokenProvider;
import io.orphos.eip.dataingestion.component.SecretRefService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * Save/validate/test orchestration for enterprise data sources.
 *
 * <p>Requirement 1.4: connection parameters are validated by actually opening
 * the connection BEFORE the definition is stored — an unreachable or
 * mis-credentialed source is rejected with 422 and never persisted.</p>
 */
@Service
public class DataSourceService {

    private static final Logger log = LogManager.getLogger(DataSourceService.class);

    private final DataSourceRepository repo;
    private final ConnectionTester tester;
    private final DataSourcePools pools;
    private final SecretRefService secrets;
    private final ErpTokenProvider tokenProvider;

    public DataSourceService(DataSourceRepository repo, ConnectionTester tester,
                             DataSourcePools pools, SecretRefService secrets,
                             ErpTokenProvider tokenProvider) {
        this.repo = repo;
        this.tester = tester;
        this.pools = pools;
        this.secrets = secrets;
        this.tokenProvider = tokenProvider;
    }

    /** Create a new data source. Validates connectivity first; stores the secret only on success. */
    public DataSourceView create(SaveDataSourceRequest req) {
        SourceKind kind = parseKind(req.kind());
        RestAuthType auth = parseAuth(req.authType());
        Normalized n = normalize(kind, req);
        requireShape(kind, auth, n.baseUrl(), req);
        if (repo.findByName(req.name()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A data source named '" + req.name() + "' already exists");
        }

        TestResult probe = tester.test(kind, req.jdbcUrl(), req.driverClass(), req.username(),
                n.baseUrl(), auth, req.authHeaderName(), req.testPath(), req.secret(), n.configJson());
        if (!probe.ok()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Connection validation failed: " + probe.message());
        }

        String credentialRef = null;
        if (req.secret() != null && !req.secret().isEmpty()) {
            credentialRef = secrets.store(kind.name().toLowerCase(), "credential", req.secret());
        }
        String id = repo.insert(toFields(req, kind, auth, n), credentialRef);
        repo.recordTestResult(id, true, "");
        log.info("Created data source {} ({}, kind={})", id, req.name(), kind);
        return toView(repo.findById(id));
    }

    /**
     * Update an existing data source. When {@code secret} is blank the stored
     * credential is kept (and used for the pre-store validation probe).
     */
    public DataSourceView update(String id, SaveDataSourceRequest req) {
        Map<String, Object> existing = require(id);
        SourceKind kind = parseKind(req.kind());
        RestAuthType auth = parseAuth(req.authType());
        Normalized n = normalize(kind, req);
        requireShape(kind, auth, n.baseUrl(), req);
        Map<String, Object> byName = repo.findByName(req.name());
        if (byName != null && !id.equals(String.valueOf(byName.get("id")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A data source named '" + req.name() + "' already exists");
        }

        String existingRef = str(existing, "credentialRef");
        String effectiveSecret = (req.secret() != null && !req.secret().isEmpty())
                ? req.secret()
                : secrets.resolve(existingRef).orElse(null);

        TestResult probe = tester.test(kind, req.jdbcUrl(), req.driverClass(), req.username(),
                n.baseUrl(), auth, req.authHeaderName(), req.testPath(), effectiveSecret, n.configJson());
        if (!probe.ok()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Connection validation failed: " + probe.message());
        }

        String credentialRef = null; // null = keep existing
        if (req.secret() != null && !req.secret().isEmpty()) {
            credentialRef = existingRef.isBlank()
                    ? secrets.store(kind.name().toLowerCase(), "credential", req.secret())
                    : secrets.overwrite(existingRef, req.secret());
        }
        repo.update(id, toFields(req, kind, auth, n), credentialRef);
        repo.recordTestResult(id, true, "");
        pools.evict(id);          // pick up new URL/credential/pool size on next use
        tokenProvider.evict(id);  // drop any cached OAuth token for this source
        return toView(repo.findById(id));
    }

    public void delete(String id) {
        Map<String, Object> existing = require(id);
        pools.evict(id);
        tokenProvider.evict(id);
        String ref = str(existing, "credentialRef");
        if (!ref.isBlank()) secrets.delete(ref);
        repo.delete(id);
        log.info("Deleted data source {}", id);
    }

    /** Re-test a stored data source and record the outcome on the row. */
    public TestResult testStored(String id) {
        Map<String, Object> row = require(id);
        SourceKind kind = parseKind(str(row, "kind"));
        RestAuthType auth = parseAuth(str(row, "authType"));
        String secret = secrets.resolve(str(row, "credentialRef")).orElse(null);
        TestResult r = tester.test(kind, str(row, "jdbcUrl"), str(row, "driverClass"),
                str(row, "username"), str(row, "baseUrl"), auth,
                str(row, "authHeaderName"), str(row, "testPath"), secret, str(row, "configJson"));
        repo.recordTestResult(id, r.ok(), r.ok() ? "" : r.message());
        return r;
    }

    public Map<String, Object> require(String id) {
        Map<String, Object> row = repo.findById(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown data source: " + id);
        }
        return row;
    }

    public static SourceKind parseKind(String kind) {
        try {
            return SourceKind.valueOf(kind == null ? "" : kind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown source kind '" + kind + "' — expected POSTGRESQL, SQLSERVER, "
                            + "GENERIC_JDBC, REST, REST_OPENAPI or ZOHO_ERP");
        }
    }

    public static RestAuthType parseAuth(String authType) {
        if (authType == null || authType.isBlank()) return RestAuthType.NONE;
        try {
            return RestAuthType.valueOf(authType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown auth type '" + authType + "' — expected NONE, BASIC, BEARER, API_KEY or OAUTH2");
        }
    }

    /**
     * Effective (normalized) connection parameters after applying vendor presets.
     * {@code baseUrl} may be derived (Zoho, from the data center); {@code configJson}
     * is the normalized objects/pagination config to persist ("" for JDBC only —
     * all HTTP kinds, including plain REST, carry it so their endpoints are mappable).
     */
    private record Normalized(String baseUrl, String configJson) {}

    /** Apply vendor presets and derive an effective base URL + config JSON. */
    private static Normalized normalize(SourceKind kind, SaveDataSourceRequest req) {
        String baseUrl = nz(req.baseUrl());
        if (kind == SourceKind.ZOHO_ERP) {
            ErpConfig cfg = ZohoPreset.applyDefaults(safeParse(req.configJson()));
            if (baseUrl.isBlank()) baseUrl = ZohoPreset.apiBase(cfg.dataCenter());
            return new Normalized(baseUrl, cfg.toJson());
        }
        if (DataSourceDtos.isHttpKind(kind)) {
            // REST + REST_OPENAPI: validate the client's objects/pagination config
            // parses, then store it so the endpoints are discoverable + mappable.
            ErpConfig cfg = safeParse(req.configJson());
            return new Normalized(baseUrl, cfg.toJson());
        }
        return new Normalized(baseUrl, ""); // JDBC kinds don't carry object config
    }

    private static ErpConfig safeParse(String configJson) {
        try {
            return ErpConfig.parse(configJson);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Kind-specific required fields (jdbcUrl for JDBC kinds, baseUrl for HTTP kinds). */
    private static void requireShape(SourceKind kind, RestAuthType auth, String effectiveBaseUrl,
                                     SaveDataSourceRequest req) {
        if (DataSourceDtos.isHttpKind(kind)) {
            // OAuth-only sources with no endpoint yet are allowed (token acquisition is the probe);
            // otherwise a base URL is required so extraction has somewhere to read from.
            boolean baseBlank = effectiveBaseUrl == null || effectiveBaseUrl.isBlank();
            if (baseBlank && auth != RestAuthType.OAUTH2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "baseUrl is required for " + kind + " sources");
            }
        } else {
            if (req.jdbcUrl() == null || req.jdbcUrl().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jdbcUrl is required for JDBC sources");
            }
            if (kind == SourceKind.GENERIC_JDBC
                    && (req.driverClass() == null || req.driverClass().isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "driverClass is required for GENERIC_JDBC sources");
            }
        }
    }

    private static Map<String, Object> toFields(SaveDataSourceRequest req, SourceKind kind,
                                                 RestAuthType auth, Normalized n) {
        Map<String, Object> f = new HashMap<>();
        f.put("name", req.name().trim());
        f.put("kind", kind.name());
        f.put("jdbcUrl", nz(req.jdbcUrl()));
        f.put("driverClass", nz(req.driverClass()));
        f.put("username", nz(req.username()));
        f.put("baseUrl", nz(n.baseUrl()));
        f.put("authType", auth.name());
        f.put("authHeaderName", nz(req.authHeaderName()));
        f.put("testPath", nz(req.testPath()));
        f.put("configJson", nz(n.configJson()));
        f.put("maxPoolSize", req.maxPoolSize() == null ? 0 : req.maxPoolSize());
        f.put("enabled", req.enabled());
        return f;
    }

    public static DataSourceView toView(Map<String, Object> row) {
        Object maxPool = row.get("maxPoolSize");
        return new DataSourceView(
                str(row, "id"),
                str(row, "name"),
                str(row, "kind"),
                str(row, "jdbcUrl"),
                str(row, "driverClass"),
                str(row, "username"),
                str(row, "baseUrl"),
                str(row, "authType"),
                str(row, "authHeaderName"),
                str(row, "testPath"),
                !str(row, "credentialRef").isBlank(),
                maxPool instanceof Number n && n.intValue() > 0 ? n.intValue() : null,
                Boolean.TRUE.equals(row.get("enabled")),
                str(row, "configJson"),
                str(row, "lastTestAt"),
                str(row, "lastTestStatus"),
                str(row, "lastTestError"),
                str(row, "createdAt"),
                str(row, "updatedAt"));
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
