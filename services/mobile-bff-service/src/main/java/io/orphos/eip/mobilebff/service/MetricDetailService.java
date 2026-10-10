package io.orphos.eip.mobilebff.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.orphos.eip.common.access.DataDomains;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.mobilebff.component.DownstreamClient;
import io.orphos.eip.mobilebff.component.MobileArcadeClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The records behind the Metrics tab's headline tiles — objectives and key results, the
 * open risk register, projects in flight, the sales pipeline, over-budget cost centres and
 * the monthly cash flow — so the screen can show what drives each number, not just the number.
 *
 * <p>Each section is read only when the caller's persona covers its data domain
 * ({@link PersonaDomainPolicy}); a section outside scope is omitted, never emptied, so the
 * app cannot tell a restricted section from one the tenant hasn't populated. Projects carry
 * their own domain and are filtered row by row. Any read failure drops just that section.</p>
 */
@Service
public class MetricDetailService {

    private static final Logger log = LogManager.getLogger(MetricDetailService.class);

    private static final String Q_OKRS =
            "SELECT name, currentValue, targetValue, baselineValue, "
          + "  out('CONTRIBUTES_TO').name AS objective FROM StrKeyResult "
          + "WHERE tenantId = :tenantId";

    private static final String Q_RISKS =
            "SELECT name, description, riskCategory, riskStatus, residualSeverity, exposureAmount, "
          + "  currency, remediationDueDate FROM RskRisk "
          + "WHERE tenantId = :tenantId AND riskStatus <> 'CLOSED' "
          + "ORDER BY residualSeverity DESC LIMIT 6";

    private static final String Q_PROJECTS =
            "SELECT name, status, domain, owner, progress, budget, spent, currency, endDate FROM Project "
          + "WHERE tenantId = :tenantId AND status <> 'COMPLETED' ORDER BY endDate LIMIT 20";

    private static final String Q_PIPELINE =
            "SELECT name, stage, probability, dealValue, weightedValue, currency, expectedCloseDate "
          + "FROM ComOpportunity WHERE tenantId = :tenantId ORDER BY weightedValue DESC LIMIT 6";

    // Metric definitions carry no figures, so they are not domain-gated like the sections below.
    private static final String Q_DEFINITIONS =
            "SELECT metricKey, aggregation, higherIsBetter, out('RENDERED_BY').widgetKey AS widgetKeys "
          + "FROM GovMetric WHERE tenantId = :tenantId";

    private static final String Q_OVER_BUDGET =
            "SELECT name, owner, budget, spent, variance, currency, period FROM CostCentre "
          + "WHERE tenantId = :tenantId AND variance < 0 ORDER BY variance LIMIT 5";

    private static final String Q_OVER_BUDGET_COUNT =
            "SELECT count(*) AS n FROM CostCentre WHERE tenantId = :tenantId AND variance < 0";

    private final MobileArcadeClient arcade;
    private final DownstreamClient ds;
    private final String briefingUrl;

    public MetricDetailService(MobileArcadeClient arcade, DownstreamClient ds,
                               @Value("${eip.downstream.briefing-url:http://eip-briefing:8084}") String briefingUrl) {
        this.arcade = arcade;
        this.ds = ds;
        this.briefingUrl = briefingUrl;
    }

    /** Section name → rows, for the sections this caller's persona may see. */
    public Map<String, Object> details(PersonaDomainPolicy.Scope scope, String auth, String tid) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> p = Map.of("tenantId", tid);
        put(out, "definitions", () -> definitions(query(Q_DEFINITIONS, p)));
        if (scope.allows(DataDomains.STRATEGY, false, false)) put(out, "okrs", () -> okrs(query(Q_OKRS, p)));
        if (scope.allows(DataDomains.RISK, false, false)) put(out, "risks", () -> query(Q_RISKS, p));
        if (scope.allows(DataDomains.COMMERCIAL, false, false)) put(out, "pipeline", () -> query(Q_PIPELINE, p));
        put(out, "projects", () -> query(Q_PROJECTS, p).stream()
                .filter(r -> scope.allows(DataDomains.of(str(r.get("domain")), str(r.get("name"))), false, false))
                .toList());
        if (scope.allows(DataDomains.FINANCE, false, false)) {
            put(out, "overBudget", () -> query(Q_OVER_BUDGET, p));
            put(out, "overBudgetCount", () -> {
                List<Map<String, Object>> r = query(Q_OVER_BUDGET_COUNT, p);
                return r.isEmpty() ? null : r.get(0).get("n");
            });
            // Through briefing, which owns the fiscal-month roll-up and its own finance gate.
            JsonNode cf = ds.get(briefingUrl, "/api/zenitus/cashflow/summary", auth, tid);
            if (cf != null && cf.path("months").isArray()) {
                Map<String, Object> cash = new LinkedHashMap<>();
                cash.put("currency", cf.path("currency").asText(null));
                cash.put("fiscalYear", cf.path("fiscalYear").asText(null));
                cash.put("months", cf.path("months"));
                out.put("cashflow", cash);
            }
        }
        return out;
    }

    /** widgetKey → {metricKey, aggregation, higherIsBetter} — how each tile's metric is measured. */
    private static Map<String, Object> definitions(List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("metricKey", r.get("metricKey"));
            def.put("aggregation", r.get("aggregation"));
            def.put("higherIsBetter", r.get("higherIsBetter"));
            if (r.get("widgetKeys") instanceof List<?> keys) {
                for (Object k : keys) if (k != null) out.putIfAbsent(k.toString(), def);
            }
        }
        return out;
    }

    /** Key results grouped under their objective, each with progress from baseline to target. */
    private static List<Map<String, Object>> okrs(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> byObjective = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String objective = first(r.get("objective"));
            if (objective == null) continue;
            Map<String, Object> kr = new LinkedHashMap<>();
            kr.put("name", r.get("name"));
            kr.put("currentValue", r.get("currentValue"));
            kr.put("targetValue", r.get("targetValue"));
            kr.put("progress", progress(num(r.get("baselineValue")), num(r.get("currentValue")), num(r.get("targetValue"))));
            byObjective.computeIfAbsent(objective, k -> new ArrayList<>()).add(kr);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byObjective.forEach((name, krs) -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", name);
            o.put("progress", (int) Math.round(krs.stream().mapToInt(k -> (Integer) k.get("progress")).average().orElse(0)));
            o.put("keyResults", krs);
            out.add(o);
        });
        return out;
    }

    /**
     * Share of the way from baseline to target, 0-100. Works for "lower is better" key
     * results too (target below baseline). Without a baseline, current / target.
     */
    static int progress(Double baseline, Double current, Double target) {
        if (current == null || target == null) return 0;
        double pct;
        if (baseline != null && Double.compare(target, baseline) != 0) {
            pct = (current - baseline) / (target - baseline) * 100;
        } else {
            pct = target == 0 ? (current <= 0 ? 100 : 0) : current / target * 100;
        }
        return (int) Math.round(Math.max(0, Math.min(100, pct)));
    }

    private List<Map<String, Object>> query(String sql, Map<String, Object> params) {
        return arcade.query(sql, params);
    }

    private static void put(Map<String, Object> out, String key, java.util.function.Supplier<Object> read) {
        try {
            Object v = read.get();
            if (v != null) out.put(key, v);
        } catch (Exception e) {
            log.warn("Metrics detail '{}' unavailable: {}", key, e.getMessage());
        }
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** A traversal projection comes back as a list; take its first element. */
    private static String first(Object o) {
        if (o instanceof List<?> l) return l.isEmpty() || l.get(0) == null ? null : l.get(0).toString();
        return str(o);
    }
}
