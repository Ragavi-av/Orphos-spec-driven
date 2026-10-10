package io.orphos.eip.decision.service;

import io.orphos.eip.common.events.EipEventPublisher;
import io.orphos.eip.decision.util.DecisionLogProperties;
import io.orphos.eip.decision.repository.DecisionRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * RAG-system integration sweep (Req 2 + Req 3): pulls new items out of
 * Communication_RAG (mail-insight's {@code MailInsight} rows) and
 * Document_RAG (doc-ingest's {@code DocIngestRecord} rows) since each
 * source's watermark, runs the Decision_Detection_Engine over them, and
 * persists flagged decisions with confidence scores and provenance
 * ({@code detectionSource}/{@code sourceRef}).
 *
 * <p>Data_Ingestion_RAG needs no polling: ETL jobs load rows directly into
 * the shared {@code Decision} type; the migration pass below picks those up
 * (they arrive without {@code schemaVersion}), normalizes them and embeds
 * them — so all three RAG sources converge on one enhanced schema (Req 2.1,
 * Req 9.2). Runs every 5 minutes per tenant, satisfying the sync-lag bound
 * (Req 2.5).</p>
 */
@Service
public class RagSyncService {

    private static final Logger log = LogManager.getLogger(RagSyncService.class);

    static final String SRC_COMMUNICATION = "COMMUNICATION_RAG";
    static final String SRC_DOCUMENT = "DOCUMENT_RAG";

    private final DecisionRepository repo;
    private final DecisionDetectionService detection;
    private final DecisionService decisions;
    private final EipEventPublisher events;
    private final DecisionLogProperties props;

    public RagSyncService(DecisionRepository repo, DecisionDetectionService detection,
                          DecisionService decisions, EipEventPublisher events, DecisionLogProperties props) {
        this.repo = repo;
        this.detection = detection;
        this.decisions = decisions;
        this.events = events;
        this.props = props;
    }

    /** One full sweep for the current tenant. Returns counts for observability / the admin endpoint. */
    public Map<String, Object> syncTenant(String tenantId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("communicationScanned", 0);
        stats.put("documentsScanned", 0);
        stats.put("decisionsDetected", 0);
        stats.put("rowsMigrated", 0);
        try {
            int detected = 0;
            detected += syncCommunication(tenantId, stats);
            detected += syncDocuments(tenantId, stats);
            stats.put("decisionsDetected", detected);
            // Data_Ingestion_RAG + legacy rows: incremental normalize/embed pass.
            stats.put("rowsMigrated", decisions.migrateBatch(tenantId, props.getSync().getBatchSize()));
        } catch (Exception e) {
            log.warn("RAG sync failed for tenant {}: {}", tenantId, e.getMessage());
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    private int syncCommunication(String tenantId, Map<String, Object> stats) {
        String since = watermarkOrDefault(tenantId, SRC_COMMUNICATION);
        var items = safeFetch(() -> repo.newCommunicationItems(tenantId, since, props.getSync().getBatchSize()));
        stats.put("communicationScanned", items.size());
        int detected = 0;
        String maxSeen = since;
        for (Map<String, Object> item : items) {
            String createdAt = str(item.get("createdAt"));
            if (createdAt.compareTo(maxSeen) > 0) maxSeen = createdAt;
            String sourceRef = "mail:" + str(item.get("id"));
            if (repo.existsBySourceRef(tenantId, sourceRef)) continue;
            Optional<DecisionDetectionService.DetectedDecision> hit = detection.detect(tenantId,
                str(item.get("title")), str(item.get("summary")), str(item.get("senderDisplay")));
            if (hit.isPresent()) {
                persistDetected(tenantId, hit.get(), SRC_COMMUNICATION, sourceRef,
                    str(item.get("receivedAt")));
                detected++;
            }
        }
        if (!maxSeen.equals(since)) repo.saveWatermark(tenantId, SRC_COMMUNICATION, maxSeen);
        return detected;
    }

    private int syncDocuments(String tenantId, Map<String, Object> stats) {
        String since = watermarkOrDefault(tenantId, SRC_DOCUMENT);
        var items = safeFetch(() -> repo.newDocumentItems(tenantId, since, props.getSync().getBatchSize()));
        stats.put("documentsScanned", items.size());
        int detected = 0;
        String maxSeen = since;
        for (Map<String, Object> item : items) {
            String completedAt = str(item.get("completedAt"));
            if (completedAt.compareTo(maxSeen) > 0) maxSeen = completedAt;
            String sourceRef = "doc:" + str(item.get("documentId"));
            if (repo.existsBySourceRef(tenantId, sourceRef)) continue;
            String title = str(item.get("title")).isBlank() ? str(item.get("fileName")) : str(item.get("title"));
            String context = ("Document type: " + str(item.get("documentType"))
                + ". Category: " + str(item.get("category"))
                + ". Department: " + str(item.get("department"))).strip();
            Optional<DecisionDetectionService.DetectedDecision> hit =
                detection.detect(tenantId, title, context, "");
            if (hit.isPresent()) {
                persistDetected(tenantId, hit.get(), SRC_DOCUMENT, sourceRef, completedAt);
                detected++;
            }
        }
        if (!maxSeen.equals(since)) repo.saveWatermark(tenantId, SRC_DOCUMENT, maxSeen);
        return detected;
    }

    private void persistDetected(String tenantId, DecisionDetectionService.DetectedDecision d,
                                 String detectionSource, String sourceRef, String occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", d.title());
        payload.put("description", d.description());
        payload.put("rationale", d.rationale());
        payload.put("status", "DETECTED");   // awaits human confirmation in the UI
        payload.put("domain", d.domain());
        payload.put("decisionArea", d.decisionArea());
        payload.put("priorityLevel", d.priorityLevel());
        payload.put("priorityScore", d.priorityScore());
        payload.put("classificationType", d.classificationType());
        payload.put("strategicImportance", d.strategicImportance());
        payload.put("involvedPeopleJson", d.involvedPeopleJson());
        payload.put("affectedAreasJson", d.affectedAreasJson());
        payload.put("confidenceScore", d.confidence());
        payload.put("detectionSource", detectionSource);
        payload.put("sourceRef", sourceRef);
        payload.put("decidedBy", "AI-detected");
        if (occurredAt != null && !occurredAt.isBlank()) payload.put("decidedAt", occurredAt);

        Map<String, Object> created = decisions.create(tenantId, payload, "decision-detection-engine");
        events.publish(DecisionService.EVT_DETECTED, EipEventPublisher.SEVERITY_INFO,
            "Decision detected: " + d.title(),
            "Flagged from " + detectionSource + " with " + Math.round(d.confidence() * 100)
                + "% confidence — pending review.",
            tenantId, Map.of("decisionId", str(created.get("id")), "confidence", d.confidence()));
    }

    private String watermarkOrDefault(String tenantId, String source) {
        String wm = repo.watermark(tenantId, source);
        // First sweep: look back 7 days rather than ingesting all history.
        return wm != null && !wm.isBlank() ? wm
            : Instant.now().minus(7, ChronoUnit.DAYS).toString();
    }

    private java.util.List<Map<String, Object>> safeFetch(
            java.util.function.Supplier<java.util.List<Map<String, Object>>> query) {
        try {
            return query.get();
        } catch (Exception e) {
            // Source type may not exist yet in this deployment (service never booted) — skip quietly.
            log.debug("RAG source unavailable this sweep: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
