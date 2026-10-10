package io.orphos.eip.docingest.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.docingest.secrets.SecretRefService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST API for remote source connections (S3/FTP/Confluence/Teams folders).
 * Lives under {@code /api/doc-ingest/admin/**}, so SecurityConfig already gates it
 * to ROLE_admin / platform_admin / ingestion_admin.
 *
 * <p>Credentials follow the platform pattern: the raw secret is accepted only on
 * write, immediately exchanged for a vault reference via {@link SecretRefService},
 * and never echoed back (views expose only a {@code hasCredential} boolean).</p>
 *
 * Endpoints:
 *   GET    /api/doc-ingest/admin/sources         — list connections (this tenant)
 *   POST   /api/doc-ingest/admin/sources         — create
 *   PUT    /api/doc-ingest/admin/sources/{id}    — update
 *   DELETE /api/doc-ingest/admin/sources/{id}    — delete (+ purge stored secret)
 *   POST   /api/doc-ingest/admin/sources/test    — validate connectivity before saving
 *   POST   /api/doc-ingest/admin/sources/{id}/sync — trigger an immediate crawl
 */
@RestController
@RequestMapping("/api/doc-ingest/admin/sources")
public class SourceConnectionController {

    private static final Logger log = LogManager.getLogger(SourceConnectionController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourceConnectionRepository repo;
    private final SourceConnectorRegistry registry;
    private final SecretRefService secrets;
    private final SourceCrawlService crawlService;

    public SourceConnectionController(SourceConnectionRepository repo,
                                      SourceConnectorRegistry registry,
                                      SecretRefService secrets,
                                      SourceCrawlService crawlService) {
        this.repo = repo;
        this.registry = registry;
        this.secrets = secrets;
        this.crawlService = crawlService;
    }

    // ── Request/response DTOs ──────────────────────────────────────────────────

    /** Non-secret config + optional raw credential (write-only). */
    public record SourceRequest(
            String kind,
            String displayName,
            Map<String, Object> config,     // bucket/prefix/region/endpoint/pathStyle …
            Map<String, String> credential, // e.g. {accessKeyId, secretAccessKey} — write-only, never stored raw
            String defaultDepartment,
            String defaultCategory,
            String defaultFolder,
            String documentType,
            Boolean enabled) {}

    // ── CRUD ───────────────────────────────────────────────────────────────────

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(name = "usableForStorage", required = false) Boolean usableForStorage) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : repo.list()) {
            Map<String, Object> v = view(row);
            if (Boolean.TRUE.equals(usableForStorage)
                    && !((List<?>) v.getOrDefault("capabilities", List.of())).contains("BROWSE")) {
                continue; // only connections a storage folder-picker can browse
            }
            out.add(v);
        }
        return out;
    }

    // ── Folder browsing (shared "Select folder" picker over any backend) ──────────

    /** Immediate sub-folders under {@code path} within this connection (blank = root). */
    @GetMapping("/{id}/browse")
    public ResponseEntity<?> browse(@PathVariable String id,
                                    @RequestParam(name = "path", required = false, defaultValue = "") String path) {
        Map<String, Object> row = repo.findById(id);
        if (row == null) return ResponseEntity.notFound().build();
        SourceKind kind = parseKind(String.valueOf(row.get("kind")));
        if (kind == null || !registry.supports(kind)) return badRequest("connector not available for this connection");
        SourceConnector connector = registry.require(kind);
        if (!connector.capabilities().contains(SourceConnector.Capability.BROWSE)) {
            return badRequest(kind + " does not support folder browsing");
        }
        try {
            List<SourceConnector.FolderEntry> entries = connector.browse(descriptorFor(row, kind), path);
            return ResponseEntity.ok(Map.of("path", path, "entries", entries));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", rootMessage(e)));
        }
    }

    public record FolderRequest(String path) {}

    /** Create a folder under this connection. */
    @PostMapping("/{id}/folders")
    public ResponseEntity<?> createFolder(@PathVariable String id, @RequestBody FolderRequest req) {
        Map<String, Object> row = repo.findById(id);
        if (row == null) return ResponseEntity.notFound().build();
        SourceKind kind = parseKind(String.valueOf(row.get("kind")));
        if (kind == null || !registry.supports(kind)) return badRequest("connector not available for this connection");
        SourceConnector connector = registry.require(kind);
        if (!connector.capabilities().contains(SourceConnector.Capability.BROWSE)) {
            return badRequest(kind + " does not support folder creation");
        }
        if (req == null || req.path() == null || req.path().isBlank()) return badRequest("path is required");
        try {
            connector.createFolder(descriptorFor(row, kind), req.path());
            return ResponseEntity.ok(Map.of("created", req.path()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", rootMessage(e)));
        }
    }

    /** Builds a connector Descriptor from a stored row: parsed config + the resolved (vaulted) credential. */
    private SourceConnector.Descriptor descriptorFor(Map<String, Object> row, SourceKind kind) {
        Map<String, Object> config = parseConfig(String.valueOf(row.getOrDefault("configJson", "")));
        String ref = String.valueOf(row.getOrDefault("credentialRef", ""));
        String credentialJson = (ref != null && !ref.isBlank()) ? secrets.resolve(ref).orElse("") : "";
        return new SourceConnector.Descriptor(kind, config, credentialJson);
    }

    private static Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return JSON.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.getClass().getSimpleName() : r.getMessage();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SourceRequest req) {
        SourceKind kind = parseKind(req.kind());
        if (kind == null) return badRequest("unknown or missing kind");
        if (!registry.supports(kind)) return badRequest(kind + " connector is not implemented yet");

        String credentialRef = storeCredentialOrNull(kind, req.credential());
        String id = repo.insert(
                kind.name(), req.displayName(), toJson(req.config()), credentialRef,
                req.defaultDepartment(), req.defaultCategory(), req.defaultFolder(),
                req.documentType(), req.enabled() == null || req.enabled());
        log.info("Created source connection {} ({})", id, kind);
        return ResponseEntity.ok(view(repo.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody SourceRequest req) {
        Map<String, Object> existing = repo.findById(id);
        if (existing == null) return ResponseEntity.notFound().build();
        SourceKind kind = SourceKind.valueOf(String.valueOf(existing.get("kind")));

        // A supplied credential rotates the stored one; omitted → keep existing.
        String credentialRef = storeCredentialOrNull(kind, req.credential());
        repo.update(id, req.displayName(), toJson(req.config()), credentialRef,
                req.defaultDepartment(), req.defaultCategory(), req.defaultFolder(),
                req.documentType(), req.enabled() == null || req.enabled());
        return ResponseEntity.ok(view(repo.findById(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        Map<String, Object> existing = repo.findById(id);
        if (existing == null) return ResponseEntity.notFound().build();
        String ref = String.valueOf(existing.getOrDefault("credentialRef", ""));
        repo.delete(id);
        try { secrets.delete(ref); } catch (Exception e) {
            log.warn("Deleted connection {} but failed to purge its secret: {}", id, e.getMessage());
        }
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ── Test & sync ──────────────────────────────────────────────────────────

    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestBody SourceRequest req) {
        SourceKind kind = parseKind(req.kind());
        if (kind == null) return badRequest("unknown or missing kind");
        if (!registry.supports(kind)) return badRequest(kind + " connector is not implemented yet");

        String credentialJson = req.credential() == null || req.credential().isEmpty()
                ? "" : toJson(req.credential());
        SourceConnector.Descriptor desc = new SourceConnector.Descriptor(
                kind, req.config() == null ? Map.of() : req.config(), credentialJson);
        SourceConnector.ProbeResult probe = registry.require(kind).test(desc);
        return ResponseEntity.ok(Map.of(
                "ok", probe.ok(),
                "message", probe.message(),
                "sampleCount", probe.sampleCount()));
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<?> sync(@PathVariable String id) {
        if (repo.findById(id) == null) return ResponseEntity.notFound().build();
        int submitted = crawlService.crawlById(id);
        return ResponseEntity.ok(Map.of("connectionId", id, "documentsSubmitted", submitted));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Store a raw credential map as a single JSON secret and return the vault ref, or null when none supplied. */
    private String storeCredentialOrNull(SourceKind kind, Map<String, String> credential) {
        if (credential == null || credential.isEmpty()) return null;
        boolean anyValue = credential.values().stream().anyMatch(v -> v != null && !v.isBlank());
        if (!anyValue) return null;
        return secrets.store(kind.name().toLowerCase(), "credential", toJson(credential));
    }

    /** Redacted view — never exposes the credential; adds the connector's support + capabilities. */
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> v = new LinkedHashMap<>();
        for (String k : new String[]{"id", "kind", "displayName", "configJson", "defaultDepartment",
                "defaultCategory", "defaultFolder", "documentType", "enabled", "watermark",
                "lastSyncAt", "lastSyncStatus", "lastSyncError", "createdAt", "updatedAt"}) {
            if (row.containsKey(k)) v.put(k, row.get(k));
        }
        String ref = String.valueOf(row.getOrDefault("credentialRef", ""));
        v.put("hasCredential", ref != null && !ref.isBlank());

        // Connector availability + what it can do (drives the shared Folder Connections UI + storage picker).
        SourceKind kind = parseKind(String.valueOf(row.get("kind")));
        boolean supported = kind != null && registry.supports(kind);
        v.put("supported", supported);
        v.put("capabilities", supported
                ? registry.require(kind).capabilities().stream().map(Enum::name).sorted().toList()
                : List.of());
        return v;
    }

    private static SourceKind parseKind(String s) {
        if (s == null || s.isBlank()) return null;
        try { return SourceKind.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static String toJson(Object o) {
        if (o == null) return "";
        try { return JSON.writeValueAsString(o); }
        catch (Exception e) { return ""; }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
