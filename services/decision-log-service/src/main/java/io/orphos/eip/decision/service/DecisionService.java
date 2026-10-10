package io.orphos.eip.decision.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.events.EipEventPublisher;
import io.orphos.eip.decision.repository.DecisionRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core Decision_Log_System orchestration: create/update with enrichment
 * (embedding + correlation), the real-time tracker (status transitions,
 * milestones, escalation — Req 10), outcome recording for continuous learning
 * (Req 4.5), and the legacy-row migration path (Req 9.2).
 *
 * <p>Stakeholder notification (Req 10.2) rides the platform event bus
 * ({@link EipEventPublisher} → admin-console rules/templates), the same
 * channel doc-ingest uses for DOC_INGEST_FAILED — events fire synchronously
 * on transition, well inside the 2-minute requirement.</p>
 */
@Service
public class DecisionService {

    private static final Logger log = LogManager.getLogger(DecisionService.class);

    public static final String EVT_STATUS_CHANGED = "DECISION_STATUS_CHANGED";
    public static final String EVT_DETECTED = "DECISION_DETECTED";
    public static final String EVT_ESCALATION = "DECISION_ESCALATION";

    public static final int SCHEMA_VERSION = 2;

    /** JSON-array columns and their structured API aliases. */
    private static final Map<String, String> JSON_ALIASES = Map.of(
        "involvedPeople", "involvedPeopleJson",
        "affectedAreas", "affectedAreasJson",
        "tags", "tagsJson",
        "milestones", "milestonesJson");

    private final DecisionRepository repo;
    private final DecisionEmbeddingService embeddings;
    private final CorrelationService correlations;
    private final EipEventPublisher events;
    private final ObjectMapper mapper = new ObjectMapper();

    public DecisionService(DecisionRepository repo, DecisionEmbeddingService embeddings,
                           CorrelationService correlations, EipEventPublisher events) {
        this.repo = repo;
        this.embeddings = embeddings;
        this.correlations = correlations;
        this.events = events;
    }

    // ------------------------------------------------------------------ create / update

    /**
     * Creates a decision. Accepts the legacy minimal payload (title/status/
     * domain/rationale/decidedBy) unchanged — Req 9.1 backward compatibility —
     * plus every RAG-schema field, with structured lists ({@code involvedPeople},
     * {@code affectedAreas}, {@code tags}, {@code milestones}) serialized to
     * their JSON columns.
     */
    public Map<String, Object> create(String tenantId, Map<String, Object> payload, String actor) {
        Map<String, Object> fields = normalizePayload(payload);
        String now = Instant.now().toString();
        fields.put("id", fields.getOrDefault("id", "dec-" + UUID.randomUUID()));
        fields.put("tenantId", tenantId);
        fields.putIfAbsent("title", "");
        fields.putIfAbsent("status", "PENDING");
        fields.putIfAbsent("domain", "IT");
        fields.putIfAbsent("rationale", "");
        fields.putIfAbsent("decidedBy", actor == null || actor.isBlank() ? "unknown" : actor);
        fields.putIfAbsent("decidedAt", now);
        fields.putIfAbsent("detectionSource", "MANUAL");
        fields.putIfAbsent("implementationStatus", "NOT_STARTED");
        fields.putIfAbsent("priorityLevel", "MEDIUM");
        fields.putIfAbsent("confidenceScore", 1.0d);
        fields.put("schemaVersion", SCHEMA_VERSION);
        fields.put("createdAt", now);
        fields.put("updatedAt", now);

        repo.insert(fields);
        enrich(tenantId, fields, null);
        repo.insertStatusEvent(tenantId, str(fields.get("id")), "STATUS", "",
            str(fields.get("status")), "Decision recorded", actor, "UI");
        return expand(repo.findById(tenantId, str(fields.get("id"))));
    }

    public Map<String, Object> update(String tenantId, String id, Map<String, Object> payload, String actor) {
        Map<String, Object> existing = repo.findById(tenantId, id);
        if (existing == null) return null;
        Map<String, Object> fields = normalizePayload(payload);
        fields.remove("id");
        fields.remove("tenantId");
        fields.remove("createdAt");
        fields.put("updatedAt", Instant.now().toString());
        repo.update(tenantId, id, fields);

        Map<String, Object> updated = repo.findById(tenantId, id);
        enrich(tenantId, updated, str(existing.get("embeddingId")));
        String oldStatus = str(existing.get("status"));
        String newStatus = str(updated.get("status"));
        if (!oldStatus.equals(newStatus)) {
            recordTransition(tenantId, id, "STATUS", oldStatus, newStatus, "", actor, "UI",
                str(updated.get("title")));
        }
        return expand(repo.findById(tenantId, id));
    }

    /** Re-embeds and re-correlates a decision after any content change. Best-effort. */
    private void enrich(String tenantId, Map<String, Object> row, String previousEmbeddingId) {
        try {
            String embeddingId = embeddings.index(row, previousEmbeddingId);
            if (embeddingId != null) {
                repo.update(tenantId, str(row.get("id")), Map.of("embeddingId", embeddingId));
            }
            correlations.refresh(tenantId, row);
        } catch (Exception e) {
            log.warn("Enrichment failed for decision {}: {}", row.get("id"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ real-time tracker (Req 10)

    /**
     * Status / implementation-status transition with audit event and
     * stakeholder notification. {@code source} distinguishes UI, SYSTEM and
     * EXTERNAL (project-management sync, Req 10.4) callers.
     */
    public Map<String, Object> changeStatus(String tenantId, String id, String status,
                                            String implementationStatus, String note,
                                            String actor, String source) {
        Map<String, Object> existing = repo.findById(tenantId, id);
        if (existing == null) return null;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("updatedAt", Instant.now().toString());
        if (status != null && !status.isBlank()) fields.put("status", status);
        if (implementationStatus != null && !implementationStatus.isBlank()) {
            fields.put("implementationStatus", implementationStatus);
            if ("COMPLETED".equals(implementationStatus)) fields.put("escalated", false);
        }
        repo.update(tenantId, id, fields);

        String title = str(existing.get("title"));
        if (status != null && !status.isBlank() && !status.equals(str(existing.get("status")))) {
            recordTransition(tenantId, id, "STATUS", str(existing.get("status")), status, note, actor, source, title);
        }
        if (implementationStatus != null && !implementationStatus.isBlank()
                && !implementationStatus.equals(str(existing.get("implementationStatus")))) {
            recordTransition(tenantId, id, "IMPLEMENTATION", str(existing.get("implementationStatus")),
                implementationStatus, note, actor, source, title);
        }
        return expand(repo.findById(tenantId, id));
    }

    private void recordTransition(String tenantId, String id, String kind, String from, String to,
                                  String note, String actor, String source, String title) {
        repo.insertStatusEvent(tenantId, id, kind, from, to, note, actor, source);
        events.publish(EVT_STATUS_CHANGED, EipEventPublisher.SEVERITY_INFO,
            "Decision " + kind.toLowerCase() + " changed: " + title,
            "\"" + title + "\" moved " + (from.isBlank() ? "(new)" : from) + " → " + to
                + (note == null || note.isBlank() ? "" : " — " + note),
            tenantId, Map.of("decisionId", id, "kind", kind, "from", from, "to", to));
    }

    /** Milestone list replacement with progress audit (Req 10.3). */
    public Map<String, Object> updateMilestones(String tenantId, String id, List<Map<String, Object>> milestones,
                                                String actor) {
        Map<String, Object> existing = repo.findById(tenantId, id);
        if (existing == null) return null;
        repo.update(tenantId, id, Map.of(
            "milestonesJson", toJson(milestones == null ? List.of() : milestones),
            "updatedAt", Instant.now().toString()));
        repo.insertStatusEvent(tenantId, id, "MILESTONE", "", "updated",
            (milestones == null ? 0 : milestones.size()) + " milestone(s)", actor, "UI");
        return expand(repo.findById(tenantId, id));
    }

    /** Marks a decision escalated and notifies (called by the escalation sweep, Req 10.5). */
    public void escalate(String tenantId, Map<String, Object> decision, String reason) {
        String id = str(decision.get("id"));
        repo.update(tenantId, id, Map.of(
            "escalated", true,
            "escalatedAt", Instant.now().toString(),
            "updatedAt", Instant.now().toString()));
        repo.insertStatusEvent(tenantId, id, "ESCALATION", "", "ESCALATED", reason, "system", "SYSTEM");
        events.publish(EVT_ESCALATION, EipEventPublisher.SEVERITY_WARNING,
            "Decision at risk: " + str(decision.get("title")),
            reason, tenantId, Map.of("decisionId", id, "dueDate", str(decision.get("dueDate"))));
    }

    // ------------------------------------------------------------------ outcomes (Req 4.5)

    public Map<String, Object> recordOutcome(String tenantId, String id, String outcome, String actor) {
        Map<String, Object> existing = repo.findById(tenantId, id);
        if (existing == null) return null;
        String now = Instant.now().toString();
        repo.update(tenantId, id, Map.of(
            "actualOutcome", outcome == null ? "" : outcome,
            "outcomeRecordedAt", now,
            "updatedAt", now));
        repo.insertStatusEvent(tenantId, id, "OUTCOME", "", "recorded", outcome, actor, "UI");
        // Outcome text becomes part of the embedded content → future impact
        // predictions compare against real results (continuous learning).
        Map<String, Object> updated = repo.findById(tenantId, id);
        enrich(tenantId, updated, str(existing.get("embeddingId")));
        return expand(repo.findById(tenantId, id));
    }

    // ------------------------------------------------------------------ migration (Req 9.2)

    /**
     * Upgrades legacy rows (no schemaVersion) and rows that were never
     * embedded: fills RAG-schema defaults, indexes the vector, correlates.
     * Runs incrementally from the sync sweep and in bulk from the admin
     * endpoint. Returns the number of rows upgraded.
     */
    public int migrateBatch(String tenantId, int batchSize) {
        List<Map<String, Object>> rows = repo.rowsNeedingUpgrade(tenantId, batchSize);
        for (Map<String, Object> row : rows) {
            try {
                Map<String, Object> defaults = new LinkedHashMap<>();
                if (row.get("schemaVersion") == null) {
                    defaults.put("schemaVersion", SCHEMA_VERSION);
                    putIfMissing(defaults, row, "detectionSource", "MANUAL");
                    putIfMissing(defaults, row, "implementationStatus",
                        "APPROVED".equals(str(row.get("status"))) ? "IN_PROGRESS" : "NOT_STARTED");
                    putIfMissing(defaults, row, "priorityLevel", "MEDIUM");
                    putIfMissing(defaults, row, "confidenceScore", 1.0d);
                    putIfMissing(defaults, row, "createdAt", str(row.get("decidedAt")));
                }
                defaults.put("updatedAt", Instant.now().toString());
                repo.update(tenantId, str(row.get("id")), defaults);
                Map<String, Object> merged = new LinkedHashMap<>(row);
                merged.putAll(defaults);
                enrich(tenantId, merged, str(row.get("embeddingId")));
            } catch (Exception e) {
                log.warn("Migration failed for decision {}: {}", row.get("id"), e.getMessage());
            }
        }
        return rows.size();
    }

    // ------------------------------------------------------------------ reads

    public Map<String, Object> get(String tenantId, String id) {
        return expand(repo.findById(tenantId, id));
    }

    public List<Map<String, Object>> timeline(String tenantId, String id) {
        return repo.statusEvents(tenantId, id);
    }

    /**
     * Parses the {@code *Json} columns into structured fields for API
     * responses and adds the legacy {@code timestamp} alias the original UI
     * read (Req 9.1/9.3 — old consumers keep working).
     */
    public Map<String, Object> expand(Map<String, Object> row) {
        if (row == null) return null;
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (Map.Entry<String, String> alias : JSON_ALIASES.entrySet()) {
            out.put(alias.getKey(), parseArray(str(row.get(alias.getValue()))));
        }
        Object predicted = row.get("predictedImpactJson");
        out.put("predictedImpact", parseObject(str(predicted)));
        out.put("timestamp", row.get("decidedAt"));
        return out;
    }

    private List<Map<String, Object>> parseArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            // tags may be a plain string array
            try {
                List<String> strings = mapper.readValue(json, new TypeReference<List<String>>() {});
                return strings.stream().map(s -> Map.<String, Object>of("name", s)).toList();
            } catch (Exception ignored) {
                return List.of();
            }
        }
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Serializes structured list fields to their JSON columns and drops unknown keys. */
    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (payload == null) return fields;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            String key = e.getKey();
            if (JSON_ALIASES.containsKey(key)) {
                fields.put(JSON_ALIASES.get(key), toJson(e.getValue()));
            } else if (DecisionRepository.DECISION_FIELDS.contains(key)) {
                fields.put(key, e.getValue());
            }
        }
        return fields;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static void putIfMissing(Map<String, Object> defaults, Map<String, Object> row,
                                     String field, Object value) {
        Object existing = row.get(field);
        if (existing == null || (existing instanceof String s && s.isBlank())) {
            defaults.put(field, value);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
