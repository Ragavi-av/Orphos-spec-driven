package io.orphos.eip.decision.service;

import io.orphos.eip.decision.repository.DecisionRepository;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decision_Analytics_Dashboard backend (Req 8): trend, velocity, distribution
 * and effectiveness aggregates computed over a bounded projection of the
 * tenant's decisions, plus CSV export. Aggregation happens in-process (the
 * projection is capped at 10k rows) so the numbers always reflect the live
 * store — "real-time" in the Req 8.5 sense of no materialized lag.
 */
@Service
public class DecisionAnalyticsService {

    private static final int PROJECTION_LIMIT = 10_000;
    private static final int TREND_MONTHS = 12;

    private final DecisionRepository repo;

    public DecisionAnalyticsService(DecisionRepository repo) {
        this.repo = repo;
    }

    public Map<String, Object> analytics(String tenantId) {
        List<Map<String, Object>> rows = repo.analyticsRows(tenantId, PROJECTION_LIMIT);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("byStatus", countBy(rows, "status"));
        out.put("byDomain", countBy(rows, "domain"));
        out.put("byArea", countBy(rows, "decisionArea"));
        out.put("byPriority", countBy(rows, "priorityLevel"));
        out.put("byDetectionSource", countBy(rows, "detectionSource"));
        out.put("byImplementationStatus", countBy(rows, "implementationStatus"));

        Map<String, Map<String, Integer>> trend = monthlyTrend(rows);
        out.put("monthlyTrend", trend);
        out.put("velocityPerMonth", velocity(trend));

        long aiDetected = rows.stream()
            .filter(r -> str(r.get("detectionSource")).endsWith("_RAG")).count();
        out.put("aiDetectedCount", aiDetected);
        out.put("avgDetectionConfidence", round3(rows.stream()
            .filter(r -> r.get("confidenceScore") instanceof Number)
            .mapToDouble(r -> ((Number) r.get("confidenceScore")).doubleValue())
            .average().orElse(0)));
        out.put("escalatedCount", rows.stream()
            .filter(r -> Boolean.TRUE.equals(r.get("escalated"))).count());
        long completed = rows.stream()
            .filter(r -> "COMPLETED".equals(str(r.get("implementationStatus")))).count();
        long outcomesRecorded = rows.stream()
            .filter(r -> !str(r.get("actualOutcome")).isBlank()).count();
        out.put("completedCount", completed);
        out.put("outcomesRecordedCount", outcomesRecorded);
        // Impact effectiveness proxy: share of completed decisions whose outcome was captured.
        out.put("outcomeCoverage", completed == 0 ? 0 : round3((double) outcomesRecorded / completed));
        return out;
    }

    /** decidedAt month → {total, aiDetected} for the last 12 months (chronological). */
    private Map<String, Map<String, Integer>> monthlyTrend(List<Map<String, Object>> rows) {
        Map<String, Map<String, Integer>> trend = new TreeMap<>();
        YearMonth now = YearMonth.now();
        for (int i = TREND_MONTHS - 1; i >= 0; i--) {
            trend.put(now.minusMonths(i).toString(), new LinkedHashMap<>(Map.of("total", 0, "aiDetected", 0)));
        }
        for (Map<String, Object> row : rows) {
            String decidedAt = str(row.get("decidedAt"));
            if (decidedAt.length() < 7) continue;
            Map<String, Integer> bucket = trend.get(decidedAt.substring(0, 7));
            if (bucket == null) continue;
            bucket.merge("total", 1, Integer::sum);
            if (str(row.get("detectionSource")).endsWith("_RAG")) {
                bucket.merge("aiDetected", 1, Integer::sum);
            }
        }
        return trend;
    }

    private static double velocity(Map<String, Map<String, Integer>> trend) {
        List<Map<String, Integer>> buckets = List.copyOf(trend.values());
        int months = Math.min(3, buckets.size());
        if (months == 0) return 0;
        int sum = 0;
        for (int i = buckets.size() - months; i < buckets.size(); i++) {
            sum += buckets.get(i).getOrDefault("total", 0);
        }
        return round3((double) sum / months);
    }

    /** CSV export of the analytics projection (Req 8.4 — CSV opens in Excel; JSON feeds web dashboards). */
    public String exportCsv(String tenantId) {
        List<Map<String, Object>> rows = repo.analyticsRows(tenantId, PROJECTION_LIMIT);
        List<String> columns = List.of("id", "status", "domain", "decisionArea", "priorityLevel",
            "detectionSource", "confidenceScore", "implementationStatus", "decidedAt", "dueDate",
            "escalated", "outcomeRecordedAt");
        StringBuilder sb = new StringBuilder(String.join(",", columns)).append("\r\n");
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(csvEscape(str(row.get(columns.get(i)))));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static String csvEscape(String v) {
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static Map<String, Integer> countBy(List<Map<String, Object>> rows, String field) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String v = str(row.get(field));
            counts.merge(v.isBlank() ? "(unset)" : v, 1, Integer::sum);
        }
        return counts;
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
