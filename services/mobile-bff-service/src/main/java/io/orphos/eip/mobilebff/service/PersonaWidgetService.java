package io.orphos.eip.mobilebff.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.orphos.eip.common.access.DataDomains;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.mobilebff.component.DownstreamClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The caller's own widget board, for the app's Metrics tab.
 *
 * <p>The widget LIST is the one the web board shows for the same user: widgets-service
 * {@code /api/dashboard/layout/me} resolves it per user → tenant default → persona →
 * system, so a CEO, CFO, CTO, COO and CHRO each get their own set without a per-role
 * list in the app. The VALUES follow the web tile's rules too (see WidgetBoard.jsx
 * fetchMetric): ontology-declared widgets read {@code /api/dashboard/persona-metrics},
 * and the handful the web deliberately keeps on a live feed (pending decisions, active
 * incidents, SLA, …) read that feed, because a start-of-quarter observation of "decisions
 * waiting on me" would be worse than today's count.</p>
 *
 * <p>Every widget comes back in the persona-metrics {@code MetricValue} shape, so the app
 * formats one shape. When a live feed is down the widget falls back to its ontology
 * observation (still a real, period-labelled figure) and only then to "no data".</p>
 */
@Service
public class PersonaWidgetService {

    /** id → {label, area, description, source}. Mirrors frontend catalog.js so both surfaces name and define a tile alike. */
    private static final Map<String, String[]> CATALOG = new LinkedHashMap<>();
    static {
        CATALOG.put("uptime",             new String[]{"System Uptime",      "IT Operations", "Overall system availability vs SLA target", "SLA Monitoring"});
        CATALOG.put("budget",             new String[]{"IT Budget Consumed", "Finance", "Budget consumed vs plan this period", "Budget Plan"});
        CATALOG.put("incidents",          new String[]{"Active Incidents",   "IT Operations", "Open and in-progress incidents", "Operations Briefing"});
        CATALOG.put("rag-red",            new String[]{"Projects at Risk",   "Projects & Governance", "Projects flagged red in RAG status", "Project RAG"});
        CATALOG.put("pending-decisions",  new String[]{"Pending Decisions",  "Projects & Governance", "Decisions awaiting executive approval", "Decision Log"});
        CATALOG.put("alerts",             new String[]{"Active Alerts",      "IT Operations", "Critical and high-severity platform alerts", "Platform Alerts"});
        CATALOG.put("sla-health",         new String[]{"SLA Health",         "IT Operations", "SLA compliance ratio across all systems", "SLA Monitoring"});
        CATALOG.put("system-health",      new String[]{"IT System Health",   "IT Operations", "Degraded or critical IT systems count", "Operations Briefing"});
        CATALOG.put("budget-variance",    new String[]{"Budget Variance",    "Finance", "Cost centres running over or under budget", "Operations Briefing"});
        CATALOG.put("headcount",          new String[]{"Total Headcount",    "People", "Active employees across the organisation", "HR System"});
        CATALOG.put("headcount-dept",     new String[]{"Headcount by Dept",  "People", "Employee distribution across departments", "HR System"});
        CATALOG.put("payroll",            new String[]{"Annual Payroll",     "Finance", "Total annual CTC across all staff", "HR System"});
        CATALOG.put("net-cashflow",       new String[]{"Net Cash Flow",      "Finance", "Net cash position for the current fiscal year", "Finance System"});
        CATALOG.put("cash-inflow",        new String[]{"Cash Inflow",        "Finance", "Total receipts for the current fiscal year", "Finance System"});
        CATALOG.put("cash-outflow",       new String[]{"Cash Outflow",       "Finance", "Total payments for the current fiscal year", "Finance System"});
        CATALOG.put("pipeline-value",     new String[]{"Pipeline Value",     "Commercial", "Weighted open pipeline for the current quarter", "Enterprise Ontology"});
        CATALOG.put("revenue-arr",        new String[]{"Recurring Revenue",  "Commercial", "Contracted ARR across active clients", "Enterprise Ontology"});
        CATALOG.put("risk-exposure",      new String[]{"Risk Exposure",      "Risk & Compliance", "Total residual financial exposure on the risk register", "Enterprise Ontology"});
        CATALOG.put("attrition",          new String[]{"Attrition Rate",     "People", "Rolling attrition rate across the organisation", "Enterprise Ontology"});
        CATALOG.put("objective-progress", new String[]{"Objective Progress", "Projects & Governance", "Key results at or ahead of target", "Enterprise Ontology"});
    }

    /** Widgets the web board serves from a live feed rather than the ontology. */
    private static final Set<String> LIVE_FEED = Set.of(
            "uptime", "budget", "incidents", "pending-decisions", "alerts",
            "sla-health", "system-health", "headcount", "headcount-dept");

    /** Live widgets whose feed is gated by a data domain (see ZenitusMetricsController). */
    private static final Map<String, String> LIVE_FEED_DOMAIN = Map.of(
            "headcount", DataDomains.HR, "headcount-dept", DataDomains.HR);

    private static final String DEFAULT_AREA = "Onboarded Data";

    private final DownstreamClient ds;
    private final String widgetsUrl;
    private final String briefingUrl;
    private final String decisionLogUrl;
    private final String priorityUrl;
    private final String notificationUrl;

    public PersonaWidgetService(DownstreamClient ds,
                                @Value("${eip.downstream.widgets-url:http://eip-widgets:8095}") String widgetsUrl,
                                @Value("${eip.downstream.briefing-url:http://eip-briefing:8084}") String briefingUrl,
                                @Value("${eip.downstream.decision-log-url:http://eip-decision-log:8086}") String decisionLogUrl,
                                @Value("${eip.downstream.priority-url:http://eip-priority-engine:8083}") String priorityUrl,
                                @Value("${eip.downstream.notification-url:http://eip-notification:8087}") String notificationUrl) {
        this.ds = ds;
        this.widgetsUrl = widgetsUrl;
        this.briefingUrl = briefingUrl;
        this.decisionLogUrl = decisionLogUrl;
        this.priorityUrl = priorityUrl;
        this.notificationUrl = notificationUrl;
    }

    /**
     * {@code {source, widgets:[{id, label, area, ...MetricValue}]}} in board order.
     * {@code source} is the layout tier that won ("user" | "tenant" | "persona" | "system").
     */
    public Map<String, Object> board(String displayCurrency, String auth, String tid) {
        return board(PersonaDomainPolicy.Scope.UNRESTRICTED, displayCurrency, auth, tid);
    }

    /**
     * As {@link #board(String, String, String)}, refusing the data-domain-gated live widgets
     * the caller's scope excludes. The HR feed answers 403 for them, and without this the
     * refusal would read as "feed down" and fall back to the ontology figure it just refused.
     */
    public Map<String, Object> board(PersonaDomainPolicy.Scope scope, String displayCurrency, String auth, String tid) {
        JsonNode layout = ds.get(widgetsUrl, "/api/dashboard/layout/me", auth, tid);
        List<String> ids = visibleInOrder(layout);
        Feeds feeds = new Feeds(auth, tid);

        List<Map<String, Object>> widgets = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> w = new LinkedHashMap<>();
            String[] meta = CATALOG.get(id);
            w.put("id", id);
            w.put("label", meta != null ? meta[0] : id);
            w.put("area", meta != null ? meta[1] : DEFAULT_AREA);
            if (meta != null) {
                w.put("description", meta[2]);
                w.put("source", meta[3]);
            }
            String domain = LIVE_FEED_DOMAIN.get(id);
            Map<String, Object> value;
            if (domain != null && !scope.allows(domain, false, false)) {
                value = denied();
            } else {
                value = LIVE_FEED.contains(id) ? live(id, feeds) : null;
                if (value == null) value = ontology(id, displayCurrency, auth, tid);
            }
            w.putAll(value);
            widgets.add(w);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", layout != null ? text(layout, "source") : null);
        out.put("widgets", widgets);
        return out;
    }

    /** visibleIds, sorted by the layout's order; unknown order entries sort last. */
    private static List<String> visibleInOrder(JsonNode layout) {
        if (layout == null) return List.of();
        Set<String> visible = new LinkedHashSet<>();
        for (JsonNode n : layout.path("visibleIds")) visible.add(n.asText());
        List<String> out = new ArrayList<>();
        for (JsonNode n : layout.path("order")) if (visible.remove(n.asText())) out.add(n.asText());
        out.addAll(visible);
        return out;
    }

    private Map<String, Object> ontology(String id, String displayCurrency, String auth, String tid) {
        String query = displayCurrency == null || displayCurrency.isBlank() ? ""
                : "?displayCurrency=" + URLEncoder.encode(displayCurrency, StandardCharsets.UTF_8);
        JsonNode mv = ds.get(widgetsUrl, "/api/dashboard/persona-metrics/" + id + query, auth, tid);
        Map<String, Object> out = new LinkedHashMap<>();
        if (mv == null || !mv.isObject()) {
            out.put("permitted", true);
            out.put("available", false);
            out.put("connected", false);
            return out;
        }
        mv.fields().forEachRemaining(e -> {
            if (!"widgetKey".equals(e.getKey())) out.put(e.getKey(), e.getValue());
        });
        out.put("connected", true);
        return out;
    }

    /**
     * The web tile's live-feed computation, as a MetricValue. Null when the feed is down,
     * so the caller falls back to the ontology observation.
     */
    private Map<String, Object> live(String id, Feeds f) {
        switch (id) {
            case "budget": {
                JsonNode rows = f.briefing() == null ? null : f.briefing().path("budgetOverruns").path("result");
                if (rows == null || !rows.isArray()) return null;
                double budget = 0, spent = 0;
                for (JsonNode r : rows) { budget += r.path("budget").asDouble(0); spent += r.path("spent").asDouble(0); }
                if (budget <= 0) return null;
                return value(Math.round(spent / budget * 100), "PERCENT",
                        "Spent of budget · " + rows.size() + " over-budget cost centres",
                        spent > budget ? "RED" : "AMBER");
            }
            case "incidents": {
                JsonNode rows = f.briefing() == null ? null : f.briefing().path("activeIncidents").path("result");
                if (rows == null || !rows.isArray()) return null;
                int n = rows.size();
                return value(n, "COUNT", n > 0 ? n + " need attention" : "No open incidents", n > 0 ? "RED" : "GREEN");
            }
            case "system-health": {
                JsonNode rows = f.briefing() == null ? null : f.briefing().path("activeIncidents").path("result");
                if (rows == null || !rows.isArray()) return null;
                int n = rows.size();
                return value(n, "COUNT", n > 0 ? "IT systems affected" : "All IT systems healthy", n > 0 ? "RED" : "GREEN");
            }
            case "pending-decisions": {
                List<JsonNode> rows = rowsOf(f.decisions());
                if (rows == null) return null;
                long n = rows.stream().filter(d -> "PENDING".equals(text(d, "status"))).count();
                return value(n, "COUNT", n > 0 ? "Awaiting approval" : "All decided", n > 0 ? "AMBER" : "GREEN");
            }
            case "alerts": {
                List<JsonNode> rows = rowsOf(f.alerts());
                if (rows == null) return null;
                long crit = rows.stream().filter(a -> "CRITICAL".equals(text(a, "severity"))).count();
                return value(rows.size(), "COUNT", crit > 0 ? crit + " critical" : "No critical alerts",
                        rows.isEmpty() ? "GREEN" : "RED");
            }
            case "uptime": {
                List<JsonNode> rows = rowsOf(f.sla());
                if (rows == null) return null;
                for (JsonNode r : rows) {
                    String m = text(r, "metricName");
                    if (!"Availability".equals(m) && !"Uptime".equals(m)) continue;
                    Map<String, Object> v = value(r.path("actual").asDouble(), "PERCENT", "Availability vs SLA target",
                            "MET".equals(text(r, "status")) ? "GREEN" : "RED");
                    if (r.hasNonNull("target")) v.put("targetValue", r.path("target").asDouble());
                    return v;
                }
                return null;
            }
            case "sla-health": {
                List<JsonNode> rows = rowsOf(f.sla());
                if (rows == null || rows.isEmpty()) return null;
                long met = rows.stream().filter(r -> "MET".equals(text(r, "status"))).count();
                long breached = rows.size() - met;
                return value(met + "/" + rows.size(), "RATIO",
                        breached > 0 ? breached + " breached" : "All SLAs met", breached > 0 ? "RED" : "GREEN");
            }
            case "headcount": {
                JsonNode hr = f.hr();
                if (hr == null || !hr.hasNonNull("headcount")) return null;
                return value(hr.path("headcount").asLong(), "COUNT",
                        hr.path("byDepartment").size() + " departments", null);
            }
            case "headcount-dept": {
                JsonNode hr = f.hr();
                if (hr == null || !hr.path("byDepartment").isArray()) return null;
                JsonNode top = hr.path("byDepartment").path(0);
                String caption = top.isMissingNode() ? "No data"
                        : text(top, "label") + " leads with " + top.path("count").asLong();
                return value(hr.path("byDepartment").size(), "COUNT", caption, null);
            }
            default:
                return null;
        }
    }

    private static Map<String, Object> denied() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("permitted", false);
        v.put("available", false);
        v.put("connected", true);
        return v;
    }

    private static Map<String, Object> value(Object value, String unit, String subtitle, String rag) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("value", value);
        v.put("unit", unit);
        v.put("subtitle", subtitle);
        v.put("ragStatus", rag);
        v.put("permitted", true);
        v.put("available", true);
        v.put("connected", true);
        v.put("live", true);
        return v;
    }

    private static List<JsonNode> rowsOf(JsonNode data) {
        if (data == null) return null;
        JsonNode arr = data.isArray() ? data
                : data.path("result").isArray() ? data.path("result")
                : data.path("content").isArray() ? data.path("content")
                : data.path("items").isArray() ? data.path("items") : null;
        if (arr == null) return List.of();
        List<JsonNode> out = new ArrayList<>();
        arr.forEach(out::add);
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Each live feed fetched at most once per board, however many tiles read it. */
    private final class Feeds {
        private final String auth, tid;
        private final Map<String, JsonNode> cache = new LinkedHashMap<>();
        private final Set<String> fetched = new LinkedHashSet<>();

        Feeds(String auth, String tid) { this.auth = auth; this.tid = tid; }

        private JsonNode once(String key, String base, String path) {
            if (fetched.add(key)) cache.put(key, ds.get(base, path, auth, tid));
            return cache.get(key);
        }

        JsonNode briefing()  { return once("briefing", briefingUrl, "/api/briefing/latest"); }
        JsonNode decisions() { return once("decisions", decisionLogUrl, "/api/decisions"); }
        JsonNode alerts()    { return once("alerts", notificationUrl, "/api/alerts"); }
        JsonNode sla()       { return once("sla", priorityUrl, "/api/priority/sla"); }
        JsonNode hr()        { return once("hr", briefingUrl, "/api/zenitus/hr/summary"); }
    }
}
