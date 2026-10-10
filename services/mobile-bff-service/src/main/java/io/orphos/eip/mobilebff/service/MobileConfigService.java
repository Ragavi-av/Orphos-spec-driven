package io.orphos.eip.mobilebff.service;

import io.orphos.eip.mobilebff.repository.WorkspaceStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tenant configuration surfaced to (and editable from) the AI Admin: the
 * task-count status-dot thresholds and the inference rules for auto-detecting
 * Approvals and Travel from mail/decisions. Persisted as a tenant-scoped singleton
 * ({@code MobileConfig}) via {@link WorkspaceStore}, keyed by a fixed owner sentinel
 * since the config is tenant-wide, not per user. Reads always return defaults merged
 * with any stored overrides, so a partial admin edit never drops a key.
 */
@Service
public class MobileConfigService {

    /** Tenant-scoped config is stored under this synthetic owner. */
    public static final String CONFIG_SCOPE = "__tenant__";
    private static final String TYPE = "MobileConfig";

    private final WorkspaceStore workspace;

    public MobileConfigService(WorkspaceStore workspace) {
        this.workspace = workspace;
    }

    /** Built-in defaults. The status dot rule (task count): count &lt;= green → Green
     *  (available), count &gt;= red → Red (busy), otherwise Blue (moderate). Managed from
     *  Platform Admin → Mobile Access. */
    public static Map<String, Object> defaults() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("green", 10);   // <= 10 → green (available)
        status.put("blue", 20);    // moderate band midpoint
        status.put("red", 30);     // >= 30 → red (busy)

        // Mobile access + app defaults, all editable from Platform Admin → Mobile Access.
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("enabled", true);              // mobile access allowed for the tenant

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("mfaEnabled", true);
        security.put("biometricEnabled", true);

        Map<String, Object> alerts = new LinkedHashMap<>();
        alerts.put("includeCritical", true);      // Critical always alerts
        alerts.put("includeHigh", false);         // High priority opt-in (default off)

        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("keywords", List.of("approv", "sign-off", "sign off", "authoris", "authoriz",
                "sanction", "ratif", "go-ahead", "go ahead", "for approval", "needs approval",
                "your approval", "yes/no", "yes / no"));
        // keyword (lowercase) → approval type; anything not matched defaults to APPROVE_REJECT.
        Map<String, Object> typeRules = new LinkedHashMap<>();
        typeRules.put("yes/no", "YES_NO");
        typeRules.put("yes / no", "YES_NO");
        typeRules.put("go-ahead", "GO_AHEAD");
        typeRules.put("go ahead", "GO_AHEAD");
        approval.put("typeRules", typeRules);
        approval.put("defaultType", "APPROVE_REJECT");
        approval.put("defaultExpiryHours", 72);

        Map<String, Object> travel = new LinkedHashMap<>();
        travel.put("enabled", true);
        Map<String, Object> modeKeywords = new LinkedHashMap<>();
        modeKeywords.put("flight", "FLIGHT");
        modeKeywords.put("fly", "FLIGHT");
        modeKeywords.put("air", "FLIGHT");
        modeKeywords.put("train", "TRAIN");
        modeKeywords.put("rail", "TRAIN");
        modeKeywords.put("cab", "CAB");
        modeKeywords.put("taxi", "CAB");
        modeKeywords.put("car", "CAB");
        modeKeywords.put("drive", "CAB");
        travel.put("modeKeywords", modeKeywords);
        travel.put("keywords", List.of("travel", "trip", "flight", "train", "itinerary",
                "boarding", "pnr", "check-in", "departure", "arrival"));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("access", access);
        cfg.put("security", security);
        cfg.put("alerts", alerts);
        cfg.put("statusThresholds", status);
        cfg.put("refreshMinutes", 5);             // data refresh interval
        cfg.put("approval", approval);
        cfg.put("travel", travel);
        return cfg;
    }

    /** Effective config for a tenant: defaults with stored overrides applied on top. */
    public Map<String, Object> get(String tenantId) {
        Map<String, Object> merged = defaults();
        Map<String, Object> stored = workspace.single(TYPE, tenantId, CONFIG_SCOPE);
        if (stored != null && !stored.isEmpty()) {
            deepMerge(merged, stored);
        }
        return merged;
    }

    /** Persist an admin edit (stored verbatim; reads merge it over defaults). */
    public Map<String, Object> put(String tenantId, Map<String, Object> body) {
        workspace.upsertSingleton(TYPE, tenantId, CONFIG_SCOPE, body);
        return get(tenantId);
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> base, Map<String, Object> over) {
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object bv = base.get(e.getKey());
            Object ov = e.getValue();
            if (bv instanceof Map && ov instanceof Map) {
                deepMerge((Map<String, Object>) bv, (Map<String, Object>) ov);
            } else if (ov != null) {
                base.put(e.getKey(), ov);
            }
        }
    }
}
