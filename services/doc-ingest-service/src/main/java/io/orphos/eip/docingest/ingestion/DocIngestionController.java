package io.orphos.eip.docingest.ingestion;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.docingest.audit.AuditEvent;
import io.orphos.eip.docingest.audit.FileAuditService;
import io.orphos.eip.docingest.llm.CaptureSummarizer;
import io.orphos.eip.docingest.settings.DocSettings;
import io.orphos.eip.docingest.settings.DocSettingsService;
import io.orphos.eip.docingest.storage.StorageRouter;
import jakarta.validation.constraints.NotBlank;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * REST API for document ingestion.
 *
 * Tenant isolation: the tenantId is always extracted from the JWT's
 * "tenant_id" claim (set by Keycloak per-realm mapper). Controllers never
 * trust a caller-supplied tenantId for write operations.
 *
 * Endpoints:
 *   POST /api/doc-ingest/upload             — multipart file upload
 *   POST /api/doc-ingest/extract            — recognise + summarise, nothing stored
 *   POST /api/doc-ingest/url                — ingest from a remote URL
 *   GET  /api/doc-ingest/documents          — list documents for the calling tenant
 *   GET  /api/doc-ingest/documents/{id}     — single document status
 *   DELETE /api/doc-ingest/admin/documents/{id} — delete stored file + registry entry
 *   GET  /api/doc-ingest/admin/audit        — admin: audit trail for this tenant
 *   GET  /api/doc-ingest/admin/folders      — admin: folder names for the chooser
 *   GET  /api/doc-ingest/health             — liveness (no auth)
 */
@RestController
@RequestMapping("/api/doc-ingest")
public class DocIngestionController {

    private static final Logger log = LogManager.getLogger(DocIngestionController.class);

    private final DocIngestionService ingestionService;
    private final DocumentRegistry documentRegistry;
    private final StorageRouter storageRouter;
    private final FileAuditService auditService;
    private final DocSettingsService settingsService;
    private final CaptureSummarizer captureSummarizer;

    public DocIngestionController(DocIngestionService ingestionService,
                                   DocumentRegistry documentRegistry,
                                   StorageRouter storageRouter,
                                   FileAuditService auditService,
                                   DocSettingsService settingsService,
                                   CaptureSummarizer captureSummarizer) {
        this.ingestionService  = ingestionService;
        this.documentRegistry  = documentRegistry;
        this.storageRouter     = storageRouter;
        this.auditService      = auditService;
        this.settingsService   = settingsService;
        this.captureSummarizer = captureSummarizer;
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentType", required = false) String documentType,
            @RequestParam(value = "referenceNumber", required = false) String referenceNumber,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "folder", required = false) String folder,
            @RequestParam(value = "inputType", required = false) String inputType,
            @RequestParam(value = "sourceChannel", required = false) String sourceChannel,
            @AuthenticationPrincipal Jwt jwt) throws Exception {

        String tenantId = currentTenantId();
        String documentId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        DocSettings.IngestionDefaults defaults = settingsService.get(tenantId).ingestion();

        DocumentMetadata metadata = DocumentMetadata.builder()
            .documentId(documentId)
            .tenantId(tenantId)
            .documentType(documentType)
            .referenceNumber(referenceNumber)
            .title(title)
            .fileName(fileName)
            .category(orDefault(category, defaults.defaultCategory()))
            .department(orDefault(department, defaults.defaultDepartment()))
            .folder(orDefault(folder, defaults.defaultFolder()))
            // New capture classification (audio/voice, image from camera/gallery,
            // handwriting) and where it came from (mobile). Default DOCUMENT/WEB.
            .inputType(orDefault(inputType, "DOCUMENT"))
            .sourceChannel(orDefault(sourceChannel, "WEB"))
            .uploadedBy(displayName(jwt))
            .build();

        documentRegistry.accepted(metadata, "UPLOAD");

        Path tempFile = Files.createTempFile("doc-ingest-", "-" + sanitize(fileName));
        file.transferTo(tempFile.toFile());

        ingestionService.ingestUpload(tempFile, metadata);

        return ResponseEntity.accepted().body(Map.of(
            "documentId", documentId,
            "status", "ACCEPTED",
            "message", "Document accepted for ingestion"));
    }

    // ── Personal capture (mobile Save) ───────────────────────────────────────

    /**
     * Ingests one user's OWN capture (a reviewed mobile note, voice note, image or
     * handwriting) so that user can find it in Document Search.
     *
     * <p>Unlike {@code /upload} this is not the admin Doc Ingestion tab, so it is left
     * out of the {@code doc-upload} RBAC route (as {@code /extract} is) and any member
     * can save their notes. What keeps that safe is the owner stamp: the caller's own
     * JWT subject, never a client-supplied value. Document Search shows an owned
     * document only to its owner and Copilot retrieval skips it, so this path cannot
     * publish into the shared tenant corpus.</p>
     */
    @PostMapping(value = "/captures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> ingestCapture(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "inputType", required = false) String inputType,
            @AuthenticationPrincipal Jwt jwt) throws Exception {

        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "A signed-in user is required"));
        }
        String tenantId = currentTenantId();
        String documentId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "capture";

        DocumentMetadata metadata = DocumentMetadata.builder()
            .documentId(documentId)
            .tenantId(tenantId)
            .title(title)
            .fileName(fileName)
            .category("Mobile Capture")
            .inputType(orDefault(inputType, "DOCUMENT"))
            .sourceChannel("MOBILE")
            .ownerSub(jwt.getSubject())
            .uploadedBy(displayName(jwt))
            .build();

        documentRegistry.accepted(metadata, "CAPTURE");

        Path tempFile = Files.createTempFile("doc-capture-", "-" + sanitize(fileName));
        file.transferTo(tempFile.toFile());

        ingestionService.ingestUpload(tempFile, metadata);

        return ResponseEntity.accepted().body(Map.of(
            "documentId", documentId,
            "status", "ACCEPTED",
            "message", "Capture accepted for ingestion"));
    }

    // ── Extract (review before ingest) ───────────────────────────────────────

    /**
     * Recognises and summarises a capture WITHOUT persisting it, so the user can
     * review and correct it first. {@code text} is text the client already
     * recognised (e.g. on-device handwriting recognition); when absent, the
     * {@code file} is run through the ingestion parse path (Tesseract OCR for
     * images). Nothing is stored — the reviewed result arrives later via
     * {@code /upload}. When OCR output does not read as language it is returned
     * with {@code lowConfidence: true} and no AI summary.
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> extract(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text) throws Exception {

        String tenantId = currentTenantId();
        String recognised = text == null ? "" : text.strip();
        String source = "CLIENT";
        if (recognised.isEmpty()) {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "file or text is required"));
            }
            String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "capture";
            Path tempFile = Files.createTempFile("doc-extract-", "-" + sanitize(fileName));
            try {
                file.transferTo(tempFile.toFile());
                recognised = OcrText.stripWatermark(ingestionService.extractText(tempFile));
            } finally {
                Files.deleteIfExists(tempFile);
            }
            source = "OCR";
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("text", recognised);
        out.put("source", source);
        // OCR noise (typically handwriting) must not be summarised: the AI would
        // invent a note from it. The client shows the text for the user to correct.
        if ("OCR".equals(source) && !OcrText.looksReadable(recognised)) {
            out.put("lowConfidence", true);
            return ResponseEntity.ok(out);
        }
        captureSummarizer.summarize(tenantId, recognised).ifPresent(s -> {
            out.put("title", s.title());
            out.put("summary", s.summary());
            out.put("keyPoints", s.keyPoints());
            out.put("actions", s.actions());
            out.put("decisions", s.decisions());
        });
        return ResponseEntity.ok(out);
    }

    // ── URL ingest ────────────────────────────────────────────────────────────

    @PostMapping("/url")
    public ResponseEntity<Map<String, Object>> ingestUrl(
            @RequestParam @NotBlank String url,
            @RequestParam(value = "documentType", required = false) String documentType,
            @RequestParam(value = "referenceNumber", required = false) String referenceNumber,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "folder", required = false) String folder,
            @AuthenticationPrincipal Jwt jwt) {

        String tenantId = currentTenantId();
        String documentId = UUID.randomUUID().toString();
        DocSettings.IngestionDefaults defaults = settingsService.get(tenantId).ingestion();

        DocumentMetadata metadata = DocumentMetadata.builder()
            .documentId(documentId)
            .tenantId(tenantId)
            .documentType(documentType)
            .referenceNumber(referenceNumber)
            .fileName(url)
            .category(orDefault(category, defaults.defaultCategory()))
            .department(orDefault(department, defaults.defaultDepartment()))
            .folder(orDefault(folder, defaults.defaultFolder()))
            .uploadedBy(displayName(jwt))
            .build();

        documentRegistry.accepted(metadata, "URL");
        ingestionService.ingestUrl(URI.create(url), metadata);

        return ResponseEntity.accepted().body(Map.of(
            "documentId", documentId,
            "status", "ACCEPTED",
            "message", "URL accepted for ingestion"));
    }

    // ── Document listing ──────────────────────────────────────────────────────

    @GetMapping("/documents")
    public List<DocumentRegistry.Entry> listDocuments(@AuthenticationPrincipal Jwt jwt) {
        return documentRegistry.list(currentTenantId());
    }

    /**
     * Size of the tenant's ingested corpus.
     *
     * <p>Split out from {@code GET /documents} because the two answer to
     * different audiences. The listing is an admin capability ({@code doc-list},
     * in the admin "documents" group) since it discloses every filename; the
     * COUNT discloses one integer and backs the dashboard hint telling a user
     * how much the co-pilot is reading from. Routing the hint through the
     * listing meant it 403'd for exactly the executive roles the dashboard is
     * built for.</p>
     *
     * <p>Mapped to {@code doc-search} in {@link
     * io.orphos.eip.docingest.access.AccessEnforcementConfig} — a user-dashboard
     * capability, so a tenant that revokes document search stops disclosing
     * corpus size along with it. That mapping MUST stay ahead of the
     * {@code /api/doc-ingest/documents} entry: the access filter prefix-matches
     * in insertion order, so a later entry would let this inherit
     * {@code doc-list} and reintroduce the 403.</p>
     */
    @GetMapping("/documents/count")
    public Map<String, Object> countDocuments(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", documentRegistry.count(currentTenantId()));
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<DocumentRegistry.Entry> getDocument(
            @PathVariable String documentId,
            @AuthenticationPrincipal Jwt jwt) {
        DocumentRegistry.Entry entry = documentRegistry.get(documentId);
        if (entry == null) return ResponseEntity.notFound().build();
        if (!currentTenantId().equals(entry.getTenantId())) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(entry);
    }

    // ── Admin operations ──────────────────────────────────────────────────────

    @DeleteMapping("/admin/documents/{documentId}")
    @PreAuthorize("hasAnyRole('admin','platform_admin','ingestion_admin')")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String documentId,
            @AuthenticationPrincipal Jwt jwt) {
        DocumentRegistry.Entry entry = documentRegistry.get(documentId);
        if (entry == null) return ResponseEntity.notFound().build();
        if (!currentTenantId().equals(entry.getTenantId())) return ResponseEntity.status(403).build();

        try {
            storageRouter.delete(entry.getStorageLocation());
        } catch (Exception e) {
            log.warn("Storage delete failed for {}: {}", documentId, e.getMessage());
        }
        // Purge the document's vector embeddings from Qdrant so deletion also
        // removes them from search results rather than orphaning them.
        ingestionService.removeVectors(documentId);
        documentRegistry.remove(documentId);
        auditService.record("DELETE", documentId, entry.getTenantId(),
            entry.getFileName(), displayName(jwt), "Admin delete");

        return ResponseEntity.noContent().build();
    }

    /**
     * Re-embeds all stored documents for the caller's tenant. Use after an
     * embedding-pipeline change (e.g. the nomic task-prefix fix) so existing
     * documents pick up the new vectors without manual re-upload. Old vectors
     * are cleared per document first to avoid duplicates.
     */
    @PostMapping("/admin/reindex")
    @PreAuthorize("hasAnyRole('admin','platform_admin','ingestion_admin')")
    public ResponseEntity<Map<String, Object>> reindex(@AuthenticationPrincipal Jwt jwt) {
        String tenantId = currentTenantId();
        int count = ingestionService.reindexTenant(tenantId);
        return ResponseEntity.ok(Map.of("reindexed", count));
    }

    /**
     * Human-readable identity for the "uploaded by" / audit fields:
     * preferred_username, then email, then the opaque sub as a last resort.
     */
    private static String displayName(Jwt jwt) {
        if (jwt == null) return "";
        for (String claim : new String[]{"preferred_username", "email"}) {
            String v = jwt.getClaimAsString(claim);
            if (v != null && !v.isBlank()) return v;
        }
        String sub = jwt.getSubject();
        return sub == null ? "" : sub;
    }

    @GetMapping("/admin/audit")
    @PreAuthorize("hasAnyRole('admin','platform_admin','ingestion_admin')")
    public List<AuditEvent> auditLog(@AuthenticationPrincipal Jwt jwt) {
        return auditService.readAll(currentTenantId());
    }

    /**
     * Lists the folder names a tenant can file documents under, backing the
     * "Default folder" chooser on the Ingestion Defaults tab. Unions the actual
     * storage-layer folders (top-level directories under the tenant's storage
     * root) with the distinct folder names recorded against existing documents,
     * so the chooser still offers choices before any file has landed on disk.
     */
    @GetMapping("/admin/folders")
    @PreAuthorize("hasAnyRole('admin','platform_admin','ingestion_admin')")
    public List<String> listFolders(@AuthenticationPrincipal Jwt jwt) {
        String tenantId = currentTenantId();
        return Stream.concat(
                storageRouter.listFolders(tenantId).stream(),
                documentRegistry.distinctFolders(tenantId).stream())
            .filter(f -> f != null && !f.isBlank())
            .distinct()
            .sorted()
            .toList();
    }

    /**
     * Explorer-style folder browser: the immediate sub-folders of {@code path}
     * (tenant-relative, '/'-separated, blank = storage root). On-disk folders
     * are unioned with folder paths recorded against existing documents, so
     * navigation works even for folders whose files haven't landed yet.
     */
    @GetMapping("/admin/folders/browse")
    @PreAuthorize("hasAnyRole('admin','platform_admin','ingestion_admin')")
    public Map<String, Object> browseFolders(
            @RequestParam(value = "path", required = false, defaultValue = "") String path,
            @AuthenticationPrincipal Jwt jwt) {
        String tenantId = currentTenantId();
        String normalized = normalizeFolderPath(path);

        // Disk children at this level.
        Map<String, Boolean> children = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var e : storageRouter.browse(tenantId, normalized)) {
            children.put(e.name(), e.hasChildren());
        }
        // Registry-known folders under this prefix (may be nested "a/b/c" paths).
        String prefix = normalized.isBlank() ? "" : normalized + "/";
        for (String folder : documentRegistry.distinctFolders(tenantId)) {
            if (folder == null || folder.isBlank()) continue;
            String f = normalizeFolderPath(folder);
            if (!f.startsWith(prefix) || f.equals(normalized)) continue;
            String rest = f.substring(prefix.length());
            if (rest.isBlank()) continue;
            int slash = rest.indexOf('/');
            String name = slash < 0 ? rest : rest.substring(0, slash);
            children.merge(name, slash >= 0, (a, b) -> a || b);
        }

        List<Map<String, Object>> entries = children.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "name", e.getKey(),
                        "path", prefix + e.getKey(),
                        "hasChildren", e.getValue()))
                .toList();

        String parent = null;
        if (!normalized.isBlank()) {
            int slash = normalized.lastIndexOf('/');
            parent = slash < 0 ? "" : normalized.substring(0, slash);
        }
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("backend", storageRouter.backendLabel());
        out.put("path", normalized);
        out.put("parent", parent);
        out.put("entries", entries);
        return out;
    }

    /** Create a folder (Explorer "New folder"). {@code path} is the full tenant-relative path. */
    @PostMapping("/admin/folders")
    @PreAuthorize("hasAnyRole('admin','platform_admin','ingestion_admin')")
    public ResponseEntity<?> createFolder(@RequestParam("path") String path,
                                          @AuthenticationPrincipal Jwt jwt) {
        String normalized = normalizeFolderPath(path);
        if (normalized.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Folder path is required"));
        }
        try {
            storageRouter.createFolder(currentTenantId(), normalized);
            return ResponseEntity.ok(Map.of("created", normalized));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Folder creation failed for '{}': {}", normalized, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Folder creation failed"));
        }
    }

    /** Collapses slashes/backslashes, trims blank and traversal segments. */
    private static String normalizeFolderPath(String path) {
        if (path == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String seg : path.replace('\\', '/').split("/")) {
            String s = seg.trim();
            if (s.isBlank() || s.equals(".") || s.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the acting tenant from {@link TenantContextHolder}, populated by
     * the common {@code TenantResolutionFilter}. This honours both the per-tenant
     * JWT {@code tenant_id} claim AND a platform_admin's {@code X-Tenant-Id}
     * acting-tenant header — without the latter, a platform_admin (whose JWT
     * carries no {@code tenant_id} claim) could not ingest, list, or manage
     * documents for a tenant they are administering.
     */
    private static String currentTenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Returns the caller-supplied value, or the tenant default when blank. */
    private static String orDefault(String supplied, String def) {
        return (supplied != null && !supplied.isBlank()) ? supplied : def;
    }
}
