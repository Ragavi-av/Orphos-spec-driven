package io.orphos.eip.mailinsight.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import io.orphos.eip.common.access.DataDomains;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.arcade.MailArcadeClient;
import io.orphos.eip.mailinsight.model.MailInsightDto;
import io.orphos.eip.mailinsight.model.SourceChannel;
import io.orphos.eip.mailinsight.priority.PriorityEngineClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Surfaces the tenant's live ontology data (incidents, alerts, decisions, SLA
 * breaches, budget overruns) as Tasks for Action and Notifications, so the tabs
 * are populated from the data source as well as from email.
 *
 * <p>These items are read on demand (never persisted by this service) — they
 * already live in ArcadeDB and are owned by their source systems. They carry
 * {@code source = "DATA"} and a synthetic id; the UI renders them read-only.
 * Each query degrades to empty on failure so one missing type never blanks a tab.</p>
 *
 * <p>Priority/criticality for Decision, Incident, and Alert rows come from
 * priority-engine-service's shared EPQ queue when available (the composite
 * severity/urgency/impact/recency/domain-risk score), rather than this class's
 * own severity-only heuristic — the same Incident row otherwise got two
 * independently-computed rankings depending on which tab you looked at. Falls
 * back to the local heuristic when priority-engine-service hasn't scored that
 * row yet (or is unreachable). SLAMetric/CostCentre rows always use the local
 * heuristic — priority-engine-service doesn't score those entity types.</p>
 */
@Component
public class DataSourceReader {

    private static final Logger log = LogManager.getLogger(DataSourceReader.class);

    private final MailArcadeClient arcade;
    private final PriorityEngineClient priorityEngine;

    public DataSourceReader(MailArcadeClient arcade, PriorityEngineClient priorityEngine) {
        this.arcade = arcade;
        this.priorityEngine = priorityEngine;
    }

    // ── Tasks for Action (things needing a decision / action) ─────────────────

    public List<MailInsightDto> tasks() {
        List<MailInsightDto> out = new ArrayList<>();
        Map<String, JsonNode> epq = priorityEngine.queueByKey();

        // Pending decisions — a decision is required.
        for (Map<String, Object> r : query(
                "SELECT id, title, domain, rationale FROM Decision " +
                "WHERE tenantId = :tenantId AND status = 'PENDING' LIMIT 50")) {
            String rawId = str(r, "id");
            out.add(dto(epq, "DECISION", rawId, "ds-decision-" + rawId, "TASK",
                    "Decision needed: " + str(r, "title"), str(r, "rationale"),
                    str(r, "domain"), "HIGH", "WARNING", 72, null,
                    DataDomains.of(str(r, "domain"), str(r, "title"), str(r, "rationale"))));
        }

        // Active incidents — action required.
        for (Map<String, Object> r : query(
                "SELECT id, title, severity, status, domain, affectedSystem, createdAt FROM Incident " +
                "WHERE tenantId = :tenantId AND status <> 'RESOLVED' LIMIT 50")) {
            String rawId = str(r, "id");
            String sev = str(r, "severity");
            String affects = str(r, "affectedSystem");
            out.add(dto(epq, "INCIDENT", rawId, "ds-incident-" + rawId, "TASK",
                    str(r, "title"), affects == null ? null : "Affects: " + affects,
                    str(r, "domain"), priority(sev), criticality(sev), score(sev), str(r, "createdAt"),
                    orDefault(DataDomains.normalize(str(r, "domain")), DataDomains.IT)));
        }
        return out;
    }

    // ── Notifications (informational / alerting) ──────────────────────────────

    public List<MailInsightDto> notifications() {
        List<MailInsightDto> out = new ArrayList<>();
        Map<String, JsonNode> epq = priorityEngine.queueByKey();

        // Active alerts.
        for (Map<String, Object> r : query(
                "SELECT id, title, severity, domain, message, createdAt FROM Alert " +
                "WHERE tenantId = :tenantId AND status = 'ACTIVE' LIMIT 50")) {
            String rawId = str(r, "id");
            String sev = str(r, "severity");
            out.add(dto(epq, "ALERT", rawId, "ds-alert-" + rawId, "NOTIFICATION",
                    str(r, "title"), str(r, "message"), str(r, "domain"),
                    priority(sev), criticality(sev), score(sev), str(r, "createdAt"),
                    orDefault(DataDomains.normalize(str(r, "domain")), DataDomains.IT)));
        }

        // Breached SLAs — not scored by priority-engine-service; always local.
        for (Map<String, Object> r : query(
                "SELECT id, system, metricName, target, actual, unit FROM SLAMetric " +
                "WHERE tenantId = :tenantId AND status = 'BREACHED' LIMIT 50")) {
            String unit = str(r, "unit");
            String summary = "Actual " + str(r, "actual") + unitSuffix(unit)
                    + " vs target " + str(r, "target") + unitSuffix(unit);
            out.add(dto(Map.of(), "SLA", str(r, "id"), "ds-sla-" + str(r, "id"), "NOTIFICATION",
                    str(r, "metricName") + " — " + str(r, "system"), summary,
                    str(r, "system"), "HIGH", "ALERT", 80, null, DataDomains.IT));
        }

        // Budget overruns (CostCentre) — not scored by priority-engine-service
        // (distinct from its own "BUDGET_ITEM" entity type); always local.
        for (Map<String, Object> r : query(
                "SELECT id, name, budget, spent, currency, variance FROM CostCentre " +
                "WHERE tenantId = :tenantId AND variance < 0 LIMIT 50")) {
            String cur = str(r, "currency");
            String summary = "Spent " + str(r, "spent") + " " + cur + " of " + str(r, "budget") + " " + cur;
            out.add(dto(Map.of(), "BUDGET", str(r, "id"), "ds-budget-" + str(r, "id"), "NOTIFICATION",
                    "Budget overrun: " + str(r, "name"), summary, "Finance",
                    "MEDIUM", "WARNING", 55, null, DataDomains.FINANCE));
        }
        return out;
    }

    /** Ids of all currently-live data-source items — lets the reminder scheduler
     *  detect items that have been resolved/closed in the source system. */
    public java.util.Set<String> allItemIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (MailInsightDto d : tasks()) ids.add(d.id());
        for (MailInsightDto d : notifications()) ids.add(d.id());
        return ids;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> query(String sql) {
        try {
            String tenantId = TenantContextHolder.requireTenantId().toString();
            return arcade.query(sql, Map.of("tenantId", tenantId));
        } catch (Exception e) {
            // Missing type / unreachable DB — degrade to empty so the tab still loads.
            log.debug("Data-source query degraded to empty: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * @param epq             priority-engine-service's queue, keyed by "TYPE:id" (see
     *                        {@link PriorityEngineClient#queueByKey()}) — empty map for
     *                        entity types priority-engine-service never scores.
     * @param epqType         priority-engine-service's type label for this row (e.g.
     *                        "INCIDENT"), used as the lookup key.
     * @param rawId           the entity's own id (NOT the "ds-…" synthetic id) — the queue
     *                        is keyed by the raw id.
     * @param fallbackPriority/fallbackCriticality/fallbackScore
     *                        this class's own severity-only heuristic, used when the row
     *                        isn't in the queue yet (or for types never scored there).
     */
    private static MailInsightDto dto(Map<String, JsonNode> epq, String epqType, String rawId,
                                      String id, String kind, String title,
                                      String summary, String senderDisplay, String fallbackPriority,
                                      String fallbackCriticality, int fallbackScore, String receivedAt,
                                      String domain) {
        JsonNode scored = epq.get(epqType + ":" + rawId);
        String priority, criticality, rationale;
        int score;
        if (scored != null) {
            double epqScore = scored.path("epqScore").asDouble(fallbackScore);
            priority = priorityFromEpq(epqScore);
            criticality = criticalityFromEpq(epqScore);
            score = (int) Math.round(epqScore);
            rationale = scored.path("rationale").asText("From the live ontology data source.");
        } else {
            priority = fallbackPriority;
            criticality = fallbackCriticality;
            score = fallbackScore;
            rationale = "From the live ontology data source.";
        }
        return new MailInsightDto(id, "", kind, epqType, title, summary, senderDisplay,
                priority, criticality, score, rationale,
                null, receivedAt, "OPEN", null, SourceChannel.DATA, SourceChannel.DATA, null,
                domain, false);
    }

    /** Mirrors priority-engine-service's own severity-score buckets (100/75/40/10). */
    private static String priorityFromEpq(double epq) {
        if (epq >= 80) return "CRITICAL";
        if (epq >= 55) return "HIGH";
        if (epq >= 30) return "MEDIUM";
        return "LOW";
    }

    private static String criticalityFromEpq(double epq) {
        if (epq >= 80) return "ALERT";
        if (epq >= 45) return "WARNING";
        return "INFORMATION";
    }

    private static String priority(String severity) {
        if (severity == null) return "MEDIUM";
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH" -> "HIGH";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private static String criticality(String severity) {
        if (severity == null) return "WARNING";
        return switch (severity.toUpperCase()) {
            case "CRITICAL", "HIGH" -> "ALERT";
            case "MEDIUM" -> "WARNING";
            default -> "INFORMATION";
        };
    }

    private static int score(String severity) {
        if (severity == null) return 50;
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 95;
            case "HIGH" -> 75;
            case "MEDIUM" -> 50;
            default -> 25;
        };
    }

    private static String unitSuffix(String unit) {
        return (unit == null || unit.isBlank()) ? "" : unit;
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : null;
    }
}
