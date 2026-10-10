package io.orphos.eip.docingest.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SharePoint / Microsoft Teams folder connection over Microsoft Graph, using <b>app-only</b>
 * (client-credentials) auth — no interactive OAuth redirect. A tenant admin registers an Azure AD
 * app with the {@code Sites.ReadWrite.All} (application) permission and grants admin consent; this
 * connector exchanges the app's client id + secret for a Graph token and drives the site's document
 * library (Teams files live in the team's SharePoint site, so the same connector serves both).
 *
 * <p>Implemented with the JDK HTTP client + raw Graph REST (no Graph SDK / MSAL dependency), so it
 * builds in the offline repo. Config: {@code tenantId} (Azure AD directory id), {@code clientId},
 * {@code siteId} (Graph site id), {@code driveId} (optional — defaults to the site's default
 * library), {@code basePath} (optional folder). Credential: {@code {"clientSecret":".."}}.</p>
 */
@Component
public class SharePointSourceConnector implements SourceConnector {

    private static final Logger log = LogManager.getLogger(SharePointSourceConnector.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GRAPH = "https://graph.microsoft.com/v1.0";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public SourceKind kind() { return SourceKind.SHAREPOINT; }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.INGEST, Capability.BROWSE, Capability.READ, Capability.WRITE);
    }

    @Override
    public ProbeResult test(Descriptor desc) {
        for (String k : new String[]{"tenantId", "clientId", "siteId"}) {
            if (str(desc.config(), k).isBlank()) return ProbeResult.fail(k + " is required");
        }
        try {
            String token = token(desc);
            String driveId = driveId(desc, token);
            JsonNode children = graphJson("GET", childrenUrl(driveId, basePath(desc)), token, null);
            JsonNode arr = children.path("value");
            return ProbeResult.ok(arr.isArray() ? arr.size() : 0);
        } catch (Exception e) {
            return ProbeResult.fail(rootMessage(e));
        }
    }

    @Override
    public List<FolderEntry> browse(Descriptor desc, String path) {
        try {
            String token = token(desc);
            String driveId = driveId(desc, token);
            JsonNode res = graphJson("GET", childrenUrl(driveId, joinPath(basePath(desc), path)), token, null);
            List<FolderEntry> out = new ArrayList<>();
            for (JsonNode item : res.path("value")) {
                if (!item.has("folder")) continue;
                String name = item.path("name").asText("");
                String rel = (path == null || path.isBlank()) ? name : trim(path) + "/" + name;
                out.add(new FolderEntry(name, rel, item.path("folder").path("childCount").asInt(0) > 0));
            }
            out.sort(Comparator.comparing(FolderEntry::name, String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (Exception e) {
            log.debug("SharePoint browse '{}' failed: {}", path, e.toString());
            return List.of();
        }
    }

    @Override
    public void createFolder(Descriptor desc, String path) throws IOException {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Folder path is required");
        try {
            String token = token(desc);
            String driveId = driveId(desc, token);
            String full = joinPath(basePath(desc), path);
            int slash = full.lastIndexOf('/');
            String parent = slash < 0 ? "" : full.substring(0, slash);
            String name = slash < 0 ? full : full.substring(slash + 1);
            String body = "{\"name\":" + JSON.writeValueAsString(name)
                    + ",\"folder\":{},\"@microsoft.graph.conflictBehavior\":\"fail\"}";
            graphJson("POST", childrenUrl(driveId, parent), token, body);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SharePoint createFolder failed: " + rootMessage(e), e);
        }
    }

    @Override
    public String write(Descriptor desc, String relPath, java.nio.file.Path sourceFile, String contentType)
            throws IOException {
        try {
            String token = token(desc);
            String driveId = driveId(desc, token);
            String full = joinPath(basePath(desc), relPath);
            HttpRequest req = HttpRequest.newBuilder(URI.create(contentUrl(driveId, full)))
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                    .PUT(HttpRequest.BodyPublishers.ofFile(sourceFile))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new IOException("Graph upload returned HTTP " + res.statusCode() + ": " + truncate(res.body()));
            }
            return relPath;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SharePoint write failed for " + relPath + ": " + rootMessage(e), e);
        }
    }

    @Override
    public InputStream readObject(Descriptor desc, String relPath) throws IOException {
        try {
            String token = token(desc);
            String driveId = driveId(desc, token);
            HttpRequest req = HttpRequest.newBuilder(URI.create(contentUrl(driveId, joinPath(basePath(desc), relPath))))
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() / 100 != 2) {
                try (InputStream b = res.body()) { b.readAllBytes(); }
                throw new IOException("Graph download returned HTTP " + res.statusCode() + " for " + relPath);
            }
            return res.body();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SharePoint read failed for " + relPath + ": " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteObject(Descriptor desc, String relPath) throws IOException {
        try {
            String token = token(desc);
            String driveId = driveId(desc, token);
            HttpRequest req = HttpRequest.newBuilder(URI.create(itemUrl(driveId, joinPath(basePath(desc), relPath))))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .DELETE()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2 && res.statusCode() != 404) {
                throw new IOException("Graph delete returned HTTP " + res.statusCode() + ": " + truncate(res.body()));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SharePoint delete failed for " + relPath + ": " + rootMessage(e), e);
        }
    }

    @Override
    public List<RemoteEntry> list(Descriptor desc, Instant since) {
        Instant floor = since == null ? Instant.EPOCH : since;
        try {
            String token = token(desc);
            String driveId = driveId(desc, token);
            String base = basePath(desc);
            JsonNode res = graphJson("GET", childrenUrl(driveId, base), token, null);
            List<RemoteEntry> out = new ArrayList<>();
            for (JsonNode item : res.path("value")) {
                if (!item.has("file")) continue;
                String name = item.path("name").asText("");
                if (!SupportedFormats.isSupported(name)) continue;
                Instant modified = parseInstant(item.path("lastModifiedDateTime").asText(""));
                if (!modified.isAfter(floor)) continue;
                String rel = base.isBlank() ? name : trim(base) + "/" + name;
                out.add(new RemoteEntry(rel, "sharepoint:" + rel, name, item.path("size").asLong(-1), modified));
            }
            return out;
        } catch (Exception e) {
            log.debug("SharePoint list failed: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public InputStream open(Descriptor desc, RemoteEntry entry) throws IOException {
        return readObject(desc, entry.key());
    }

    // ── Graph plumbing ───────────────────────────────────────────────────────

    /** App-only token via the OAuth2 client-credentials endpoint (no MSAL dependency). */
    private String token(Descriptor desc) throws IOException, InterruptedException {
        Map<String, Object> cfg = desc.config();
        String tenant = str(cfg, "tenantId");
        String clientId = str(cfg, "clientId");
        String secret = credential(desc, "clientSecret");
        String form = "grant_type=client_credentials"
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(secret)
                + "&scope=" + enc("https://graph.microsoft.com/.default");
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://login.microsoftonline.com/" + enc(tenant) + "/oauth2/v2.0/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new IOException("Azure AD token request failed (HTTP " + res.statusCode() + "): " + truncate(res.body()));
        }
        String tok = JSON.readTree(res.body()).path("access_token").asText("");
        if (tok.isBlank()) throw new IOException("Azure AD returned no access_token");
        return tok;
    }

    private String driveId(Descriptor desc, String token) throws IOException, InterruptedException {
        String configured = str(desc.config(), "driveId");
        if (!configured.isBlank()) return configured;
        String siteId = str(desc.config(), "siteId");
        JsonNode drive = graphJson("GET", GRAPH + "/sites/" + enc(siteId) + "/drive", token, null);
        String id = drive.path("id").asText("");
        if (id.isBlank()) throw new IOException("Could not resolve default document library for site " + siteId);
        return id;
    }

    private JsonNode graphJson(String method, String url, String token, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + token);
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json")
             .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new IOException("Graph " + method + " returned HTTP " + res.statusCode() + ": " + truncate(res.body()));
        }
        return res.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(res.body());
    }

    private static String childrenUrl(String driveId, String path) {
        String p = trim(path);
        return p.isEmpty()
                ? GRAPH + "/drives/" + enc(driveId) + "/root/children"
                : GRAPH + "/drives/" + enc(driveId) + "/root:/" + encodePath(p) + ":/children";
    }

    private static String contentUrl(String driveId, String path) {
        return GRAPH + "/drives/" + enc(driveId) + "/root:/" + encodePath(trim(path)) + ":/content";
    }

    private static String itemUrl(String driveId, String path) {
        return GRAPH + "/drives/" + enc(driveId) + "/root:/" + encodePath(trim(path));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String basePath(Descriptor desc) { return trim(str(desc.config(), "basePath")); }

    private static String joinPath(String base, String rel) {
        String b = trim(base), r = trim(rel);
        if (b.isEmpty()) return r;
        return r.isEmpty() ? b : b + "/" + r;
    }

    private static String credential(Descriptor desc, String field) {
        String json = desc.credentialJson();
        if (json == null || json.isBlank()) return "";
        try { return JSON.readTree(json).path(field).asText(""); }
        catch (Exception e) { return ""; }
    }

    /** Percent-encode each path segment (keeps '/'), space as %20. */
    private static String encodePath(String path) {
        String[] segs = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String seg : segs) {
            if (seg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(enc(seg));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return Instant.EPOCH;
        try { return Instant.parse(s); } catch (Exception e) { return Instant.EPOCH; }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.getClass().getSimpleName() : r.getMessage();
    }
}
