package io.orphos.eip.briefing.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped reads against ArcadeDB for the briefing surfaces.
 *
 * <p>Extracted from BriefingController so the daily digest and the /latest
 * briefing run the SAME queries instead of drifting apart — the digest's whole
 * point is to narrate the numbers the dashboard already shows.</p>
 */
@Service
public class BriefingDataService {

    private static final String DB = "orphos";

    @Autowired
    private RestTemplate rest;

    @Value("${arcadedb.url:http://eip-arcadedb:2480}")
    private String arcadeUrl;

    @Value("${arcadedb.user:root}")
    private String arcadeUser;

    @Value("${arcadedb.password:}")
    private String arcadePassword;

    // createdAt / dueDate are selected so the AI Summary can date each row
    // ("12 min ago", "Due today"). They were always in the ORDER BY; they are
    // now projected as well rather than sorted on and discarded.
    public static final String Q_ACTIVE_INCIDENTS =
            "SELECT id, title, severity, status, affectedSystem, createdAt FROM Incident "
          + "WHERE tenantId = :tenantId AND status != 'RESOLVED' "
          + "ORDER BY createdAt DESC LIMIT 5";

    public static final String Q_CRITICAL_ALERTS =
            "SELECT id, title, severity, domain, message, createdAt FROM Alert "
          + "WHERE tenantId = :tenantId AND status='ACTIVE' "
          + "ORDER BY createdAt DESC LIMIT 3";

    public static final String Q_BREACHED_SLAS =
            "SELECT id, system, metricName, target, actual, unit FROM SLAMetric "
          + "WHERE tenantId = :tenantId AND status='BREACHED'";

    public static final String Q_BUDGET_OVERRUNS =
            "SELECT id, name, budget, spent, currency, variance FROM CostCentre "
          + "WHERE tenantId = :tenantId AND variance < 0";

    // impactSummary / linkedBudget give the digest row its "what is at stake"
    // figure; priorityLevel and dueDate give it urgency.
    //
    // DETECTED as well as PENDING: a decision the platform detected from
    // comms/documents is waiting on the same human confirmation a manually
    // raised one is. Filtering to PENDING alone hid every decision in a tenant
    // whose decisions all arrive via detection.
    public static final String Q_PENDING_DECISIONS =
            "SELECT id, title, domain, rationale, dueDate, createdAt, decidedAt, status, "
          + "priorityLevel, impactSummary, linkedBudget FROM Decision "
          + "WHERE tenantId = :tenantId AND (status = 'PENDING' OR status = 'DETECTED')";

    // ── "Important" — real, but not necessarily today ────────────────────────

    public static final String Q_OPEN_RISKS =
            "SELECT id, name, description, riskCategory, riskStatus, residualSeverity, "
          + "exposureAmount, currency, identifiedDate, remediationDueDate FROM RskRisk "
          + "WHERE tenantId = :tenantId AND riskStatus <> 'CLOSED' "
          + "ORDER BY residualSeverity DESC LIMIT 5";

    public static final String Q_OPEN_MILESTONES =
            "SELECT id, name, milestoneStatus, plannedEndDate FROM StrMilestone "
          + "WHERE tenantId = :tenantId AND milestoneStatus <> 'MET' "
          + "ORDER BY plannedEndDate LIMIT 6";

    public static final String Q_ACTIVE_CONTRACTS =
            "SELECT id, name, contractValue, currency, contractStatus, expiryDate FROM ComContract "
          + "WHERE tenantId = :tenantId AND contractStatus = 'ACTIVE' "
          + "ORDER BY expiryDate LIMIT 5";

    public static final String Q_UPCOMING_CHANGES =
            "SELECT id, name, changeStatus, changeRisk, scheduledDate FROM ItopsChangeRequest "
          + "WHERE tenantId = :tenantId AND changeStatus <> 'IMPLEMENTED' "
          + "ORDER BY scheduledDate LIMIT 5";

    // ── "What changed" — recent activity in the org's own records ────────────

    public static final String Q_RECENT_DECISIONS =
            "SELECT id, title, status, decidedAt, domain FROM Decision "
          + "WHERE tenantId = :tenantId AND decidedAt IS NOT NULL "
          + "ORDER BY decidedAt DESC LIMIT 5";

    public static final String Q_MET_MILESTONES =
            "SELECT id, name, actualEndDate FROM StrMilestone "
          + "WHERE tenantId = :tenantId AND milestoneStatus = 'MET' "
          + "ORDER BY actualEndDate DESC LIMIT 4";

    public static final String Q_RECENT_RELEASES =
            "SELECT id, name, releaseVersion, scheduledDate FROM ItopsRelease "
          + "WHERE tenantId = :tenantId ORDER BY scheduledDate DESC LIMIT 4";

    public static final String Q_LATEST_CASHFLOW =
            "SELECT fiscalYear, monthName, monthIndex, inflow, outflow, nett "
          + "FROM CashFlowMonth WHERE tenantId = :tenantId "
          + "ORDER BY fiscalYear DESC, monthIndex DESC LIMIT 1";

    /** Raw ArcadeDB envelope ({@code {"result":[...]}}), as /api/briefing/latest has always returned. */
    public Object rawQuery(String tenantId, String sql) {
        return rawQueryWithParams(sql, Map.of("tenantId", tenantId));
    }

    /**
     * As {@link #rawQuery}, for a query whose bindings are not just the tenant.
     *
     * <p>Added for the FX rate lookup, which filters on a currency pair, a rate
     * type and a date as well. Everything else here binds exactly one parameter,
     * which is why the original signature took a bare tenant id.</p>
     */
    public Object rawQueryWithParams(String sql, Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>();
        body.put("language", "sql");
        body.put("command", sql);
        body.put("params", params);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(arcadeUser, arcadePassword);

        return rest.exchange(arcadeUrl + "/api/v1/query/" + DB, HttpMethod.POST,
                new HttpEntity<>(body, headers), Object.class).getBody();
    }

    /**
     * Plain rows for a parameterised query. Same quiet degradation as
     * {@link #rows(String, String)} — a missing type must not fail the caller.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rowsWithParams(String sql, Map<String, Object> params) {
        try {
            Object raw = rawQueryWithParams(sql, params);
            if (raw instanceof Map<?, ?> envelope && envelope.get("result") instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>(list.size());
                for (Object row : list) {
                    if (row instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                }
                return out;
            }
        } catch (Exception e) {
            // Same reasoning as rows(): an absent FxRate type on a tenant that
            // has never entered a rate is the normal case, not an error.
        }
        return List.of();
    }

    /**
     * Same query, unwrapped to plain rows. Returns an empty list rather than
     * throwing when the type is missing or the database is unreachable: the
     * digest degrades one line item at a time instead of failing whole.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rows(String tenantId, String sql) {
        try {
            Object raw = rawQuery(tenantId, sql);
            if (raw instanceof Map<?, ?> envelope) {
                Object result = envelope.get("result");
                if (result instanceof List<?> list) {
                    List<Map<String, Object>> out = new ArrayList<>(list.size());
                    for (Object row : list) {
                        if (row instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                    }
                    return out;
                }
            }
        } catch (Exception e) {
            // Intentionally quiet: a missing document type on a fresh tenant is
            // the common case, not an error worth logging on every dashboard load.
        }
        return List.of();
    }
}
