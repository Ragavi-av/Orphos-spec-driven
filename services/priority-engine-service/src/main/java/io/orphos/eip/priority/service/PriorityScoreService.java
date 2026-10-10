package io.orphos.eip.priority.service;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.common.tenant.TenantTaskExecutor;
import io.orphos.eip.priority.repository.ArcadeDbFetcher;
import io.orphos.eip.priority.util.EpqConfig;
import io.orphos.eip.priority.vo.PriorityItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * EPQ — Executive Priority Queue scoring engine, per tenant.
 *
 * <p>Composite score formula (all dimensions 0–100):
 *   EPQ = severity×0.35 + urgency×0.25 + impact×0.20 + recency×0.10 + domainRisk×0.10
 * </p>
 *
 * <p>The scored queue is rebuilt every {@code epq.cache.refresh-ms}
 * milliseconds for every active tenant via {@link TenantTaskExecutor}.
 * Each tenant's queue is stored in a tenant-keyed {@link ConcurrentMap} so
 * reads remain non-blocking and a refresh failure for one tenant does not
 * stall the others.</p>
 */
@Service
public class PriorityScoreService {

    private static final Logger log = LogManager.getLogger(PriorityScoreService.class);

    private final ArcadeDbFetcher fetcher;
    private final EpqConfig       cfg;
    private final TenantTaskExecutor tenantExecutor;
    private final ConcurrentMap<UUID, List<PriorityItem>> cache = new ConcurrentHashMap<>();

    public PriorityScoreService(ArcadeDbFetcher fetcher, EpqConfig cfg, TenantTaskExecutor tenantExecutor) {
        this.fetcher = fetcher;
        this.cfg     = cfg;
        this.tenantExecutor = tenantExecutor;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Reads the EPQ queue for the calling tenant. Returns empty if no refresh has run yet. */
    public List<PriorityItem> getQueue() {
        UUID tenantId = TenantContextHolder.requireTenantId();
        return cache.getOrDefault(tenantId, List.of());
    }

    // ── Scheduled refresh ─────────────────────────────────────────────────

    /**
     * Rebuilds the queue for every active tenant. Runs on the
     * {@code epq.cache.refresh-ms} cadence. We intentionally don't run on
     * {@code @PostConstruct} any more — admin-service may not yet be reachable
     * at startup, and the first scheduled tick (~30 s) is fast enough.
     */
    @Scheduled(fixedDelayString = "${epq.cache.refresh-ms:30000}")
    public void refresh() {
        tenantExecutor.forEachTenant(tenantId -> {
            try {
                List<PriorityItem> scored = buildQueue();
                cache.put(tenantId, scored);
                if (log.isDebugEnabled()) {
                    log.debug("EPQ tenant={} refreshed — {} items, top {}",
                            tenantId, scored.size(),
                            scored.isEmpty() ? "n/a" : String.format("%.1f", scored.get(0).epqScore()));
                }
            } catch (Exception e) {
                log.warn("EPQ refresh failed for tenant {}, retaining stale cache: {}",
                        tenantId, e.getMessage());
            }
        });
    }

    /**
     * Forces a refresh for the calling tenant only. Used by the manual
     * refresh endpoint; the scheduled refresh continues independently.
     */
    public void refreshCurrent() {
        UUID tenantId = TenantContextHolder.requireTenantId();
        try {
            List<PriorityItem> scored = buildQueue();
            cache.put(tenantId, scored);
            log.info("EPQ on-demand refresh tenant={} — {} items", tenantId, scored.size());
        } catch (Exception e) {
            log.warn("EPQ on-demand refresh failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    // ── Queue builder ─────────────────────────────────────────────────────

    private List<PriorityItem> buildQueue() {
        List<PriorityItem> items = new ArrayList<>();

        fetcher.fetchIncidents() .forEach(r -> items.add(score("INCIDENT",    r)));
        fetcher.fetchAlerts()    .forEach(r -> items.add(score("ALERT",       r)));
        fetcher.fetchBudgetItems().forEach(r -> items.add(score("BUDGET_ITEM", r)));
        fetcher.fetchDecisions() .forEach(r -> items.add(score("DECISION",    r)));
        fetcher.fetchKeyResults().forEach(r -> items.add(score("KEY_RESULT",  r)));
        fetcher.fetchProjects()  .forEach(r -> items.add(score("PROJECT",     r)));

        items.sort((a, b) -> Double.compare(b.epqScore(), a.epqScore()));
        List<PriorityItem> ranked = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PriorityItem p = items.get(i);
            ranked.add(new PriorityItem(
                p.id(), p.type(), p.title(), p.severity(), p.status(), p.domain(),
                p.epqScore(), i + 1, p.scoreBreakdown(), p.rationale(), p.attributes()
            ));
        }
        return Collections.unmodifiableList(ranked);
    }

    // ── Scorer ────────────────────────────────────────────────────────────
    // (unchanged from pre-multi-tenant — only the orchestration above is tenant-aware)

    private PriorityItem score(String type, Map<String, Object> raw) {
        EpqConfig.Weights w = cfg.getWeights();

        double sev  = severityScore(type, raw);
        double urg  = urgencyScore(type, raw);
        double imp  = impactScore(type, raw);
        double rec  = recencyScore(raw);
        double dom  = domainRiskScore(str(raw, "domain"));

        double epq  = round2(sev * w.getSeverity()
                           + urg * w.getUrgency()
                           + imp * w.getImpact()
                           + rec * w.getRecency()
                           + dom * w.getDomainRisk());

        Map<String, Double> breakdown = Map.of(
            "severity",   round2(sev),
            "urgency",    round2(urg),
            "impact",     round2(imp),
            "recency",    round2(rec),
            "domainRisk", round2(dom)
        );

        return new PriorityItem(
            str(raw, "id"),
            type,
            title(type, raw),
            deriveSeverityLabel(type, raw),
            str(raw, "status"),
            str(raw, "domain"),
            epq,
            0,
            breakdown,
            buildRationale(sev, urg, imp, dom, raw),
            raw
        );
    }

    private double severityScore(String type, Map<String, Object> raw) {
        String sev    = str(raw, "severity");
        String status = str(raw, "status");

        if (sev != null) {
            return switch (sev.toUpperCase()) {
                case "CRITICAL" -> 100;
                case "HIGH"     ->  75;
                case "MEDIUM"   ->  40;
                case "LOW"      ->  10;
                default         ->  20;
            };
        }

        return switch (status != null ? status.toUpperCase() : "") {
            case "PENDING_APPROVAL" -> 85;
            case "PENDING"          -> 65;
            case "AT_RISK"          -> 65;
            case "BEHIND"           -> 55;
            case "DELAYED"          -> 50;
            case "IN_REVIEW"        -> 40;
            case "IN_PROGRESS"      -> 30;
            case "ON_TRACK"         -> 15;
            case "APPROVED"         -> 20;
            default                 -> 20;
        };
    }

    private double urgencyScore(String type, Map<String, Object> raw) {
        String deadlineStr = switch (type) {
            case "INCIDENT"    -> str(raw, "estimatedResolution");
            case "BUDGET_ITEM" -> str(raw, "dueDate");
            case "DECISION"    -> str(raw, "decidedAt");
            default            -> null;
        };

        if (deadlineStr != null) {
            long hoursUntil = parseHoursUntil(deadlineStr);
            if (hoursUntil < 0)   return 100;
            if (hoursUntil < 4)   return  95;
            if (hoursUntil < 8)   return  85;
            if (hoursUntil < 24)  return  70;
            if (hoursUntil < 72)  return  55;
            if (hoursUntil < 168) return  40;
            return 20;
        }

        if ("KEY_RESULT".equals(type)) {
            double gap = progressGapPct(raw);
            if (gap >= 60) return 90;
            if (gap >= 40) return 70;
            if (gap >= 20) return 50;
            return 30;
        }

        if ("ALERT".equals(type)) return 75;

        return 35;
    }

    private double impactScore(String type, Map<String, Object> raw) {
        return switch (type) {
            case "BUDGET_ITEM" -> {
                double amount = num(raw, "amount");
                if (amount >= 1_000_000) yield 100;
                if (amount >= 500_000)   yield  85;
                if (amount >= 200_000)   yield  65;
                if (amount >= 50_000)    yield  40;
                yield 20;
            }
            case "INCIDENT" -> {
                String sev = str(raw, "severity");
                yield (sev == null) ? 50 : switch (sev.toUpperCase()) {
                    case "CRITICAL" -> 95;
                    case "HIGH"     -> 75;
                    case "MEDIUM"   -> 45;
                    default         -> 20;
                };
            }
            case "KEY_RESULT" -> {
                double gap = progressGapPct(raw);
                if (gap >= 50) yield 90;
                if (gap >= 30) yield 70;
                if (gap >= 15) yield 50;
                yield 25;
            }
            case "PROJECT" -> {
                double budget = num(raw, "budget");
                if (budget >= 2_000_000) yield 95;
                if (budget >= 1_000_000) yield 80;
                if (budget >= 500_000)   yield 60;
                yield 35;
            }
            case "DECISION" -> 60;
            case "ALERT"    -> domainRiskScore(str(raw, "domain"));
            default         -> 40;
        };
    }

    private double recencyScore(Map<String, Object> raw) {
        String createdAt = str(raw, "createdAt");
        if (createdAt == null) return 50;
        try {
            LocalDateTime created = LocalDateTime.parse(createdAt, DateTimeFormatter.ISO_DATE_TIME);
            long hoursOld = ChronoUnit.HOURS.between(created, LocalDateTime.now());
            return Math.max(5, 100 - (hoursOld / 48.0) * 100);
        } catch (Exception e) {
            return 50;
        }
    }

    private double domainRiskScore(String domain) {
        if (domain == null) return 40;
        return switch (domain.toUpperCase()) {
            case "FINANCE"                -> 100;
            case "SECURITY", "COMPLIANCE" ->  90;
            case "IT"                     ->  75;
            case "SALES"                  ->  60;
            case "OPERATIONS"             ->  50;
            default                       ->  40;
        };
    }

    private String buildRationale(double sev, double urg, double imp, double dom,
                                  Map<String, Object> raw) {
        List<String> factors = new ArrayList<>();
        if (sev >= 85)  factors.add("critical/high severity");
        if (urg >= 95)  factors.add("overdue or imminent deadline");
        else if (urg >= 70) factors.add("urgent deadline");
        if (imp >= 80)  factors.add("high financial/operational impact");
        if (dom >= 90)  factors.add("finance/security domain");

        String status = str(raw, "status");
        if ("PENDING_APPROVAL".equalsIgnoreCase(status)) factors.add(0, "requires CEO approval");
        if ("BEHIND".equalsIgnoreCase(status))           factors.add("OKR behind target");

        if (factors.isEmpty()) return "Moderate priority across all dimensions";
        if (factors.size() == 1) return "Elevated: " + factors.get(0);
        return "Elevated: " + factors.get(0) + "; " + factors.get(1);
    }

    private String title(String type, Map<String, Object> raw) {
        String t = "PROJECT".equals(type) ? str(raw, "name") : str(raw, "title");
        return t != null ? t : type + " " + str(raw, "id");
    }

    private String deriveSeverityLabel(String type, Map<String, Object> raw) {
        String sev = str(raw, "severity");
        if (sev != null) return sev;
        String status = str(raw, "status");
        if (status == null) return "MEDIUM";
        return switch (status.toUpperCase()) {
            case "PENDING_APPROVAL", "AT_RISK" -> "HIGH";
            case "PENDING", "BEHIND", "DELAYED" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private long parseHoursUntil(String dateStr) {
        try {
            LocalDateTime dt = LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME);
            return ChronoUnit.HOURS.between(LocalDateTime.now(), dt);
        } catch (Exception ignored) {}
        try {
            LocalDate date = LocalDate.parse(dateStr);
            return ChronoUnit.HOURS.between(LocalDateTime.now(), date.atTime(23, 59));
        } catch (Exception e) {
            return 72;
        }
    }

    private double progressGapPct(Map<String, Object> raw) {
        double target  = num(raw, "target");
        double current = num(raw, "current");
        if (target <= 0) return 0;
        return Math.abs(target - current) / target * 100.0;
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
