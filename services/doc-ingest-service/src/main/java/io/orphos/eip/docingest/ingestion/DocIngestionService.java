package io.orphos.eip.docingest.ingestion;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.resilience4j.retry.Retry;
import io.orphos.eip.common.events.EipEventPublisher;
import io.orphos.eip.common.lang.LanguageDetector;
import io.orphos.eip.common.resilience.EipRetries;
import io.orphos.eip.docingest.audit.FileAuditService;
import io.orphos.eip.docingest.llm.DocumentAiEnricher;
import io.orphos.eip.docingest.settings.DocSettings;
import io.orphos.eip.docingest.settings.DocSettingsService;
import io.orphos.eip.docingest.ingestion.structure.Block;
import io.orphos.eip.docingest.ingestion.structure.StructureAwareChunker;
import io.orphos.eip.docingest.ingestion.structure.StructuredDocumentParser;
import io.orphos.eip.docingest.storage.StorageRouter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Document ingestion pipeline for Orphos.
 *
 * Ported from Ragas MVP IngestionService with the following adaptations:
 *   - departmentCode → tenantId (Orphos multi-tenancy via Keycloak JWT claim)
 *   - Milvus EmbeddingStore → Qdrant EmbeddingStore (existing Orphos infra)
 *   - bge-m3 (multilingual, 1024-dim) for bilingual JA+EN retrieval
 *   - Document metadata backed by DocumentRegistry (in-memory PoC; ArcadeDB in Phase 2)
 *   - OCR via native Tesseract binary installed in the container (no tess4j needed)
 *
 * Pipeline stages:
 *   1. Store  — persist original file via StorageRouter (LOCAL / AES-256-GCM)
 *   2. Parse  — Tika XHTML → StructuredDocumentParser → Block list + plain text
 *   3. Chunk  — StructureAwareChunker (semantic: atomic tables, whole clauses, breadcrumbs)
 *   4. Embed  — nomic-embed-text via Ollama, batched 32 at a time
 *   5. Index  — vectors + metadata payload written to Qdrant collection
 */
@Service
public class DocIngestionService {

    private static final Logger log = LogManager.getLogger(DocIngestionService.class);

    private static final int CHUNK_SIZE_TOKENS    = 512;
    private static final int CHUNK_OVERLAP_TOKENS = 64;
    private static final int EMBEDDING_BATCH_SIZE = 32;

    /** Min characters of extracted text to attempt language detection on. */
    private static final int LANG_DETECT_MIN_CHARS = 20;

    /** Trust manager that accepts any server cert — used only when verify-tls is off. */
    private static final javax.net.ssl.X509TrustManager TRUST_ALL =
        new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        };

    /** Built in {@link #initHttpClient()} so it can honour the verify-tls flag. */
    private HttpClient httpClient;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final StructuredDocumentParser structuredParser;
    private final StructureAwareChunker structureChunker;
    private final DoclingParser doclingParser;
    private final TranscriptionClient transcriptionClient;
    private final TesseractOcr photoOcr;
    private final DocumentRegistry documentRegistry;
    private final StorageRouter storageRouter;
    private final FileAuditService auditService;
    private final MeterRegistry meterRegistry;
    private final DocSettingsService settingsService;
    private final EipEventPublisher eventPublisher;
    private final DocumentAiEnricher aiEnricher;
    private final UrlEgressGuard urlEgressGuard;
    private final Retry embedRetry;

    @Value("${doc-ingest.chunking-strategy:semantic}")
    private String chunkingStrategy;

    // bge-m3 needs NO task-instruction prefix (unlike nomic-embed-text, which
    // required "search_document: "). The knob is kept so an operator can re-add a
    // prefix if the embedding model is swapped again; default is empty. Applied
    // ONLY to the text sent to the model — stored segment text stays clean.
    @Value("${doc-ingest.embedding.document-prefix:}")
    private String documentPrefix;

    // Hard cap on the characters sent to the embedding model per input. The
    // structure-aware chunker deliberately keeps some units atomic (whole tables,
    // whole clauses), so a single chunk can exceed the embedding runtime's
    // physical batch (llama.cpp n_batch, 2048 tokens by default) and get rejected
    // with "input too large to process", failing the whole document. We split any
    // oversized chunk into embed-safe pieces first. 1600 chars stays comfortably
    // under 2048 tokens even for single-token-per-char CJK text, while normal
    // ~512-token chunks are untouched. Operator-tunable.
    @Value("${doc-ingest.embedding.max-chars-per-input:1600}")
    private int maxEmbedChars;

    // TLS verification defaults ON. Disabling it (doc-ingest.url.verify-tls=false)
    // lets the fetcher accept any server certificate — only turn it off, per
    // deployment, when you must index a specific public site whose chain the JVM
    // truststore can't build ("PKIX path building failed") AND you trust the
    // network path. Combined with an SSRF egress guard (UrlEgressGuard) so a
    // relaxed client can never be pointed at internal hosts.
    @Value("${doc-ingest.url.verify-tls:true}")
    private boolean verifyTls;

    /** Max redirect hops the fetcher follows manually (each re-validated). */
    private static final int MAX_REDIRECTS = 5;

    @jakarta.annotation.PostConstruct
    void initHttpClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
            // Redirects are NOT auto-followed: the fetcher follows them manually
            // so UrlEgressGuard re-validates every hop (an allowed public URL
            // must not be able to 302 the server onto an internal address).
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30));
        if (!verifyTls) {
            log.warn("doc-ingest.url.verify-tls=false — URL ingestion will NOT validate TLS "
                + "certificates. Acceptable for indexing public content; do not enable for "
                + "fetches that rely on server identity.");
            try {
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(null, new javax.net.ssl.TrustManager[]{ TRUST_ALL },
                    new java.security.SecureRandom());
                b.sslContext(ctx);
            } catch (Exception e) {
                log.error("Could not build relaxed SSL context; using default trust: {}", e.getMessage());
            }
        }
        this.httpClient = b.build();
    }

    public DocIngestionService(EmbeddingModel embeddingModel,
                               EmbeddingStore<TextSegment> embeddingStore,
                               StructuredDocumentParser structuredParser,
                               StructureAwareChunker structureChunker,
                               DoclingParser doclingParser,
                               TranscriptionClient transcriptionClient,
                               TesseractOcr photoOcr,
                               DocumentRegistry documentRegistry,
                               StorageRouter storageRouter,
                               FileAuditService auditService,
                               MeterRegistry meterRegistry,
                               DocSettingsService settingsService,
                               EipEventPublisher eventPublisher,
                               DocumentAiEnricher aiEnricher,
                               UrlEgressGuard urlEgressGuard,
                               EipRetries retries) {
        this.embeddingModel   = embeddingModel;
        this.embeddingStore   = embeddingStore;
        this.structuredParser = structuredParser;
        this.structureChunker = structureChunker;
        this.doclingParser    = doclingParser;
        this.transcriptionClient = transcriptionClient;
        this.photoOcr         = photoOcr;
        this.documentRegistry = documentRegistry;
        this.storageRouter    = storageRouter;
        this.auditService     = auditService;
        this.meterRegistry    = meterRegistry;
        this.settingsService  = settingsService;
        this.eventPublisher   = eventPublisher;
        this.aiEnricher       = aiEnricher;
        this.urlEgressGuard   = urlEgressGuard;
        this.embedRetry       = retries.retry("doc-ingest-embed");
    }

    /**
     * Notifies the event-communication framework of an ingestion failure so
     * tenants with a DOC_INGEST_FAILED rule (Admin → Communications → Event
     * Alerts) get an email. Fire-and-forget — never affects the pipeline.
     */
    private void publishIngestFailedEvent(DocumentMetadata metadata, String error) {
        try {
            eventPublisher.publish(
                EipEventPublisher.DOC_INGEST_FAILED,
                EipEventPublisher.SEVERITY_WARNING,
                "Document ingestion failed: " + safe(metadata.getFileName()),
                "Ingestion of '" + safe(metadata.getFileName()) + "' failed and the document "
                    + "was not indexed. See attributes for the error detail.",
                metadata.getTenantId(),
                java.util.Map.of(
                    "documentId", safe(metadata.getDocumentId()),
                    "fileName", safe(metadata.getFileName()),
                    "uploadedBy", safe(metadata.getUploadedBy()),
                    "error", error == null ? "" : error));
        } catch (Exception e) {
            log.warn("DOC_INGEST_FAILED event not published for {}: {}",
                metadata.getDocumentId(), e.getMessage());
        }
    }

    // ── Public entry points ──────────────────────────────────────────────────

    /**
     * Accepts a multipart upload: stores the file, then runs the ingestion pipeline
     * asynchronously. Deletes the temp file when the pipeline finishes.
     */
    @Async("ingestionExecutor")
    public void ingestUpload(Path tempFile, DocumentMetadata metadata) {
        try {
            storeOriginal(tempFile, metadata);
            ingestSync(tempFile, metadata);
        } finally {
            try { Files.deleteIfExists(tempFile); }
            catch (IOException io) {
                log.warn("Failed to delete temp file {}: {}", tempFile, io.getMessage());
            }
        }
    }

    /**
     * Fetches a URL to a temp file and runs the ingestion pipeline asynchronously.
     */
    @Async("ingestionExecutor")
    public void ingestUrl(URI url, DocumentMetadata metadata) {
        Path tempFile = null;
        try {
            tempFile = downloadToTempFile(url);
            storeOriginal(tempFile, metadata);
            ingestSync(tempFile, metadata);
        } catch (Exception e) {
            meterRegistry.counter("eip.doc_ingest.errors",
                "tenant", safe(metadata.getTenantId()),
                "error_type", e.getClass().getSimpleName(),
                "source", "url").increment();
            documentRegistry.failed(metadata.getDocumentId(), e.getMessage());
            auditService.record("INGEST_FAILED", metadata.getDocumentId(), metadata.getTenantId(),
                metadata.getFileName(), metadata.getUploadedBy(), e.getMessage());
            publishIngestFailedEvent(metadata, e.getMessage());
            log.error("URL ingestion failed for {} [tenant={}]: {}",
                url, metadata.getTenantId(), e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); }
                catch (IOException io) {
                    log.warn("Failed to delete temp file {}: {}", tempFile, io.getMessage());
                }
            }
        }
    }

    /**
     * Batch ingestion from a local directory (initial corpus load).
     */
    public void ingestDirectory(Path directory, String tenantId, String documentType, String uploadedBy) {
        log.info("Batch ingestion from {} [tenant={}]", directory, tenantId);
        AtomicInteger count = new AtomicInteger(0);
        try (var paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                .filter(this::isSupportedFormat)
                .forEach(filePath -> {
                    DocumentMetadata metadata = DocumentMetadata.builder()
                        .documentId(UUID.randomUUID().toString())
                        .tenantId(tenantId)
                        .documentType(documentType)
                        .fileName(filePath.getFileName().toString())
                        .uploadedBy(uploadedBy)
                        .build();
                    documentRegistry.accepted(metadata, "BATCH");
                    ingestUpload(filePath, metadata);
                    count.incrementAndGet();
                });
        } catch (IOException e) {
            throw new IngestionException("Failed to walk directory: " + directory, e);
        }
        log.info("Batch ingestion submitted for {} documents from {}", count.get(), directory);
    }

    // ── Extract only (no store / index) ──────────────────────────────────────

    /**
     * Text of one file through the same parse path ingestion uses (audio →
     * Whisper, Docling, then Tika with Tesseract OCR for images) without storing,
     * chunking or indexing it. Backs the mobile capture review step, where the
     * user corrects the recognised text before anything is persisted.
     */
    public String extractText(Path filePath) {
        String text = parseForStrategy(filePath).text();
        return text == null ? "" : text.strip();
    }

    // ── Re-index (re-embed existing documents) ───────────────────────────────

    /**
     * Re-embeds every stored document for a tenant from its persisted original,
     * so an embedding change (e.g. adding the nomic task prefix) takes effect
     * without a manual re-upload. Old vectors are removed first to avoid
     * duplicates. Runs synchronously; returns the count re-indexed.
     */
    public int reindexTenant(String tenantId) {
        int reindexed = 0;
        for (DocumentRegistry.Entry d : documentRegistry.list(tenantId)) {
            if (d.getStorageLocation() == null || d.getStorageLocation().isBlank()) {
                log.info("Skipping re-index of {} — no stored original on this backend", d.getDocumentId());
                continue;
            }
            try {
                reindexOne(d, tenantId);
                reindexed++;
            } catch (Exception e) {
                log.warn("Re-index failed for {} [tenant={}]: {}",
                    d.getDocumentId(), tenantId, e.getMessage());
            }
        }
        log.info("Re-index complete: {} document(s) for tenant {}", reindexed, tenantId);
        return reindexed;
    }

    private void reindexOne(DocumentRegistry.Entry d, String tenantId) throws IOException {
        removeVectors(d.getDocumentId());
        Path temp = Files.createTempFile("doc-reindex-", "-" + sanitize(safe(d.getFileName())));
        try (InputStream in = storageRouter.openForRead(d.getStorageLocation())) {
            Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            DocumentMetadata meta = DocumentMetadata.builder()
                .documentId(d.getDocumentId())
                .tenantId(tenantId)
                .documentType(d.getDocumentType())
                .referenceNumber(d.getReferenceNumber())
                .title(d.getTitle())
                .fileName(d.getFileName())
                .category(d.getCategory())
                .department(d.getDepartment())
                .folder(d.getFolder())
                .uploadedBy(d.getUploadedBy())
                // Carried over, or a re-index would turn a private capture into shared corpus.
                .inputType(d.getInputType())
                .sourceChannel(d.getSourceChannel())
                .ownerSub(d.getOwnerSub())
                .build();
            ingestSync(temp, meta);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Removes all stored vectors for a document from the embedding store.
     * Used both by re-index (so a re-embed doesn't duplicate chunks) and by
     * admin delete (so deleting a document also purges its embeddings from
     * Qdrant rather than orphaning them and polluting search results).
     */
    public void removeVectors(String documentId) {
        try {
            embeddingStore.removeAll(metadataKey("document_id").isEqualTo(documentId));
        } catch (Exception e) {
            log.warn("Could not remove existing vectors for {} (re-index may duplicate chunks): {}",
                documentId, e.getMessage());
        }
    }

    // ── Internal sync pipeline ───────────────────────────────────────────────

    private void ingestSync(Path filePath, DocumentMetadata metadata) {
        Timer.Sample sample = Timer.start(meterRegistry);
        log.info("Ingesting {} [tenant={}]", filePath.getFileName(), metadata.getTenantId());
        documentRegistry.processing(metadata);

        try {
            ParsedContent content = parseForStrategy(filePath);
            String rawText = content.text();

            if (rawText == null || rawText.isBlank()) {
                log.warn("No text extracted from {}. "
                    + "If this is a scanned PDF ensure tesseract-ocr is installed in the container.",
                    filePath.getFileName());
                meterRegistry.counter("eip.doc_ingest.skipped", "reason", "empty_text").increment();
                documentRegistry.skipped(metadata.getDocumentId(), "No text could be extracted");
                return;
            }

            // Fill in title from embedded document properties when absent
            if ((metadata.getTitle() == null || metadata.getTitle().isBlank())
                    && content.embeddedTitle() != null) {
                metadata.setTitle(content.embeddedTitle());
                documentRegistry.metadataExtracted(metadata);
            }

            // Detect document language (bilingual JA+EN) when the caller didn't
            // supply one. Flows into the Qdrant payload (documentLevelMetadata)
            // and the DocIngestRecord so search/UI can filter or display it.
            if ((metadata.getLanguage() == null || metadata.getLanguage().isBlank())
                    && rawText.length() >= LANG_DETECT_MIN_CHARS) {
                String detected = LanguageDetector.detect(rawText);
                if (!"und".equals(detected)) {
                    metadata.setLanguage(detected);
                    log.debug("Detected language '{}' for document {}", detected, metadata.getDocumentId());
                }
            }

            // Optional AI metadata enrichment (per-tenant opt-in) BEFORE chunking,
            // so filled fields flow into the Qdrant chunk payloads too. Best-effort:
            // any failure leaves the uploader's metadata untouched.
            if (aiEnricher.enrich(metadata, rawText)) {
                documentRegistry.metadataExtracted(metadata);
                meterRegistry.counter("eip.doc_ingest.ai_enriched",
                    "tenant", safe(metadata.getTenantId())).increment();
            }

            List<TextSegment> chunks = content.blocks() != null
                ? structureChunker.chunk(content.blocks(), documentLevelMetadata(metadata, rawText))
                : chunkDocument(rawText, metadata);

            log.info("Created {} chunks from {} [strategy={}]",
                chunks.size(), filePath.getFileName(),
                content.blocks() != null ? "semantic" : "recursive");

            int totalStored = embedAndStore(chunks, metadata);

            sample.stop(meterRegistry.timer("eip.doc_ingest.duration",
                "tenant", safe(metadata.getTenantId()),
                "document_type", safe(metadata.getDocumentType())));
            meterRegistry.counter("eip.doc_ingest.chunks_stored",
                "tenant", safe(metadata.getTenantId())).increment(totalStored);
            meterRegistry.counter("eip.doc_ingest.documents_success",
                "tenant", safe(metadata.getTenantId())).increment();

            documentRegistry.completed(metadata.getDocumentId(), totalStored, rawText.length(),
                safe(metadata.getLanguage()));
            auditService.record("INGEST_COMPLETE", metadata.getDocumentId(), metadata.getTenantId(),
                metadata.getFileName(), metadata.getUploadedBy(),
                totalStored + " chunks stored");

            log.info("Ingestion complete: {} chunks for document {} [tenant={}]",
                totalStored, metadata.getDocumentId(), metadata.getTenantId());

        } catch (Exception e) {
            meterRegistry.counter("eip.doc_ingest.errors",
                "tenant", safe(metadata.getTenantId()),
                "error_type", e.getClass().getSimpleName()).increment();
            // Embedding is batched, so a mid-document failure (an unreachable
            // Ollama, a model that has not been pulled) leaves the earlier batches
            // already written to Qdrant. Those orphans belong to a document the
            // registry now reports as FAILED, and leaving them behind means search
            // answers out of a document nobody can see the ingest status of. Roll
            // the vectors back so a failed ingest is all-or-nothing.
            removeVectors(metadata.getDocumentId());
            documentRegistry.failed(metadata.getDocumentId(), e.getMessage());
            auditService.record("INGEST_FAILED", metadata.getDocumentId(), metadata.getTenantId(),
                metadata.getFileName(), metadata.getUploadedBy(), e.getMessage());
            publishIngestFailedEvent(metadata, e.getMessage());
            log.error("Ingestion failed for {} [tenant={}]: {}",
                metadata.getDocumentId(), metadata.getTenantId(), e.getMessage(), e);
            throw new IngestionException("Ingestion failed: " + metadata.getDocumentId(), e);
        }
    }

    // ── Stage 0: Store original ──────────────────────────────────────────────

    private void storeOriginal(Path file, DocumentMetadata metadata) {
        try {
            String storageConnectionId = settingsService.get(metadata.getTenantId())
                .ingestion().storageConnectionId();
            StorageRouter.StoreResult result = storageRouter.store(
                file, metadata.getTenantId(), buildRelativeDir(metadata),
                metadata.getDocumentId(), probeContentType(file), storageConnectionId);
            documentRegistry.stored(metadata.getDocumentId(),
                result.backend().name(), result.location(),
                result.sizeBytes(), result.contentType(), result.checksumSha256());
            auditService.record("UPLOAD", metadata.getDocumentId(), metadata.getTenantId(),
                metadata.getFileName(), metadata.getUploadedBy(),
                "Stored at " + result.location());
        } catch (IOException e) {
            log.warn("Could not persist original file for {} — pipeline continues: {}",
                metadata.getDocumentId(), e.getMessage());
        }
    }

    /**
     * Builds the storage sub-path under {baseDir}/{tenantId} from the tenant's
     * {@code organizeBy} setting and the document's folder/department. Layout is
     * {folder}/{department}/{fileType}, with each segment included only when the
     * setting calls for it and the value is present.
     */
    private String buildRelativeDir(DocumentMetadata m) {
        DocSettings.IngestionDefaults d = settingsService.get(m.getTenantId()).ingestion();
        String organizeBy = d.organizeBy() != null ? d.organizeBy() : "DEPARTMENT_FILETYPE";
        boolean byDept = organizeBy.contains("DEPARTMENT");
        boolean byType = organizeBy.contains("FILETYPE");

        List<String> segs = new ArrayList<>();
        if (m.getFolder() != null && !m.getFolder().isBlank())     segs.add(m.getFolder());
        if (byDept && m.getDepartment() != null && !m.getDepartment().isBlank())
            segs.add(m.getDepartment());
        if (byType) {
            String ext = fileExtension(m.getFileName());
            segs.add(ext.isBlank() ? "other" : ext);
        }
        return String.join("/", segs);
    }

    private static String fileExtension(String fileName) {
        if (fileName == null) return "";
        // For URLs, take the last path segment first.
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) return "";
        String ext = name.substring(dot + 1).toLowerCase();
        // Guard against query strings on URLs (e.g. "pdf?token=…").
        int q = ext.indexOf('?');
        if (q >= 0) ext = ext.substring(0, q);
        return ext.matches("[a-z0-9]{1,8}") ? ext : "";
    }

    // ── Stage 1: Parse ───────────────────────────────────────────────────────

    private record ParsedContent(String text, String embeddedTitle, List<Block> blocks) {}

    private ParsedContent parseForStrategy(Path filePath) {
        // Audio first: a voice note / audio file has no extractable text, so
        // transcribe it via the Whisper sidecar and feed the transcript into the
        // same chunk→embed→store pipeline as any document. This is how mobile voice
        // captures become searchable. Falls through only if transcription is off.
        if (transcriptionClient.handles(filePath)) {
            String transcript = transcriptionClient.transcribe(filePath);
            if (transcript != null && !transcript.isBlank()) {
                log.info("Transcribed audio {} ({} chars)", filePath.getFileName(), transcript.length());
                meterRegistry.counter("eip.doc_ingest.audio.transcribed").increment();
                return new ParsedContent(transcript, null, null);
            }
            log.warn("Audio {} not transcribed (Whisper unavailable/empty); storing without text",
                filePath.getFileName());
            meterRegistry.counter("eip.doc_ingest.audio.transcribe_failed").increment();
            return new ParsedContent("", null, null);
        }

        // Phone photos are stored sideways with an EXIF rotation flag that neither
        // Docling nor Tesseract honours; OCR an upright copy instead. The original
        // file (which the upload path stores) is left untouched.
        Path upright = ImageOrientation.uprightCopy(filePath);
        Path page = upright != null ? upright : filePath;
        try {
            // Photos: whole-page Tesseract first — Docling reads a photographed page
            // as a picture and only OCRs fragments. Docling stays the fallback when
            // Tesseract finds nothing that reads as language.
            if (photoOcr.handles(filePath)) {
                String text = photoOcr.read(page);
                if (OcrText.looksReadable(text)) {
                    log.info("OCR'd photo {} via Tesseract ({} chars)", filePath.getFileName(), text.length());
                    meterRegistry.counter("eip.doc_ingest.photo_ocr.tesseract").increment();
                    return new ParsedContent(text, null, null);
                }
                meterRegistry.counter("eip.doc_ingest.photo_ocr.fallback").increment();
            }
            return parseDocument(page);
        } finally {
            if (upright != null) {
                try {
                    Files.deleteIfExists(upright);
                } catch (IOException e) {
                    log.debug("Could not delete upright copy {}: {}", upright, e.getMessage());
                }
            }
        }
    }

    private ParsedContent parseDocument(Path filePath) {
        // Docling sidecar first (when enabled for this file type): layout-aware
        // HTML that reuses the SAME XHTML→Block pipeline as Tika, so semantic
        // chunking, tables and Japanese OCR all flow through unchanged. Any
        // failure or empty result falls through to the Tika paths below.
        if (doclingParser.handles(filePath)) {
            DoclingParser.Result docling = doclingParser.parse(filePath);
            if (docling != null) {
                StructuredDocumentParser.ParsedDocument structured =
                    structuredParser.parseXhtml(docling.html());
                if (!structured.blocks().isEmpty()) {
                    log.info("Parsed {} via Docling ({} blocks)",
                        filePath.getFileName(), structured.blocks().size());
                    return new ParsedContent(
                        structured.plainText(), structured.embeddedTitle(), structured.blocks());
                }
                log.warn("Docling HTML produced no blocks for {}; falling back to Tika",
                    filePath.getFileName());
                meterRegistry.counter("eip.doc_ingest.docling.fallback").increment();
            }
        }

        if ("semantic".equalsIgnoreCase(chunkingStrategy)) {
            try {
                StructuredDocumentParser.ParsedDocument structured = structuredParser.parse(filePath);
                if (!structured.blocks().isEmpty()) {
                    return new ParsedContent(
                        structured.plainText(), structured.embeddedTitle(), structured.blocks());
                }
                log.warn("Structured parse produced no blocks for {}; falling back to plain text",
                    filePath.getFileName());
            } catch (Exception e) {
                log.warn("Structured parse failed for {} ({}); falling back to recursive chunking",
                    filePath.getFileName(), e.getMessage());
                meterRegistry.counter("eip.doc_ingest.structured_parse.fallback").increment();
            }
        }
        ParsedDocument parsed = parsePlain(filePath);
        return new ParsedContent(parsed.text(), parsed.embeddedTitle(), null);
    }

    private record ParsedDocument(String text, String embeddedTitle) {}

    private ParsedDocument parsePlain(Path filePath) {
        try (InputStream stream = Files.newInputStream(filePath)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            org.apache.tika.metadata.Metadata tikaMetadata = new org.apache.tika.metadata.Metadata();
            new AutoDetectParser().parse(stream, handler, tikaMetadata, new ParseContext());
            String text = handler.toString().replaceAll("\\s{3,}", "\n\n").trim();
            return new ParsedDocument(text, tikaMetadata.get(TikaCoreProperties.TITLE));
        } catch (IOException | SAXException | TikaException e) {
            throw new IngestionException("Text extraction failed for: " + filePath, e);
        }
    }

    // ── Stage 2: Chunk ───────────────────────────────────────────────────────

    private List<TextSegment> chunkDocument(String text, DocumentMetadata metadata) {
        Metadata meta = documentLevelMetadata(metadata, text);
        List<TextSegment> segments = new java.util.ArrayList<>();
        // Simple paragraph-boundary split: split on blank lines, then honour CHUNK_SIZE_TOKENS.
        String[] paragraphs = text.split("\\n{2,}");
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            int projected = io.orphos.eip.docingest.ingestion.structure.TokenEstimator.estimate(
                current.length() > 0 ? current + "\n\n" + trimmed : trimmed);
            if (current.length() > 0 && projected > CHUNK_SIZE_TOKENS) {
                segments.add(TextSegment.from(current.toString().trim(), meta));
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(trimmed);
        }
        if (current.length() > 0) segments.add(TextSegment.from(current.toString().trim(), meta));
        return segments;
    }

    private Metadata documentLevelMetadata(DocumentMetadata m, String text) {
        Metadata meta = new Metadata();
        meta.put("document_id",     m.getDocumentId());
        meta.put("tenant_id",       safe(m.getTenantId()));
        meta.put("document_type",   safe(m.getDocumentType()));
        meta.put("reference_number",safe(m.getReferenceNumber()));
        meta.put("title",           safe(m.getTitle()));
        meta.put("file_name",       safe(m.getFileName()));
        meta.put("uploaded_by",     safe(m.getUploadedBy()));
        meta.put("language",        safe(m.getLanguage()));
        meta.put("category",        safe(m.getCategory()));
        meta.put("department",      safe(m.getDepartment()));
        meta.put("folder",          safe(m.getFolder()));
        meta.put("effective_date",  m.getEffectiveDate() != null ? m.getEffectiveDate().toString() : "");
        meta.put("input_type",      m.getInputType());       // DOCUMENT | IMAGE | HANDWRITING | AUDIO
        meta.put("source_channel",  m.getSourceChannel());   // WEB | MOBILE | CONNECTOR
        meta.put("owner_sub",       safe(m.getOwnerSub()));  // "" = shared tenant corpus
        return meta;
    }

    // ── Stages 3+4: Embed + Store ────────────────────────────────────────────

    private int embedAndStore(List<TextSegment> chunks, DocumentMetadata metadata) {
        // Guard against oversized atomic chunks (e.g. a whole table) that would
        // exceed the embedding runtime's per-input batch limit and reject the
        // whole document. Split them into embed-safe pieces, losing no content.
        chunks = capSegmentSizes(chunks, metadata);

        int totalStored = 0;
        for (int i = 0; i < chunks.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, chunks.size());
            List<TextSegment> batch = chunks.subList(i, end);

            List<TextSegment> tagged = new ArrayList<>();
            for (int j = 0; j < batch.size(); j++) {
                TextSegment chunk = batch.get(j);
                Metadata chunkMeta = chunk.metadata().copy();
                chunkMeta.put("chunk_index", String.valueOf(i + j));
                String chunkPage = chunkMeta.getString("page_number");
                if (chunkPage == null || chunkPage.isBlank() || chunkPage.equals("0")) {
                    chunkMeta.put("page_number", String.valueOf(
                        approximatePage(i + j, chunks.size(), metadata.getPageNumber())));
                }
                tagged.add(TextSegment.from(chunk.text(), chunkMeta));
            }

            // Embed the prefixed text, but store the ORIGINAL segments so search
            // results never leak the "search_document: " instruction into the UI.
            List<TextSegment> toEmbed = new ArrayList<>(tagged.size());
            for (TextSegment s : tagged) {
                toEmbed.add(TextSegment.from(documentPrefix + s.text(), s.metadata()));
            }
            // Retry only the embed call (a stateless read against Ollama) — 2-3 short-backoff
            // attempts so one Ollama blip doesn't fail the whole document. Deliberately NOT
            // wrapped around addAll(): QdrantEmbeddingStore.addAll(embeddings, segments) assigns
            // each point a fresh random id, so retrying it after an ambiguous failure could insert
            // duplicate points rather than safely reapplying the same write.
            Response<List<Embedding>> resp = embedRetry.executeSupplier(() -> embeddingModel.embedAll(toEmbed));
            embeddingStore.addAll(resp.content(), tagged);
            totalStored += batch.size();

            log.debug("Embedded batch {}/{} ({} chunks) for document {}",
                (i / EMBEDDING_BATCH_SIZE) + 1,
                (int) Math.ceil((double) chunks.size() / EMBEDDING_BATCH_SIZE),
                batch.size(), metadata.getDocumentId());
        }
        return totalStored;
    }

    /**
     * Splits any chunk whose text exceeds {@link #maxEmbedChars} into consecutive
     * embed-safe pieces, preferring a natural (newline/space) boundary in the back
     * half of each window and falling back to a hard cut. Normal chunks pass
     * through untouched. This keeps every embedding input under the model's
     * physical batch limit so one oversized table/clause can no longer fail the
     * whole document, while preserving all content (no truncation).
     */
    private List<TextSegment> capSegmentSizes(List<TextSegment> chunks, DocumentMetadata metadata) {
        int max = maxEmbedChars > 0 ? maxEmbedChars : 1600;
        boolean anyOversized = false;
        for (TextSegment s : chunks) {
            if (s.text() != null && s.text().length() > max) { anyOversized = true; break; }
        }
        if (!anyOversized) return chunks;   // common case — no copy, no work

        List<TextSegment> out = new ArrayList<>(chunks.size() + 4);
        for (TextSegment seg : chunks) {
            String text = seg.text();
            if (text == null || text.length() <= max) { out.add(seg); continue; }

            int start = 0, part = 0;
            while (start < text.length()) {
                int end = Math.min(start + max, text.length());
                if (end < text.length()) {
                    int brk = Math.max(text.lastIndexOf('\n', end), text.lastIndexOf(' ', end));
                    if (brk > start + max / 2) end = brk;   // natural boundary in the back half
                }
                Metadata m = seg.metadata().copy();
                m.put("split_part", String.valueOf(part));
                out.add(TextSegment.from(text.substring(start, end), m));
                start = end;
                while (start < text.length()
                        && (text.charAt(start) == '\n' || text.charAt(start) == ' ')) start++;
                part++;
            }
            log.info("Split oversized chunk ({} chars) into {} embed-safe pieces (max {}) for document {}",
                    text.length(), part, max, metadata.getDocumentId());
        }
        return out;
    }

    // ── URL helpers ──────────────────────────────────────────────────────────

    private Path downloadToTempFile(URI url) throws IOException, InterruptedException {
        // Follow redirects manually so the SSRF guard re-validates EVERY hop.
        // Auto-following (Redirect.NORMAL) would let an allowlisted public URL
        // 302 the server onto 169.254.169.254 or an internal service unchecked.
        URI current = url;
        for (int hop = 0; ; hop++) {
            urlEgressGuard.validate(current);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(current).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "OrphosDocIngest/1.0")
                .GET().build();

            Path tempFile = Files.createTempFile("doc-ingest-url-", "-" + sanitize(deriveName(current)));
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            int status = response.statusCode();

            if (status >= 300 && status < 400) {
                Files.deleteIfExists(tempFile);   // redirect body is not the document
                if (hop >= MAX_REDIRECTS) {
                    throw new IngestionException("Too many redirects (>" + MAX_REDIRECTS + ") fetching " + url);
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new IngestionException("Redirect with no Location header fetching " + current);
                }
                current = current.resolve(location);   // resolve relative + re-validate next loop
                continue;
            }
            if (status >= 400) {
                Files.deleteIfExists(tempFile);
                throw new IngestionException("URL fetch failed HTTP " + status + ": " + current);
            }
            return tempFile;   // 2xx — host was validated at the top of this iteration
        }
    }

    private static String deriveName(URI url) {
        String path = url.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) return "page.html";
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
        return name.isBlank() ? "page.html" : name;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ── Format helpers ───────────────────────────────────────────────────────

    private boolean isSupportedFormat(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf")
            || name.endsWith(".docx") || name.endsWith(".doc")
            || name.endsWith(".pptx") || name.endsWith(".ppt")
            || name.endsWith(".xlsx") || name.endsWith(".xls")
            || name.endsWith(".csv")
            || name.endsWith(".txt")
            || name.endsWith(".html") || name.endsWith(".htm")
            || name.endsWith(".png")
            || name.endsWith(".jpg")  || name.endsWith(".jpeg")
            || name.endsWith(".tif")  || name.endsWith(".tiff")
            || name.endsWith(".gif")  || name.endsWith(".bmp")  || name.endsWith(".webp") || name.endsWith(".heic")
            // Audio (transcribed via the Whisper sidecar) — covers mobile voice captures.
            || name.endsWith(".mp3")  || name.endsWith(".wav")  || name.endsWith(".m4a")  || name.endsWith(".mp4")
            || name.endsWith(".aac")  || name.endsWith(".ogg")  || name.endsWith(".oga")  || name.endsWith(".opus")
            || name.endsWith(".flac") || name.endsWith(".webm") || name.endsWith(".amr")  || name.endsWith(".3gp");
    }

    private static String probeContentType(Path file) {
        try { return java.nio.file.Files.probeContentType(file); }
        catch (IOException e) { return "application/octet-stream"; }
    }

    private static String safe(String v) { return v != null ? v : ""; }

    private static int approximatePage(int chunkIndex, int totalChunks, int totalPages) {
        if (totalPages <= 0 || totalChunks <= 0) return 1;
        return (int) Math.ceil(((double) chunkIndex / totalChunks) * totalPages) + 1;
    }
}
