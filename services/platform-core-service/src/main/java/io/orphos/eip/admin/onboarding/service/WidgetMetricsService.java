package io.orphos.eip.admin.onboarding.service;

import io.orphos.eip.core.arcadedb.ArcadeConnectionResolver;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.WidgetProposal;
import io.orphos.eip.admin.onboarding.registry.TenantDataRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Computes a single dynamic widget's value/detail by running the appropriate
 * aggregation against the tenant's onboarded ArcadeDB vertex type. All inputs
 * come from the tenant's own registry (admin-defined), and every identifier is
 * whitelist-validated before it touches SQL; values are parameter-bound.
 */
@Service
public class WidgetMetricsService {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final ArcadeDbTemplate arcade;
    private final TenantDataRegistry registry;
    private final ArcadeConnectionResolver resolver;
    private final java.util.concurrent.ConcurrentHashMap<String, Route> routeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** A tenant's data-DB name + the connection whose server hosts it. */
    private record Route(String db, String connId) {}

    public WidgetMetricsService(ArcadeDbTemplate arcade, TenantDataRegistry registry,
                                ArcadeConnectionResolver resolver) {
        this.arcade = arcade;
        this.registry = registry;
        this.resolver = resolver;
    }

    public Map<String, Object> compute(UUID tenantId, String widgetId) {
        WidgetProposal w = registry.widget(tenantId, widgetId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (w == null) { out.put("value", "—"); out.put("subtitle", "Unknown widget"); return out; }

        String cls = w.entity();
        if (!safe(cls)) { out.put("value", "—"); out.put("subtitle", "Invalid entity"); return out; }
        String t = tenantId.toString();
        Route route = resolveRoute(tenantId);
        String db = route.db();                                   // the tenant's own (DEDICATED) database
        ArcadeDbTemplate tdb = resolver.templateFor(route.connId());   // on its chosen server

        try {
            switch (w.type()) {
                case COUNT -> {
                    long n = num(one(tdb, db, "SELECT count(*) AS v FROM " + cls + " WHERE tenantId = :t",
                            Map.of("t", t)));
                    out.put("value", String.valueOf(n));
                    out.put("subtitle", w.label());
                }
                case SUM, AVG -> {
                    if (!safe(w.column())) { out.put("value", "—"); break; }
                    String fn = w.type() == OnboardingDtos.WidgetType.AVG ? "avg" : "sum";
                    Object v = one(tdb, db, "SELECT " + fn + "(" + w.column() + ") AS v FROM " + cls
                            + " WHERE tenantId = :t", Map.of("t", t));
                    out.put("value", formatNumber(v));
                    out.put("subtitle", w.label());
                }
                case BREAKDOWN -> {
                    if (!safe(w.groupBy())) { out.put("value", "—"); break; }
                    List<Map<String, Object>> rows = tdb.queryOn(db,
                            "SELECT " + w.groupBy() + " AS label, count(*) AS value FROM " + cls
                          + " WHERE tenantId = :t GROUP BY " + w.groupBy() + " ORDER BY value DESC LIMIT 25",
                            Map.of("t", t));
                    out.put("value", String.valueOf(rows.size()));
                    if (!rows.isEmpty()) {
                        out.put("subtitle", rows.get(0).get("label") + " leads (" + rows.get(0).get("value") + ")");
                    }
                    out.put("rows", labelValueRows(rows));
                }
                case TREND -> {
                    if (!safe(w.column()) || !safe(w.dateColumn())) { out.put("value", "—"); break; }
                    List<Map<String, Object>> rows = tdb.queryOn(db,
                            "SELECT " + w.dateColumn() + " AS label, sum(" + w.column() + ") AS value FROM " + cls
                          + " WHERE tenantId = :t GROUP BY " + w.dateColumn() + " ORDER BY label LIMIT 60",
                            Map.of("t", t));
                    out.put("value", rows.isEmpty() ? "—" : formatNumber(rows.get(rows.size() - 1).get("value")));
                    out.put("subtitle", w.label());
                    out.put("rows", labelValueRows(rows));
                }
            }
        } catch (Exception e) {
            out.put("value", "—");
            out.put("subtitle", "Data unavailable");
        }
        return out;
    }

    private boolean safe(String s) {
        return s != null && IDENT.matcher(s).matches();
    }

    private Object one(ArcadeDbTemplate tdb, String database, String sql, Map<String, Object> params) {
        List<Map<String, Object>> rows = tdb.queryOn(database, sql, params);
        return rows.isEmpty() ? null : rows.get(0).get("v");
    }

    /**
     * Resolve the tenant's data DB name + hosting connection from its Tenant
     * record (always on the platform DB). Cached. Falls back to the shared
     * {@code orphos} on the platform ArcadeDB.
     */
    private Route resolveRoute(UUID tenantId) {
        return routeCache.computeIfAbsent(tenantId.toString(), tid -> {
            try {
                List<Map<String, Object>> r = arcade.query(
                        "SELECT arcadeDatabase, arcadeConnectionId FROM Tenant WHERE id = :id",
                        Map.of("id", tid));
                if (!r.isEmpty()) {
                    Object d = r.get(0).get("arcadeDatabase");
                    Object c = r.get(0).get("arcadeConnectionId");
                    String db = (d != null && !d.toString().isBlank()) ? d.toString() : "orphos";
                    String connId = (c != null && !c.toString().isBlank()) ? c.toString() : null;
                    return new Route(db, connId);
                }
            } catch (Exception ignored) { /* fall through */ }
            return new Route("orphos", null);
        });
    }

    private long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? 0 : (long) Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }

    private String formatNumber(Object o) {
        if (o == null) return "0";
        try {
            double d = o instanceof Number n ? n.doubleValue() : Double.parseDouble(o.toString());
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.format("%,d", (long) d);
            return String.format("%,.2f", d);
        } catch (Exception e) {
            return o.toString();
        }
    }

    private List<Map<String, Object>> labelValueRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", String.valueOf(r.getOrDefault("label", "")));
            m.put("value", r.get("value"));
            out.add(m);
        }
        return out;
    }
}
