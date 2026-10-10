package io.orphos.eip.decision.repository;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All ArcadeDB access for the decision-log types. Rows travel as
 * {@code Map<String,Object>} (the repo-wide idiom for ArcadeDB-backed
 * services); JSON-typed columns ({@code *Json}) hold serialized lists.
 *
 * <p>Every query is tenant-scoped and parameter-bound. Field names in
 * dynamically assembled SET/WHERE clauses are validated against the
 * {@link #DECISION_FIELDS} whitelist — values are always {@code :param}
 * bound, identifiers never come from user input.</p>
 */
@Repository
public class DecisionRepository {

    /** Every persistable Decision column (legacy + RAG schema). */
    public static final Set<String> DECISION_FIELDS = Set.of(
        "id", "tenantId", "title", "status", "domain", "rationale", "decidedBy", "decidedAt",
        "linkedIncident", "linkedBudget",
        "description", "decisionArea", "involvedPeopleJson", "affectedAreasJson",
        "priorityLevel", "priorityScore", "tagsJson",
        "detectionSource", "sourceRef", "confidenceScore", "classificationType", "strategicImportance",
        "impactSummary", "predictedImpactJson", "actualOutcome", "outcomeRecordedAt",
        "implementationStatus", "dueDate", "milestonesJson", "escalated", "escalatedAt",
        "embeddingId", "schemaVersion", "createdAt", "updatedAt");

    private static final String DECISION_SELECT =
        "SELECT " + String.join(", ", DECISION_FIELDS) + " FROM Decision ";

    private final ArcadeDbTemplate arcade;

    public DecisionRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    // ------------------------------------------------------------------ Decision CRUD

    public Map<String, Object> insert(Map<String, Object> fields) {
        Map<String, Object> params = whitelisted(fields);
        StringBuilder sql = new StringBuilder("INSERT INTO Decision SET ");
        appendAssignments(sql, params);
        List<Map<String, Object>> res = arcade.command(sql.toString(), params);
        return res.isEmpty() ? params : res.get(0);
    }

    public void update(String tenantId, String id, Map<String, Object> fields) {
        Map<String, Object> params = whitelisted(fields);
        params.remove("id");
        params.remove("tenantId");
        if (params.isEmpty()) return;
        StringBuilder sql = new StringBuilder("UPDATE Decision SET ");
        appendAssignments(sql, params);
        sql.append(" WHERE tenantId = :__tenantId AND id = :__id");
        params.put("__tenantId", tenantId);
        params.put("__id", id);
        arcade.commandNoResult(sql.toString(), params);
    }

    public Map<String, Object> findById(String tenantId, String id) {
        List<Map<String, Object>> rows = arcade.query(
            DECISION_SELECT + "WHERE tenantId = :tenantId AND id = :id LIMIT 1",
            Map.of("tenantId", tenantId, "id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findByIds(String tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return arcade.query(
            DECISION_SELECT + "WHERE tenantId = :tenantId AND id IN :ids",
            Map.of("tenantId", tenantId, "ids", ids));
    }

    public boolean existsBySourceRef(String tenantId, String sourceRef) {
        List<Map<String, Object>> rows = arcade.query(
            "SELECT id FROM Decision WHERE tenantId = :tenantId AND sourceRef = :sourceRef LIMIT 1",
            Map.of("tenantId", tenantId, "sourceRef", sourceRef));
        return !rows.isEmpty();
    }

    /**
     * Filtered list backing both the log view and keyword search. All filters
     * optional; {@code q} matches title/description/rationale/decidedBy
     * case-insensitively.
     */
    public List<Map<String, Object>> list(String tenantId, DecisionFilter f, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        StringBuilder where = new StringBuilder("WHERE tenantId = :tenantId");
        applyFilter(where, params, f);
        String order = " ORDER BY decidedAt DESC";
        String page = " SKIP " + Math.max(0, offset) + " LIMIT " + Math.max(1, Math.min(limit, 500));
        return arcade.query(DECISION_SELECT + where + order + page, params);
    }

    /** Bounded projection for in-memory analytics aggregation (Req 8). */
    public List<Map<String, Object>> analyticsRows(String tenantId, int limit) {
        return arcade.query(
            "SELECT id, status, domain, decisionArea, priorityLevel, detectionSource, confidenceScore, " +
            "implementationStatus, decidedAt, dueDate, escalated, actualOutcome, outcomeRecordedAt, createdAt " +
            "FROM Decision WHERE tenantId = :tenantId ORDER BY decidedAt DESC LIMIT " + Math.min(limit, 10_000),
            Map.of("tenantId", tenantId));
    }

    /** Legacy rows (schemaVersion missing) or rows never embedded — the incremental migration feed (Req 9.2). */
    public List<Map<String, Object>> rowsNeedingUpgrade(String tenantId, int limit) {
        return arcade.query(
            DECISION_SELECT + "WHERE tenantId = :tenantId AND (schemaVersion IS NULL OR embeddingId IS NULL) " +
            "LIMIT " + Math.max(1, Math.min(limit, 500)),
            Map.of("tenantId", tenantId));
    }

    /** Overdue, not-completed, not-yet-escalated decisions (Req 10.5). */
    public List<Map<String, Object>> overdueDecisions(String tenantId, String nowIso) {
        return arcade.query(
            DECISION_SELECT + "WHERE tenantId = :tenantId AND dueDate IS NOT NULL AND dueDate <> '' " +
            "AND dueDate < :now AND (escalated IS NULL OR escalated = false) " +
            "AND (implementationStatus IS NULL OR implementationStatus <> 'COMPLETED') " +
            "AND status <> 'REJECTED' LIMIT 100",
            Map.of("tenantId", tenantId, "now", nowIso));
    }

    private static void applyFilter(StringBuilder where, Map<String, Object> params, DecisionFilter f) {
        if (f == null) return;
        if (notBlank(f.q())) {
            params.put("q", "%" + f.q().toLowerCase(Locale.ROOT) + "%");
            where.append(" AND (title.toLowerCase() LIKE :q OR description.toLowerCase() LIKE :q")
                 .append(" OR rationale.toLowerCase() LIKE :q OR decidedBy.toLowerCase() LIKE :q")
                 .append(" OR involvedPeopleJson.toLowerCase() LIKE :q OR affectedAreasJson.toLowerCase() LIKE :q)");
        }
        addIn(where, params, "status", f.statuses());
        addIn(where, params, "domain", f.domains());
        addIn(where, params, "decisionArea", f.areas());
        addIn(where, params, "priorityLevel", f.priorities());
        addIn(where, params, "detectionSource", f.detectionSources());
        if (notBlank(f.dateFrom())) {
            params.put("dateFrom", f.dateFrom());
            where.append(" AND decidedAt >= :dateFrom");
        }
        if (notBlank(f.dateTo())) {
            params.put("dateTo", f.dateTo());
            where.append(" AND decidedAt <= :dateTo");
        }
        if (notBlank(f.person())) {
            params.put("person", "%" + f.person().toLowerCase(Locale.ROOT) + "%");
            where.append(" AND (decidedBy.toLowerCase() LIKE :person OR involvedPeopleJson.toLowerCase() LIKE :person)");
        }
        if (notBlank(f.impactArea())) {
            params.put("impactArea", "%" + f.impactArea().toLowerCase(Locale.ROOT) + "%");
            where.append(" AND affectedAreasJson.toLowerCase() LIKE :impactArea");
        }
    }

    private static void addIn(StringBuilder where, Map<String, Object> params, String field, List<String> values) {
        if (values == null || values.isEmpty()) return;
        String key = "in_" + field;
        params.put(key, values);
        where.append(" AND ").append(field).append(" IN :").append(key);
    }

    /** Faceted-filter carrier (Req 6.2). All members optional. */
    public record DecisionFilter(String q, List<String> statuses, List<String> domains, List<String> areas,
                                 List<String> priorities, List<String> detectionSources,
                                 String dateFrom, String dateTo, String person, String impactArea) {
        public static DecisionFilter none() {
            return new DecisionFilter(null, null, null, null, null, null, null, null, null, null);
        }
    }

    // ------------------------------------------------------------------ Status events (Req 10)

    public void insertStatusEvent(String tenantId, String decisionId, String kind, String fromValue,
                                  String toValue, String note, String changedBy, String source) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", "dse-" + UUID.randomUUID());
        params.put("tenantId", tenantId);
        params.put("decisionId", decisionId);
        params.put("kind", kind);
        params.put("fromValue", nullSafe(fromValue));
        params.put("toValue", nullSafe(toValue));
        params.put("note", nullSafe(note));
        params.put("changedBy", nullSafe(changedBy));
        params.put("source", nullSafe(source));
        params.put("occurredAt", Instant.now().toString());
        arcade.commandNoResult(
            "INSERT INTO DecisionStatusEvent SET id = :id, tenantId = :tenantId, decisionId = :decisionId, " +
            "kind = :kind, fromValue = :fromValue, toValue = :toValue, note = :note, changedBy = :changedBy, " +
            "source = :source, occurredAt = :occurredAt", params);
    }

    public List<Map<String, Object>> statusEvents(String tenantId, String decisionId) {
        return arcade.query(
            "SELECT id, kind, fromValue, toValue, note, changedBy, source, occurredAt FROM DecisionStatusEvent " +
            "WHERE tenantId = :tenantId AND decisionId = :decisionId ORDER BY occurredAt ASC LIMIT 500",
            Map.of("tenantId", tenantId, "decisionId", decisionId));
    }

    // ------------------------------------------------------------------ Correlations (Req 11)

    public void replaceAutoCorrelations(String tenantId, String decisionId, List<Map<String, Object>> correlations) {
        arcade.commandNoResult(
            "DELETE FROM DecisionCorrelation WHERE tenantId = :tenantId AND decisionId = :decisionId " +
            "AND source IN ['AUTO','RULE','SEMANTIC']",
            Map.of("tenantId", tenantId, "decisionId", decisionId));
        for (Map<String, Object> c : correlations) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("id", "dcor-" + UUID.randomUUID());
            params.put("tenantId", tenantId);
            params.put("decisionId", decisionId);
            params.put("targetType", nullSafe((String) c.get("targetType")));
            params.put("targetId", nullSafe((String) c.get("targetId")));
            params.put("targetTitle", nullSafe((String) c.get("targetTitle")));
            params.put("relation", nullSafe((String) c.get("relation")));
            params.put("confidence", c.getOrDefault("confidence", 0.0d));
            params.put("source", nullSafe((String) c.get("source")));
            params.put("ruleId", nullSafe((String) c.get("ruleId")));
            params.put("createdAt", Instant.now().toString());
            arcade.commandNoResult(
                "INSERT INTO DecisionCorrelation SET id = :id, tenantId = :tenantId, decisionId = :decisionId, " +
                "targetType = :targetType, targetId = :targetId, targetTitle = :targetTitle, relation = :relation, " +
                "confidence = :confidence, source = :source, ruleId = :ruleId, createdAt = :createdAt", params);
        }
    }

    public List<Map<String, Object>> correlations(String tenantId, String decisionId) {
        return arcade.query(
            "SELECT id, targetType, targetId, targetTitle, relation, confidence, source, ruleId, createdAt " +
            "FROM DecisionCorrelation WHERE tenantId = :tenantId AND decisionId = :decisionId " +
            "ORDER BY confidence DESC LIMIT 100",
            Map.of("tenantId", tenantId, "decisionId", decisionId));
    }

    /** Candidate rows from another system's type for correlation scoring. Type name is caller-validated. */
    public List<Map<String, Object>> externalCandidates(String tenantId, String type, int limit) {
        requireIdent(type);
        return arcade.query(
            "SELECT FROM " + type + " WHERE tenantId = :tenantId ORDER BY @rid DESC LIMIT " + Math.min(limit, 200),
            Map.of("tenantId", tenantId));
    }

    // ------------------------------------------------------------------ Correlation rules (Req 11.4)

    public List<Map<String, Object>> correlationRules(String tenantId, boolean enabledOnly) {
        String sql = "SELECT id, name, targetType, keywordPattern, relation, confidence, enabled, createdAt, updatedAt " +
                     "FROM DecisionCorrelationRule WHERE tenantId = :tenantId" +
                     (enabledOnly ? " AND enabled = true" : "") + " ORDER BY name LIMIT 200";
        return arcade.query(sql, Map.of("tenantId", tenantId));
    }

    public Map<String, Object> insertCorrelationRule(String tenantId, Map<String, Object> rule) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", "dcr-" + UUID.randomUUID());
        params.put("tenantId", tenantId);
        params.put("name", nullSafe((String) rule.get("name")));
        params.put("targetType", nullSafe((String) rule.get("targetType")));
        params.put("keywordPattern", nullSafe((String) rule.get("keywordPattern")));
        params.put("relation", rule.getOrDefault("relation", "RELATED"));
        params.put("confidence", rule.getOrDefault("confidence", 0.9d));
        params.put("enabled", rule.getOrDefault("enabled", Boolean.TRUE));
        params.put("createdAt", Instant.now().toString());
        params.put("updatedAt", Instant.now().toString());
        List<Map<String, Object>> res = arcade.command(
            "INSERT INTO DecisionCorrelationRule SET id = :id, tenantId = :tenantId, name = :name, " +
            "targetType = :targetType, keywordPattern = :keywordPattern, relation = :relation, " +
            "confidence = :confidence, enabled = :enabled, createdAt = :createdAt, updatedAt = :updatedAt", params);
        return res.isEmpty() ? params : res.get(0);
    }

    public void deleteCorrelationRule(String tenantId, String ruleId) {
        arcade.commandNoResult(
            "DELETE FROM DecisionCorrelationRule WHERE tenantId = :tenantId AND id = :id",
            Map.of("tenantId", tenantId, "id", ruleId));
    }

    // ------------------------------------------------------------------ Annotations (Req 7)

    public Map<String, Object> annotation(String tenantId, String decisionId, String audienceRole) {
        List<Map<String, Object>> rows = arcade.query(
            "SELECT annotationJson, model, createdAt FROM DecisionAnnotation " +
            "WHERE tenantId = :tenantId AND decisionId = :decisionId AND audienceRole = :audienceRole LIMIT 1",
            Map.of("tenantId", tenantId, "decisionId", decisionId, "audienceRole", audienceRole));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveAnnotation(String tenantId, String decisionId, String audienceRole,
                               String annotationJson, String model) {
        arcade.commandNoResult(
            "DELETE FROM DecisionAnnotation WHERE tenantId = :tenantId AND decisionId = :decisionId " +
            "AND audienceRole = :audienceRole",
            Map.of("tenantId", tenantId, "decisionId", decisionId, "audienceRole", audienceRole));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", "dann-" + UUID.randomUUID());
        params.put("tenantId", tenantId);
        params.put("decisionId", decisionId);
        params.put("audienceRole", audienceRole);
        params.put("annotationJson", annotationJson);
        params.put("model", nullSafe(model));
        params.put("createdAt", Instant.now().toString());
        arcade.commandNoResult(
            "INSERT INTO DecisionAnnotation SET id = :id, tenantId = :tenantId, decisionId = :decisionId, " +
            "audienceRole = :audienceRole, annotationJson = :annotationJson, model = :model, createdAt = :createdAt",
            params);
    }

    // ------------------------------------------------------------------ Decision areas (Req 1.1)

    public List<Map<String, Object>> areas(String tenantId) {
        return arcade.query(
            "SELECT id, name, parentId, description, createdAt FROM DecisionArea " +
            "WHERE tenantId = :tenantId ORDER BY name LIMIT 500",
            Map.of("tenantId", tenantId));
    }

    public Map<String, Object> insertArea(String tenantId, String name, String parentId, String description) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", "darea-" + UUID.randomUUID());
        params.put("tenantId", tenantId);
        params.put("name", nullSafe(name));
        params.put("parentId", nullSafe(parentId));
        params.put("description", nullSafe(description));
        params.put("createdAt", Instant.now().toString());
        List<Map<String, Object>> res = arcade.command(
            "INSERT INTO DecisionArea SET id = :id, tenantId = :tenantId, name = :name, parentId = :parentId, " +
            "description = :description, createdAt = :createdAt", params);
        return res.isEmpty() ? params : res.get(0);
    }

    // ------------------------------------------------------------------ Sync watermarks (Req 2.5)

    public String watermark(String tenantId, String source) {
        List<Map<String, Object>> rows = arcade.query(
            "SELECT lastSyncAt FROM DecisionSyncWatermark WHERE tenantId = :tenantId AND source = :source LIMIT 1",
            Map.of("tenantId", tenantId, "source", source));
        return rows.isEmpty() ? null : (String) rows.get(0).get("lastSyncAt");
    }

    public void saveWatermark(String tenantId, String source, String lastSyncAt) {
        // UPSERT requires the (tenantId, source) UNIQUE index created by the bootstrap.
        arcade.commandNoResult(
            "UPDATE DecisionSyncWatermark SET tenantId = :tenantId, source = :source, lastSyncAt = :lastSyncAt, " +
            "updatedAt = :updatedAt UPSERT WHERE tenantId = :tenantId AND source = :source",
            Map.of("tenantId", tenantId, "source", source,
                   "lastSyncAt", lastSyncAt, "updatedAt", Instant.now().toString()));
    }

    // ------------------------------------------------------------------ Source RAG systems (read-only)

    /** New Communication_RAG items since the watermark (MailInsight rows, Req 2.3). */
    public List<Map<String, Object>> newCommunicationItems(String tenantId, String sinceIso, int limit) {
        return arcade.query(
            "SELECT id, title, summary, senderDisplay, source, sourceType, priority, receivedAt, createdAt " +
            "FROM MailInsight WHERE tenantId = :tenantId AND createdAt > :since " +
            "ORDER BY createdAt ASC LIMIT " + Math.max(1, Math.min(limit, 500)),
            Map.of("tenantId", tenantId, "since", sinceIso));
    }

    /** New Document_RAG items since the watermark (DocIngestRecord rows, Req 2.2). */
    public List<Map<String, Object>> newDocumentItems(String tenantId, String sinceIso, int limit) {
        return arcade.query(
            "SELECT documentId, title, fileName, documentType, category, department, completedAt " +
            "FROM DocIngestRecord WHERE tenantId = :tenantId AND status = 'COMPLETED' AND completedAt > :since " +
            "ORDER BY completedAt ASC LIMIT " + Math.max(1, Math.min(limit, 500)),
            Map.of("tenantId", tenantId, "since", sinceIso));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> whitelisted(Map<String, Object> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (DECISION_FIELDS.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static void appendAssignments(StringBuilder sql, Map<String, Object> params) {
        List<String> assignments = new ArrayList<>(params.size());
        for (String key : params.keySet()) {
            requireIdent(key);
            assignments.add(key + " = :" + key);
        }
        sql.append(String.join(", ", assignments));
    }

    private static void requireIdent(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Illegal identifier: " + name);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
