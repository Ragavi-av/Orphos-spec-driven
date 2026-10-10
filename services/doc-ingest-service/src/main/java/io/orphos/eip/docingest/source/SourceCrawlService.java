package io.orphos.eip.docingest.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.docingest.ingestion.DocIngestionService;
import io.orphos.eip.docingest.ingestion.DocumentMetadata;
import io.orphos.eip.docingest.ingestion.DocumentRegistry;
import io.orphos.eip.docingest.secrets.SecretRefService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crawls remote folder sources and feeds each discovered file through the
 * existing document ingestion pipeline ({@link DocIngestionService#ingestUpload}
 * — store → parse → chunk → embed → Qdrant). Nothing about the downstream
 * pipeline changes; a crawled file is ingested exactly as an uploaded one.
 *
 * <p><b>Folder → department/category mapping</b> ("shared per the folder
 * settings"): every document from a connection inherits that connection's
 * {@code defaultDepartment}/{@code defaultCategory}/{@code defaultFolder}, and its
 * source path (e.g. {@code s3://bucket/key}) is recorded as the reference number
 * for provenance.</p>
 *
 * <p><b>Incremental sync</b>: only files modified after the connection's stored
 * watermark are pulled; the max last-modified seen is persisted on success, so
 * the next tick skips everything already ingested (mirrors the watermark pattern
 * in data-ingestion-service / okg-ingest-service).</p>
 */
@Service
public class SourceCrawlService {

    private static final Logger log = LogManager.getLogger(SourceCrawlService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourceConnectionRepository connections;
    private final SourceConnectorRegistry registry;
    private final SecretRefService secrets;
    private final DocIngestionService ingestionService;
    private final DocumentRegistry documentRegistry;

    public SourceCrawlService(SourceConnectionRepository connections,
                              SourceConnectorRegistry registry,
                              SecretRefService secrets,
                              DocIngestionService ingestionService,
                              DocumentRegistry documentRegistry) {
        this.connections = connections;
        this.registry = registry;
        this.secrets = secrets;
        this.ingestionService = ingestionService;
        this.documentRegistry = documentRegistry;
    }

    /** Crawl every enabled connection for the current tenant. Returns per-connection counts. */
    public Map<String, Object> crawlCurrentTenant() {
        List<Map<String, Object>> conns = connections.listEnabledForCurrentTenant();
        int totalSubmitted = 0;
        for (Map<String, Object> conn : conns) {
            totalSubmitted += crawlConnection(conn);
        }
        return Map.of("connections", conns.size(), "documentsSubmitted", totalSubmitted);
    }

    /** Crawl a single connection by id (current tenant). Returns the number of documents submitted. */
    public int crawlById(String id) {
        Map<String, Object> conn = connections.findById(id);
        if (conn == null) throw new IllegalArgumentException("connection not found: " + id);
        return crawlConnection(conn);
    }

    private int crawlConnection(Map<String, Object> conn) {
        String id = str(conn, "id");
        SourceKind kind;
        try {
            kind = SourceKind.valueOf(str(conn, "kind"));
        } catch (IllegalArgumentException e) {
            log.warn("Connection {} has unknown kind '{}' — skipping", id, str(conn, "kind"));
            return 0;
        }

        if (!registry.supports(kind)) {
            log.info("Connection {} kind {} not implemented yet — skipping", id, kind);
            return 0;
        }

        SourceConnector connector = registry.require(kind);
        String tenantId = str(conn, "tenantId");
        Instant since = parseInstant(str(conn, "watermark"));

        try {
            SourceConnector.Descriptor desc = descriptor(conn, kind);
            List<RemoteEntry> entries = connector.list(desc, since);
            log.info("Crawl {} [{}]: {} new/changed file(s) since {}", id, kind, entries.size(), since);

            int submitted = 0;
            Instant maxSeen = since;
            for (RemoteEntry entry : entries) {
                try {
                    submitOne(conn, tenantId, connector, desc, entry);
                    submitted++;
                    if (entry.lastModified() != null && entry.lastModified().isAfter(maxSeen)) {
                        maxSeen = entry.lastModified();
                    }
                } catch (Exception e) {
                    log.warn("Crawl {}: failed to ingest {} — {}", id, entry.path(), e.getMessage());
                }
            }

            // Advance the watermark to the newest file actually seen this run.
            if (maxSeen.isAfter(since)) connections.updateWatermark(id, maxSeen.toString());
            connections.recordSyncResult(id, true, "");
            log.info("Crawl {} complete: {}/{} document(s) submitted", id, submitted, entries.size());
            return submitted;
        } catch (Exception e) {
            log.error("Crawl {} [{}] failed: {}", id, kind, e.getMessage(), e);
            connections.recordSyncResult(id, false, e.getMessage());
            return 0;
        }
    }

    private void submitOne(Map<String, Object> conn, String tenantId, SourceConnector connector,
                           SourceConnector.Descriptor desc, RemoteEntry entry) throws Exception {
        String documentId = UUID.randomUUID().toString();
        DocumentMetadata metadata = DocumentMetadata.builder()
                .documentId(documentId)
                .tenantId(tenantId)
                .documentType(blankToNull(str(conn, "documentType")))
                .referenceNumber(entry.path())            // provenance: e.g. s3://bucket/key
                .title(entry.fileName())
                .fileName(entry.fileName())
                .category(blankToNull(str(conn, "defaultCategory")))
                .department(blankToNull(str(conn, "defaultDepartment")))
                .folder(blankToNull(str(conn, "defaultFolder")))
                .uploadedBy("connector:" + str(conn, "kind"))
                .build();

        Path temp = Files.createTempFile("doc-source-", "-" + sanitize(entry.fileName()));
        try (InputStream in = connector.open(desc, entry)) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        // Register then hand to the async pipeline (which deletes the temp file when done).
        documentRegistry.accepted(metadata, str(conn, "kind"));
        ingestionService.ingestUpload(temp, metadata);
    }

    private SourceConnector.Descriptor descriptor(Map<String, Object> conn, SourceKind kind) throws Exception {
        Map<String, Object> config = parseConfig(str(conn, "configJson"));
        String credentialRef = str(conn, "credentialRef");
        String credentialJson = credentialRef.isBlank() ? "" : secrets.resolve(credentialRef).orElse("");
        return new SourceConnector.Descriptor(kind, config, credentialJson);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseConfig(String json) throws Exception {
        if (json == null || json.isBlank()) return Map.of();
        return JSON.readValue(json, Map.class);
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return Instant.EPOCH;
        try { return Instant.parse(iso); } catch (Exception e) { return Instant.EPOCH; }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    private static String sanitize(String s) {
        return s == null ? "file" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
