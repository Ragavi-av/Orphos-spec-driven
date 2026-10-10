package io.orphos.eip.docingest.ingestion;

import io.orphos.eip.docingest.arcade.DocArcadeClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ArcadeDB-backed registry of every document submitted for ingestion.
 * Lifecycle: ACCEPTED → PROCESSING → COMPLETED | SKIPPED | FAILED.
 *
 * Persists to the DocIngestRecord document type (created by DocIngestSchemaBootstrap).
 * Thread-safe: each call is an independent HTTP request to ArcadeDB.
 */
@Component
public class DocumentRegistry {

    private static final Logger log = LogManager.getLogger(DocumentRegistry.class);

    public enum Status { ACCEPTED, PROCESSING, COMPLETED, SKIPPED, FAILED }

    private final DocArcadeClient arcade;

    public DocumentRegistry(DocArcadeClient arcade) {
        this.arcade = arcade;
    }

    // ── Public entry class (same shape as before — JSON-serializable) ─────────

    public static class Entry {
        private String documentId;
        private String fileName;
        private String title;
        private String referenceNumber;
        private String tenantId;
        private String documentType;
        private String source;
        private Status status;
        private int chunksStored;
        private int charactersExtracted;
        private String language;
        private String error;
        private Instant submittedAt;
        private Instant completedAt;
        private Long durationMs;
        private String uploadedBy;
        private Long fileSizeBytes;
        private String contentType;
        private String checksumSha256;
        private String storageBackend;
        private String storageLocation;
        private String category;
        private String department;
        private String folder;
        private String inputType;
        private String sourceChannel;
        private String ownerSub;

        public String getDocumentId()       { return documentId; }
        public String getFileName()          { return fileName; }
        public String getTitle()             { return title; }
        public String getReferenceNumber()   { return referenceNumber; }
        public String getTenantId()          { return tenantId; }
        public String getDocumentType()      { return documentType; }
        public String getSource()            { return source; }
        public Status getStatus()            { return status; }
        public int getChunksStored()         { return chunksStored; }
        public int getCharactersExtracted()  { return charactersExtracted; }
        public String getLanguage()          { return language; }
        public String getError()             { return error; }
        public Instant getSubmittedAt()      { return submittedAt; }
        public Instant getCompletedAt()      { return completedAt; }
        public Long getDurationMs()          { return durationMs; }
        public String getUploadedBy()        { return uploadedBy; }
        public Long getFileSizeBytes()       { return fileSizeBytes; }
        public String getContentType()       { return contentType; }
        public String getChecksumSha256()    { return checksumSha256; }
        public String getStorageBackend()    { return storageBackend; }
        public String getStorageLocation()   { return storageLocation; }
        public String getCategory()          { return category; }
        public String getDepartment()        { return department; }
        public String getFolder()            { return folder; }
        public String getInputType()         { return inputType; }
        public String getSourceChannel()     { return sourceChannel; }
        // Keycloak sub of the user a private capture belongs to; null for shared corpus.
        public String getOwnerSub()          { return ownerSub; }

        public void setTitle(String v)           { this.title = v; }
        public void setReferenceNumber(String v) { this.referenceNumber = v; }
        public void setDocumentType(String v)    { this.documentType = v; }
        public void setStatus(Status v)          { this.status = v; }
        public void setChunksStored(int v)       { this.chunksStored = v; }
        public void setCharactersExtracted(int v){ this.charactersExtracted = v; }
        public void setLanguage(String v)        { this.language = v; }
        public void setError(String v)           { this.error = v; }
        public void setCompletedAt(Instant v)    { this.completedAt = v; }
        public void setDurationMs(Long v)        { this.durationMs = v; }
        public void setFileSizeBytes(Long v)     { this.fileSizeBytes = v; }
        public void setContentType(String v)     { this.contentType = v; }
        public void setChecksumSha256(String v)  { this.checksumSha256 = v; }
        public void setStorageBackend(String v)  { this.storageBackend = v; }
        public void setStorageLocation(String v) { this.storageLocation = v; }
    }

    // ── Lifecycle transitions ─────────────────────────────────────────────────

    public void accepted(DocumentMetadata metadata, String source) {
        Map<String, Object> params = new HashMap<>();
        params.put("documentId",      metadata.getDocumentId());
        params.put("tenantId",        safe(metadata.getTenantId()));
        params.put("fileName",        safe(metadata.getFileName()));
        params.put("title",           safe(metadata.getTitle()));
        params.put("referenceNumber", safe(metadata.getReferenceNumber()));
        params.put("documentType",    safe(metadata.getDocumentType()));
        params.put("source",          source);
        params.put("uploadedBy",      safe(metadata.getUploadedBy()));
        params.put("category",        safe(metadata.getCategory()));
        params.put("department",      safe(metadata.getDepartment()));
        params.put("folder",          safe(metadata.getFolder()));
        params.put("inputType",       metadata.getInputType());
        params.put("sourceChannel",   metadata.getSourceChannel());
        params.put("ownerSub",        safe(metadata.getOwnerSub()));
        params.put("submittedAt",     Instant.now().toString());

        try {
            arcade.commandNoResult(
                "INSERT INTO DocIngestRecord SET " +
                "documentId = :documentId, tenantId = :tenantId, fileName = :fileName, " +
                "title = :title, referenceNumber = :referenceNumber, documentType = :documentType, " +
                "category = :category, department = :department, folder = :folder, " +
                "inputType = :inputType, sourceChannel = :sourceChannel, ownerSub = :ownerSub, " +
                "source = :source, status = 'ACCEPTED', uploadedBy = :uploadedBy, " +
                "submittedAt = :submittedAt, chunksStored = 0, charactersExtracted = 0",
                params);
        } catch (Exception e) {
            log.error("Failed to persist DocIngestRecord for {}: {}", metadata.getDocumentId(), e.getMessage());
        }
    }

    public void stored(String documentId, String backend, String location,
                       Long sizeBytes, String contentType, String checksum) {
        Map<String, Object> params = new HashMap<>();
        params.put("documentId",     documentId);
        params.put("storageBackend", safe(backend));
        params.put("storageLocation",safe(location));
        params.put("fileSizeBytes",  sizeBytes != null ? sizeBytes : 0L);
        params.put("contentType",    safe(contentType));
        params.put("checksumSha256", safe(checksum));

        try {
            arcade.commandNoResult(
                "UPDATE DocIngestRecord SET storageBackend = :storageBackend, " +
                "storageLocation = :storageLocation, fileSizeBytes = :fileSizeBytes, " +
                "contentType = :contentType, checksumSha256 = :checksumSha256 " +
                "WHERE documentId = :documentId",
                params);
        } catch (Exception e) {
            log.warn("Failed to update storage info for {}: {}", documentId, e.getMessage());
        }
    }

    public void processing(DocumentMetadata metadata) {
        // INSERT if missing (guard for BATCH flow where accepted() might be skipped)
        Entry existing = get(metadata.getDocumentId());
        if (existing == null) {
            accepted(metadata, "BATCH");
        }
        try {
            arcade.commandNoResult(
                "UPDATE DocIngestRecord SET status = 'PROCESSING' WHERE documentId = :documentId",
                Map.of("documentId", metadata.getDocumentId()));
        } catch (Exception e) {
            log.warn("Failed to set PROCESSING for {}: {}", metadata.getDocumentId(), e.getMessage());
        }
    }

    public void metadataExtracted(DocumentMetadata metadata) {
        Map<String, Object> params = new HashMap<>();
        params.put("documentId",      metadata.getDocumentId());
        params.put("title",           safe(metadata.getTitle()));
        params.put("referenceNumber", safe(metadata.getReferenceNumber()));
        params.put("documentType",    safe(metadata.getDocumentType()));
        params.put("category",        safe(metadata.getCategory()));
        params.put("department",      safe(metadata.getDepartment()));

        try {
            arcade.commandNoResult(
                "UPDATE DocIngestRecord SET title = :title, " +
                "referenceNumber = :referenceNumber, documentType = :documentType, " +
                "category = :category, department = :department " +
                "WHERE documentId = :documentId",
                params);
        } catch (Exception e) {
            log.warn("Failed to update metadata for {}: {}", metadata.getDocumentId(), e.getMessage());
        }
    }

    public void completed(String documentId, int chunks, int chars, String language) {
        finish(documentId, "COMPLETED", null, chunks, chars, language);
    }

    public void skipped(String documentId, String reason) {
        finish(documentId, "SKIPPED", reason, 0, 0, null);
    }

    public void failed(String documentId, String error) {
        finish(documentId, "FAILED", error, 0, 0, null);
    }

    public Entry get(String documentId) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM DocIngestRecord WHERE documentId = :documentId",
                Map.of("documentId", documentId));
            return rows.isEmpty() ? null : rowToEntry(rows.get(0));
        } catch (Exception e) {
            log.warn("Failed to fetch DocIngestRecord {}: {}", documentId, e.getMessage());
            return null;
        }
    }

    public Entry remove(String documentId) {
        Entry entry = get(documentId);
        try {
            arcade.commandNoResult(
                "DELETE FROM DocIngestRecord WHERE documentId = :documentId",
                Map.of("documentId", documentId));
        } catch (Exception e) {
            log.warn("Failed to delete DocIngestRecord {}: {}", documentId, e.getMessage());
        }
        return entry;
    }

    public List<Entry> list(String tenantId) {
        try {
            Map<String, Object> params = new HashMap<>();
            String sql;
            if (tenantId != null) {
                params.put("tenantId", tenantId);
                sql = "SELECT * FROM DocIngestRecord WHERE tenantId = :tenantId ORDER BY submittedAt DESC";
            } else {
                sql = "SELECT * FROM DocIngestRecord ORDER BY submittedAt DESC";
            }
            return arcade.query(sql, params).stream()
                .map(DocumentRegistry::rowToEntry)
                .toList();
        } catch (Exception e) {
            log.warn("Failed to list DocIngestRecords for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    /**
     * How many documents a tenant has ingested.
     *
     * <p>A COUNT rather than {@code list(tenantId).size()}: the only caller is
     * the dashboard's corpus hint, which needs the number and none of the rows,
     * and materialising every record to discard all of them would make an
     * ambient footnote scale with the size of the corpus it describes.</p>
     *
     * <p>Returns 0 rather than throwing — the caller renders a hint, and a
     * missing count should hide the line, not fail the request.</p>
     */
    public long count(String tenantId) {
        try {
            Map<String, Object> params = new HashMap<>();
            String sql;
            if (tenantId != null) {
                params.put("tenantId", tenantId);
                sql = "SELECT count(*) AS n FROM DocIngestRecord WHERE tenantId = :tenantId";
            } else {
                sql = "SELECT count(*) AS n FROM DocIngestRecord";
            }
            List<Map<String, Object>> rows = arcade.query(sql, params);
            if (rows.isEmpty()) return 0L;
            Object n = rows.get(0).get("n");
            return (n instanceof Number num) ? num.longValue() : 0L;
        } catch (Exception e) {
            log.warn("Failed to count DocIngestRecords for tenant {}: {}", tenantId, e.getMessage());
            return 0L;
        }
    }

    /** Distinct, non-blank folder names recorded against a tenant's documents. */
    public List<String> distinctFolders(String tenantId) {
        try {
            Map<String, Object> params = new HashMap<>();
            String sql;
            if (tenantId != null) {
                params.put("tenantId", tenantId);
                sql = "SELECT folder FROM DocIngestRecord " +
                      "WHERE tenantId = :tenantId AND folder IS NOT NULL AND folder <> ''";
            } else {
                sql = "SELECT folder FROM DocIngestRecord WHERE folder IS NOT NULL AND folder <> ''";
            }
            return arcade.query(sql, params).stream()
                .map(r -> str(r, "folder"))
                .filter(f -> f != null && !f.isBlank())
                .distinct()
                .sorted()
                .toList();
        } catch (Exception e) {
            log.warn("Failed to list distinct folders for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void finish(String documentId, String status, String error,
                        int chunks, int chars, String language) {
        // Compute submittedAt from persisted record to calculate durationMs
        Entry existing = get(documentId);
        Instant submittedAt = existing != null ? existing.getSubmittedAt() : null;
        Instant completedAt = Instant.now();
        Long durationMs = submittedAt != null
            ? completedAt.toEpochMilli() - submittedAt.toEpochMilli() : null;

        Map<String, Object> params = new HashMap<>();
        params.put("documentId",          documentId);
        params.put("status",              status);
        params.put("error",               error != null ? error : "");
        params.put("chunksStored",        chunks);
        params.put("charactersExtracted", chars);
        params.put("language",            language != null ? language : "");
        params.put("completedAt",         completedAt.toString());
        params.put("durationMs",          durationMs != null ? durationMs : 0L);

        try {
            arcade.commandNoResult(
                "UPDATE DocIngestRecord SET status = :status, error = :error, " +
                "chunksStored = :chunksStored, charactersExtracted = :charactersExtracted, " +
                "language = :language, completedAt = :completedAt, durationMs = :durationMs " +
                "WHERE documentId = :documentId",
                params);
        } catch (Exception e) {
            log.error("Failed to finish DocIngestRecord {} as {}: {}", documentId, status, e.getMessage());
        }
    }

    private static Entry rowToEntry(Map<String, Object> row) {
        Entry e = new Entry();
        e.documentId          = str(row, "documentId");
        e.fileName            = str(row, "fileName");
        e.title               = str(row, "title");
        e.referenceNumber     = str(row, "referenceNumber");
        e.tenantId            = str(row, "tenantId");
        e.documentType        = str(row, "documentType");
        e.source              = str(row, "source");
        e.status              = parseStatus(str(row, "status"));
        e.chunksStored        = toInt(row.get("chunksStored"));
        e.charactersExtracted = toInt(row.get("charactersExtracted"));
        e.language            = str(row, "language");
        e.error               = str(row, "error");
        e.submittedAt         = parseInstant(str(row, "submittedAt"));
        e.completedAt         = parseInstant(str(row, "completedAt"));
        e.durationMs          = toLong(row.get("durationMs"));
        e.uploadedBy          = str(row, "uploadedBy");
        e.fileSizeBytes       = toLong(row.get("fileSizeBytes"));
        e.contentType         = str(row, "contentType");
        e.checksumSha256      = str(row, "checksumSha256");
        e.storageBackend      = str(row, "storageBackend");
        e.storageLocation     = str(row, "storageLocation");
        e.category            = str(row, "category");
        e.department          = str(row, "department");
        e.folder              = str(row, "folder");
        e.inputType           = str(row, "inputType");
        e.sourceChannel       = str(row, "sourceChannel");
        String owner          = str(row, "ownerSub");
        e.ownerSub            = owner == null || owner.isBlank() ? null : owner;
        return e;
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : null;
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return null;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private static Status parseStatus(String s) {
        if (s == null) return Status.ACCEPTED;
        try { return Status.valueOf(s); } catch (Exception e) { return Status.ACCEPTED; }
    }

    private static String safe(String v) { return v != null ? v : ""; }
}
