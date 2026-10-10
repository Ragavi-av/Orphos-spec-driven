package io.orphos.eip.briefing.controller;

import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.DataDomains;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.common.money.ConvertedAmount;
import io.orphos.eip.common.money.FigureKind;
import io.orphos.eip.common.money.MoneyConverter;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HR + Finance dashboard metrics for tenants whose data was loaded by the
 * zenitus-loader (Employee / CashFlowMonth vertices).
 *
 * <p>Zenitus is a SHARED-mode tenant, so its data lives in the shared
 * {@code orphos} database tagged with {@code tenantId}. Every query is scoped
 * by the JWT tenant id — identical to how BriefingController resolves data — so
 * one tenant never sees another's rows. Responses are aggregates only
 * (headcount, totals); no per-employee PII is returned.</p>
 */
@RestController
@RequestMapping("/api/zenitus")
public class ZenitusMetricsController {

    private final RestTemplate rest;

    /**
     * Restates HR/finance totals in the currency the reader picked.
     *
     * <p>These figures are all FLOWs — payroll and cash movements accumulate
     * across the fiscal year — so they take the period's average rate. The
     * as-of is the fiscal year the totals cover, not today, so a closed year
     * keeps the number it was priced at.</p>
     */
    private final MoneyConverter money;

    private final AccessGrantSource grants;

    public ZenitusMetricsController(MoneyConverter money, RestTemplate rest, AccessGrantSource grants) {
        this.grants = grants;
        this.money = money;
        this.rest = rest;
    }

    @Value("${arcadedb.url:http://eip-arcadedb:2480}")
    private String arcadeUrl;

    @Value("${arcadedb.user:root}")
    private String arcadeUser;

    @Value("${arcadedb.password:}")
    private String arcadePassword;

    /** Admin/shared database — holds the Tenant records used to resolve each
     *  tenant's data database. SHARED tenants keep their data here too. */
    private static final String ADMIN_DB = "orphos";

    /** Cache of tenantId → data database name (orphos for SHARED, eip_tenant_* for DEDICATED). */
    private final java.util.Map<String, String> dbCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Persona gate: HR figures are for the HR persona (CHRO, admin), finance for CFO/CEO/admin. */
    private boolean inScope(String domain) {
        return PersonaDomainPolicy.current(grants).allows(domain, false, false);
    }

    private static ResponseEntity<Object> outOfScope(String area) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(Map.of("permitted", false, "error", "Your role does not include " + area + " data."));
    }

    @GetMapping("/hr/summary")
    public ResponseEntity<Object> hrSummary(
            @RequestParam(required = false) String displayCurrency) {
        if (!inScope(DataDomains.HR)) return outOfScope("HR");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = query(resolveDb(tenantId()),
                    "SELECT department, employmentType, annualCtc FROM Employee WHERE tenantId = :t",
                    Map.of("t", tenantId()));

            Map<String, Integer> byDept = new LinkedHashMap<>();
            Map<String, Integer> byType = new LinkedHashMap<>();
            double payroll = 0d;
            for (Map<String, Object> r : rows) {
                String dept = str(r.get("department"));
                String type = str(r.get("employmentType"));
                if (dept != null) byDept.merge(dept, 1, Integer::sum);
                if (type != null) byType.merge(type, 1, Integer::sum);
                Double ctc = dbl(r.get("annualCtc"));
                if (ctc != null) payroll += ctc;
            }

            out.put("headcount", rows.size());
            // Annual CTC accumulates across the year, so it is a FLOW. No period
            // is carried on the Employee rows, so this prices at today's average
            // rate — stated as such in the tooltip rather than implied.
            putMoney(out, "payrollTotal", payroll, REPORTED_CURRENCY, displayCurrency,
                    FigureKind.FLOW, null);
            out.put("byDepartment", toBreakdown(byDept));
            out.put("byEmploymentType", toBreakdown(byType));
            out.put("status", "ok");
        } catch (Exception e) {
            out.put("status", "unavailable");
            out.put("error", e.getMessage());
            out.put("headcount", 0);
            out.put("byDepartment", List.of());
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/cashflow/summary")
    public ResponseEntity<Object> cashflowSummary(
            @RequestParam(required = false) String displayCurrency) {
        if (!inScope(DataDomains.FINANCE)) return outOfScope("finance");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> months = query(resolveDb(tenantId()),
                    "SELECT fiscalYear, monthName, monthIndex, inflow, outflow, nett " +
                    "FROM CashFlowMonth WHERE tenantId = :t ORDER BY fiscalYear, monthIndex",
                    Map.of("t", tenantId()));

            out.put("months", months);
            out.put("currency", REPORTED_CURRENCY);

            if (!months.isEmpty()) {
                Map<String, Object> latest = months.get(months.size() - 1);
                String fy = str(latest.get("fiscalYear"));
                out.put("latest", latest);
                out.put("fiscalYear", fy);

                double in = 0, outflow = 0, nett = 0;
                for (Map<String, Object> m : months) {
                    if (fy != null && fy.equals(str(m.get("fiscalYear")))) {
                        in += orZero(dbl(m.get("inflow")));
                        outflow += orZero(dbl(m.get("outflow")));
                        nett += orZero(dbl(m.get("nett")));
                    }
                }
                // Cash movements accumulate across the fiscal year: FLOW, priced
                // at the year's average rate. All three go through one conversion
                // pass so inflow − outflow still equals nett after restating —
                // converting them independently at rates that straddled a change
                // would leave the three numbers not adding up on screen.
                LocalDate asOf = fiscalYearEnd(fy);
                Map<String, Object> totals = new LinkedHashMap<>();
                putMoney(totals, "inflow", in, REPORTED_CURRENCY, displayCurrency, FigureKind.FLOW, asOf);
                putMoney(totals, "outflow", outflow, REPORTED_CURRENCY, displayCurrency, FigureKind.FLOW, asOf);
                putMoney(totals, "nett", nett, REPORTED_CURRENCY, displayCurrency, FigureKind.FLOW, asOf);
                out.put("ytd", totals);
                // Note what is NOT converted: the per-month `months` rows. Each
                // covers a different month and would need its own rate, and the
                // response-level `currency` describes those rows. So it stays the
                // reported one, and a converted ytd total carries its currency in
                // its own `<key>Fx` block instead — which is what the tiles read.
                // Overwriting `currency` here would relabel every month row with a
                // unit it was never converted into.
            }
            out.put("status", "ok");
        } catch (Exception e) {
            out.put("status", "unavailable");
            out.put("error", e.getMessage());
            out.put("months", List.of());
        }
        return ResponseEntity.ok(out);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The currency these loaders write.
     *
     * <p>Still a constant, because it is a fact about the zenitus-loader's
     * output rather than a choice: {@code Employee.annualCtc} and
     * {@code CashFlowMonth} carry no currency property, so there is nothing to
     * read it from. Named instead of inlined so the assumption is visible at
     * both use sites, and so the day those vertices gain a currency column there
     * is one place to change.</p>
     */
    private static final String REPORTED_CURRENCY = "INR";

    /**
     * Put an amount into the response, converted where a rate allows, with its
     * provenance beside it under {@code <key>Fx}.
     *
     * <p>The sibling-key shape rather than a nested object because the existing
     * tiles read {@code data.payrollTotal} and {@code data.ytd.inflow} directly;
     * nesting the amount would have broken every one of them for the sake of
     * tidiness. A client that ignores the {@code Fx} key gets exactly what it got
     * before, in whatever currency the figure is now in.</p>
     */
    private void putMoney(Map<String, Object> target, String key, double amount,
                          String fromCurrency, String displayCurrency,
                          FigureKind kind, LocalDate asOf) {
        ConvertedAmount converted = money.convert(
                BigDecimal.valueOf(amount), fromCurrency, displayCurrency, kind, asOf);
        target.put(key, converted.value());
        if (converted.converted()) {
            Map<String, Object> fx = new LinkedHashMap<>();
            fx.put("reportedValue", converted.sourceValue());
            fx.put("reportedCurrency", converted.sourceCurrency());
            fx.put("displayCurrency", converted.currency());
            fx.put("rate", converted.rate());
            fx.put("rateAsOf", String.valueOf(converted.rateAsOf()));
            fx.put("rateType", converted.rateType().name());
            fx.put("rateSource", converted.rateSource());
            target.put(key + "Fx", fx);
        }
    }

    /**
     * Last day of a fiscal year label ("FY2027" → 31 March 2027).
     *
     * <p>April–March, which is what the zenitus data uses throughout (see the
     * {@code prd-fy2027-q*} periods). An unrecognised label yields null, which
     * prices at today — wrong-ish but explainable, and better than guessing a
     * year boundary and pricing a closed year at a rate from the wrong side of it.</p>
     */
    private static LocalDate fiscalYearEnd(String fiscalYear) {
        if (fiscalYear == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})").matcher(fiscalYear);
        if (!m.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), 3, 31);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    /**
     * Resolve the tenant's data database: {@code orphos} for SHARED tenants,
     * {@code eip_tenant_*} for DEDICATED. Read from the Tenant record in the
     * admin DB and cached. Falls back to {@code orphos} on any lookup failure.
     */
    private String resolveDb(String tenantId) {
        return dbCache.computeIfAbsent(tenantId, tid -> {
            try {
                List<Map<String, Object>> rows = query(ADMIN_DB,
                        "SELECT arcadeDatabase FROM Tenant WHERE id = :id", Map.of("id", tid));
                if (!rows.isEmpty()) {
                    Object db = rows.get(0).get("arcadeDatabase");
                    if (db != null && !db.toString().isBlank()) return db.toString();
                }
            } catch (Exception ignored) { /* fall through */ }
            return ADMIN_DB;
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> query(String database, String sql, Map<String, Object> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(arcadeUser, arcadePassword);
        Map<String, Object> body = Map.of("language", "sql", "command", sql, "params", params);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        Map<String, Object> resp = rest.exchange(
                arcadeUrl + "/api/v1/query/" + database, HttpMethod.POST, entity, Map.class).getBody();

        Object result = resp == null ? null : resp.get("result");
        if (result instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
            return out;
        }
        return List.of();
    }

    private static List<Map<String, Object>> toBreakdown(Map<String, Integer> counts) {
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("label", e.getKey());
                    row.put("count", e.getValue());
                    out.add(row);
                });
        return out;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static double orZero(Double d) {
        return d == null ? 0d : d;
    }
}
