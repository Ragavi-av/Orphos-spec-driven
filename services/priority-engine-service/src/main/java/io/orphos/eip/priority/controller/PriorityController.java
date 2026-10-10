package io.orphos.eip.priority.controller;

import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.DataDomains;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.priority.vo.PriorityItem;
import io.orphos.eip.priority.repository.ArcadeDbFetcher;
import io.orphos.eip.priority.service.PriorityScoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The Executive Priority Queue and its supporting feeds. Every response is filtered to
 * the caller's persona ({@link PersonaDomainPolicy}): a CTO's queue holds IT items, a
 * CFO's finance and commercial ones, a CEO's executive and finance items plus anything
 * critical. Internal callers (e.g. mail-insight scoring with the internal token) see
 * the whole tenant queue.
 */
@RestController
@RequestMapping("/api/priority")
public class PriorityController {

    private final PriorityScoreService scoreService;
    private final ArcadeDbFetcher fetcher;
    private final AccessGrantSource grants;

    public PriorityController(PriorityScoreService scoreService, ArcadeDbFetcher fetcher,
                              AccessGrantSource grants) {
        this.scoreService = scoreService;
        this.fetcher = fetcher;
        this.grants = grants;
    }

    /** EPQ-scored queue — all entity types ranked by composite score, scoped to the calling tenant and persona. */
    @GetMapping("/queue")
    public ResponseEntity<Object> getPriorityQueue() {
        List<PriorityItem> queue = visible(scoreService.getQueue());
        return ResponseEntity.ok(Map.of(
            "queue",  queue,
            "count",  queue.size(),
            "source", "EPQ-v1"
        ));
    }

    /** Force an immediate EPQ refresh for the calling tenant and return the new queue. */
    @PostMapping("/queue/refresh")
    public ResponseEntity<Object> refreshQueue() {
        scoreService.refreshCurrent();
        List<PriorityItem> queue = visible(scoreService.getQueue());
        return ResponseEntity.ok(Map.of(
            "queue",     queue,
            "count",     queue.size(),
            "refreshed", true
        ));
    }

    // Both endpoints return {"result": [...rows]} — a controlled shape built from
    // the ArcadeDbTemplate rows. This deliberately keeps the "result" key the SPA
    // already reads, but no longer forwards the raw ArcadeDB driver body (which
    // also exposed user/version/serverName).

    /** SLA metrics — tenant-scoped; technology data, so IT personas (and the CEO when breached). */
    @GetMapping("/sla")
    public ResponseEntity<Object> getSlaMetrics() {
        PersonaDomainPolicy.Scope scope = PersonaDomainPolicy.current(grants);
        List<Map<String, Object>> rows = fetcher.fetchSlaMetrics().stream()
                .filter(r -> scope.allows(DataDomains.IT, "BREACHED".equalsIgnoreCase(str(r.get("status"))), false))
                .toList();
        return ResponseEntity.ok(Map.of("result", rows));
    }

    /** Active alerts — used by HeaderBar chips, tenant-scoped and persona-filtered. */
    @GetMapping("/alerts")
    public ResponseEntity<Object> getAlerts() {
        PersonaDomainPolicy.Scope scope = PersonaDomainPolicy.current(grants);
        List<Map<String, Object>> rows = fetcher.fetchActiveAlertsRanked().stream()
                .filter(r -> scope.allows(labelOr(str(r.get("domain")), DataDomains.IT),
                        "CRITICAL".equalsIgnoreCase(str(r.get("severity"))), false))
                .toList();
        return ResponseEntity.ok(Map.of("result", rows));
    }

    private List<PriorityItem> visible(List<PriorityItem> queue) {
        PersonaDomainPolicy.Scope scope = PersonaDomainPolicy.current(grants);
        if (scope.unrestricted()) return queue;
        return queue.stream()
                .filter(p -> scope.allows(domainOf(p), "CRITICAL".equalsIgnoreCase(p.severity()), false))
                .toList();
    }

    /** An item's business domain: its own label, else the default for its entity type. */
    static String domainOf(PriorityItem p) {
        String type = p.type() == null ? "" : p.type().toUpperCase();
        return switch (type) {
            case "INCIDENT", "ALERT" -> labelOr(p.domain(), DataDomains.IT);
            case "BUDGET_ITEM"       -> labelOr(p.domain(), DataDomains.FINANCE);
            case "KEY_RESULT", "PROJECT" -> labelOr(p.domain(), DataDomains.STRATEGY);
            default                  -> DataDomains.of(p.domain(), p.title());
        };
    }

    private static String labelOr(String label, String fallback) {
        String d = DataDomains.normalize(label);
        return d != null ? d : fallback;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
