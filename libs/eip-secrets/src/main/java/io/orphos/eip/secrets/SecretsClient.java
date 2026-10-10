package io.orphos.eip.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin client over the Infisical (MIT-licensed) secrets HTTP API, exposing the
 * same method contract the EIP services historically used against OpenBao's
 * Vault KV v2 API. This lets the rest of the codebase treat secrets as a
 * {@code path -> {field: value}} store with no awareness of the backend.
 *
 * <h2>Model mapping (Vault KV v2 → Infisical)</h2>
 * <ul>
 *   <li>{@code path}  → Infisical <em>folder</em> ({@code secretPath = "/" + path})</li>
 *   <li>{@code field} → Infisical <em>secret key</em></li>
 *   <li>{@code mount} → not used; a fixed {@code projectId} + {@code environment}
 *       scope every call instead</li>
 * </ul>
 * Infisical does not auto-create folders on write, so {@link #writeField} and
 * {@link #writeFields} first ensure the folder chain exists (idempotent).
 *
 * <h2>Auth</h2>
 * Universal Auth (machine identity): {@code POST /api/v1/auth/universal-auth/login}
 * with {@code clientId}/{@code clientSecret} yields a short-lived access token,
 * which is cached and transparently refreshed on expiry or a 401.
 *
 * <p>Plain class (no Spring stereotype) so it can be instantiated both by the
 * EnvironmentPostProcessor at bootstrap time and as a Spring bean at runtime via
 * {@link SecretsAutoConfiguration}.</p>
 */
public class SecretsClient {

    private static final Logger log = LogManager.getLogger(SecretsClient.class);

    /** Refresh the access token this many seconds before its advertised expiry. */
    private static final long TOKEN_EXPIRY_SKEW_SECONDS = 30;

    private final String host;
    private final String projectId;
    private final String environment;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper mapper = new ObjectMapper();

    // Created lazily on first network use so an unconfigured client (and the
    // unit tests that construct one) never allocates networking resources.
    private volatile HttpClient http;

    // Cached Universal Auth access token + absolute expiry epoch-millis.
    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;

    public SecretsClient(String host, String projectId, String environment,
                         String clientId, String clientSecret) {
        String h = host == null ? "" : host.trim();
        this.host        = h.endsWith("/") ? h.substring(0, h.length() - 1) : h;
        this.projectId   = projectId   == null ? "" : projectId.trim();
        this.environment = (environment == null || environment.isBlank()) ? "prod" : environment.trim();
        this.clientId    = clientId     == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    /** Lazily builds the HTTP client on first use (double-checked). */
    private HttpClient http() {
        HttpClient h = http;
        if (h == null) {
            synchronized (this) {
                h = http;
                if (h == null) {
                    h = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                    http = h;
                }
            }
        }
        return h;
    }

    public boolean isConfigured() {
        return !host.isEmpty() && !projectId.isEmpty()
                && !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /**
     * Best-effort probe that the backend is configured <em>and</em> currently
     * reachable with valid machine-identity credentials. Triggers a (cached)
     * Universal-Auth login, so a {@code true} means a subsequent
     * {@link #readField} will actually hit the API rather than silently
     * returning empty because Infisical was not ready yet.
     *
     * <p>Used by the bootstrap backstop to distinguish "backend not up yet"
     * (retry) from "secret simply not set" (bind the default). A failed login
     * clears the cached token, so repeated calls transparently re-attempt.</p>
     */
    public boolean isReachable() {
        return isConfigured() && !token().isEmpty();
    }

    /**
     * Resolves a configuration value, preferring an explicit {@code value} and
     * falling back to reading {@code file} (delivered via a mounted volume).
     * Used for the machine-identity {@code project-id}, {@code client-id}, and
     * {@code client-secret}: the infisical-seed bootstrap generates these at
     * runtime and writes them to a shared volume, so no literal lives in
     * compose/.env (mirrors the old OpenBao token-file pattern).
     */
    public static String resolveValue(String value, String file) {
        if (value != null && !value.isBlank()) return value.trim();
        if (file != null && !file.isBlank()) {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(file.trim())).trim();
            } catch (Exception e) {
                log.warn("Infisical secrets file {} could not be read: {}", file, e.getMessage());
            }
        }
        return "";
    }

    /** @deprecated use {@link #resolveValue(String, String)} — kept for source symmetry. */
    @Deprecated
    public static String resolveClientSecret(String clientSecret, String clientSecretFile) {
        return resolveValue(clientSecret, clientSecretFile);
    }

    // ------------------------------------------------------------------ reads

    /** Read a single field from a secret folder. Returns empty if the path/field is missing. */
    public Optional<String> readField(String path, String field) {
        if (!isConfigured()) return Optional.empty();
        try {
            String url = host + "/api/v3/secrets/raw/" + enc(field)
                    + "?workspaceId=" + enc(projectId)
                    + "&environment=" + enc(environment)
                    + "&secretPath=" + enc(secretPath(path));
            HttpResponse<String> res = send(authRequest(url).GET().build(), true);
            if (res.statusCode() == 404) return Optional.empty();
            if (res.statusCode() / 100 != 2) {
                log.warn("Infisical read {}#{} failed: {} {}", path, field, res.statusCode(), truncate(res.body()));
                return Optional.empty();
            }
            JsonNode value = mapper.readTree(res.body()).path("secret").path("secretValue");
            if (value.isMissingNode() || value.isNull()) return Optional.empty();
            String text = value.asText("");
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (Exception e) {
            log.warn("Infisical read {}#{} threw: {}", path, field, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Read every field at a secret folder as a map. Returns an empty map if the
     * folder is missing, the backend is not configured, or the response is
     * malformed. Intended for small config-style documents (a handful of fields).
     */
    public Map<String, String> readAllFields(String path) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!isConfigured()) return out;
        try {
            String url = host + "/api/v3/secrets/raw"
                    + "?workspaceId=" + enc(projectId)
                    + "&environment=" + enc(environment)
                    + "&secretPath=" + enc(secretPath(path));
            HttpResponse<String> res = send(authRequest(url).GET().build(), true);
            if (res.statusCode() == 404) return out;
            if (res.statusCode() / 100 != 2) {
                log.warn("Infisical list {} failed: {} {}", path, res.statusCode(), truncate(res.body()));
                return out;
            }
            JsonNode secrets = mapper.readTree(res.body()).path("secrets");
            if (!secrets.isArray()) return out;
            for (JsonNode s : secrets) {
                String k = s.path("secretKey").asText("");
                JsonNode v = s.path("secretValue");
                if (k.isEmpty() || v.isMissingNode() || v.isNull()) continue;
                out.put(k, v.asText(""));
            }
            return out;
        } catch (Exception e) {
            log.warn("Infisical list {} threw: {}", path, e.getMessage());
            return out;
        }
    }

    // ----------------------------------------------------------------- writes

    /**
     * Replace the entire document at {@code path} with {@code fields} (KV v2
     * replace semantics — fields not present in the map are removed). Use this
     * when several related values must rotate together.
     */
    public void writeFields(String path, Map<String, String> fields) {
        requireConfigured();
        ensureFolder(path);
        Map<String, String> existing = readAllFields(path);
        if (fields == null) fields = Map.of();
        // Drop fields no longer present.
        for (String key : existing.keySet()) {
            if (!fields.containsKey(key)) {
                deleteSecret(path, key);
            }
        }
        // Upsert the rest.
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            upsertSecret(path, e.getKey(), e.getValue() == null ? "" : e.getValue(),
                    existing.containsKey(e.getKey()));
        }
    }

    /**
     * Write a single field, overwriting any existing data at the path (the prior
     * OpenBao {@code writeField} replaced the whole KV document; we preserve that
     * by clearing other fields first).
     */
    public void writeField(String path, String field, String value) {
        writeFields(path, Map.of(field, value == null ? "" : value));
    }

    /** Permanently destroy the folder (and all fields) at the given path. */
    public void delete(String path) {
        requireConfigured();
        String parent = parentPath(path);
        String name = lastSegment(path);
        if (name.isEmpty()) return;
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("workspaceId", projectId);
            body.put("environment", environment);
            body.put("path", parent);
            HttpRequest req = authRequest(host + "/api/v1/folders/" + enc(name))
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = send(req, true);
            if (res.statusCode() == 404) return;
            if (res.statusCode() / 100 != 2) {
                throw new RuntimeException("Infisical delete folder " + path + " failed: "
                        + res.statusCode() + " " + truncate(res.body()));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Infisical delete folder " + path + " threw: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------- internals

    /** Create each missing segment of {@code path} as a folder (idempotent). */
    private void ensureFolder(String path) {
        List<String> segments = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (!seg.isBlank()) segments.add(seg.trim());
        }
        String parent = "/";
        for (String seg : segments) {
            createFolderIgnoreExisting(seg, parent);
            parent = "/".equals(parent) ? "/" + seg : parent + "/" + seg;
        }
    }

    private void createFolderIgnoreExisting(String name, String parentPath) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("workspaceId", projectId);
            body.put("environment", environment);
            body.put("name", name);
            body.put("path", parentPath);
            HttpRequest req = authRequest(host + "/api/v1/folders")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = send(req, true);
            // 2xx = created; 4xx with "already exists" = fine; anything else logs.
            if (res.statusCode() / 100 != 2 && !alreadyExists(res)) {
                log.warn("Infisical create folder {} under {} returned {} {}",
                        name, parentPath, res.statusCode(), truncate(res.body()));
            }
        } catch (Exception e) {
            log.warn("Infisical create folder {} under {} threw: {}", name, parentPath, e.getMessage());
        }
    }

    private void upsertSecret(String path, String field, String value, boolean exists) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("workspaceId", projectId);
            body.put("environment", environment);
            body.put("secretPath", secretPath(path));
            body.put("secretValue", value);
            String url = host + "/api/v3/secrets/raw/" + enc(field);
            HttpRequest.Builder rb = authRequest(url).header("Content-Type", "application/json");
            String payload = mapper.writeValueAsString(body);
            HttpRequest req = (exists
                    ? rb.method("PATCH", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    : rb.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))).build();
            HttpResponse<String> res = send(req, true);
            // If we guessed wrong about existence, retry with the other verb.
            if (res.statusCode() / 100 != 2) {
                HttpRequest retry = (exists
                        ? authRequest(url).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        : authRequest(url).header("Content-Type", "application/json")
                            .method("PATCH", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    ).build();
                res = send(retry, true);
            }
            if (res.statusCode() / 100 != 2) {
                throw new RuntimeException("Infisical write " + path + "#" + field + " failed: "
                        + res.statusCode() + " " + truncate(res.body()));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Infisical write " + path + "#" + field + " threw: " + e.getMessage(), e);
        }
    }

    private void deleteSecret(String path, String field) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("workspaceId", projectId);
            body.put("environment", environment);
            body.put("secretPath", secretPath(path));
            HttpRequest req = authRequest(host + "/api/v3/secrets/raw/" + enc(field))
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = send(req, true);
            if (res.statusCode() != 404 && res.statusCode() / 100 != 2) {
                log.warn("Infisical delete {}#{} returned {} {}", path, field, res.statusCode(), truncate(res.body()));
            }
        } catch (Exception e) {
            log.warn("Infisical delete {}#{} threw: {}", path, field, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ auth

    private HttpRequest.Builder authRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token());
    }

    /**
     * Sends a request; on a 401 (expired/invalid token) re-logins once and
     * retries when {@code retryOnUnauthorized} is set.
     */
    private HttpResponse<String> send(HttpRequest req, boolean retryOnUnauthorized) throws Exception {
        HttpResponse<String> res = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 401 && retryOnUnauthorized) {
            synchronized (this) {
                accessToken = null;
                accessTokenExpiresAt = 0;
            }
            HttpRequest retried = HttpRequest.newBuilder(req, (n, v) -> true)
                    .setHeader("Authorization", "Bearer " + token())
                    .build();
            return http().send(retried, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        return res;
    }

    private String token() {
        long now = System.currentTimeMillis();
        String cached = accessToken;
        if (cached != null && now < accessTokenExpiresAt) {
            return cached;
        }
        synchronized (this) {
            if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt) {
                return accessToken;
            }
            login();
            return accessToken == null ? "" : accessToken;
        }
    }

    private void login() {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("clientId", clientId);
            body.put("clientSecret", clientSecret);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/api/v1/auth/universal-auth/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                log.warn("Infisical login failed: {} {}", res.statusCode(), truncate(res.body()));
                accessToken = null;
                accessTokenExpiresAt = 0;
                return;
            }
            JsonNode root = mapper.readTree(res.body());
            accessToken = root.path("accessToken").asText(null);
            long expiresIn = root.path("expiresIn").asLong(7200);
            accessTokenExpiresAt = System.currentTimeMillis()
                    + Math.max(0, (expiresIn - TOKEN_EXPIRY_SKEW_SECONDS)) * 1000L;
        } catch (Exception e) {
            log.warn("Infisical login threw: {}", e.getMessage());
            accessToken = null;
            accessTokenExpiresAt = 0;
        }
    }

    // --------------------------------------------------------------- helpers

    /** Infisical secret paths are absolute and folder-style: {@code /eip/smtp}. */
    private static String secretPath(String path) {
        String p = path == null ? "" : path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return "/" + p;
    }

    private static String parentPath(String path) {
        String sp = secretPath(path);            // e.g. /a/b/c
        int slash = sp.lastIndexOf('/');
        return slash <= 0 ? "/" : sp.substring(0, slash);
    }

    private static String lastSegment(String path) {
        String sp = secretPath(path);
        int slash = sp.lastIndexOf('/');
        return slash < 0 ? sp : sp.substring(slash + 1);
    }

    private static boolean alreadyExists(HttpResponse<String> res) {
        String b = res.body();
        return b != null && b.toLowerCase().contains("already exist");
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Infisical is not configured (host, project-id, client-id, or client-secret is blank)");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
