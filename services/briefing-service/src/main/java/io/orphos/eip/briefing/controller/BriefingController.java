package io.orphos.eip.briefing.controller;

import io.orphos.eip.briefing.service.DailyDigestService;
import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Raw briefing result sets for the widget board and briefing viewer. The rows are
 * filtered to the caller's persona exactly as the daily digest is
 * ({@link PersonaDomainPolicy}), so the board and the digest never disagree.
 */
@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

    private final DailyDigestService digest;
    private final AccessGrantSource grants;

    public BriefingController(DailyDigestService digest, AccessGrantSource grants) {
        this.digest = digest;
        this.grants = grants;
    }

    @GetMapping("/latest")
    public ResponseEntity<Object> getLatestBriefing() {
        String tenantId = TenantContextHolder.requireTenantId().toString();

        Map<String, Object> briefing = new HashMap<>();
        briefing.put("date", LocalDate.now().toString());
        briefing.put("generatedAt", java.time.Instant.now().toString());
        briefing.put("tenantId", tenantId);

        try {
            // Query text lives in BriefingDataService and the persona filter in
            // DailyDigestService, so the digest narrates exactly these rows.
            briefing.putAll(digest.latestSets(tenantId, PersonaDomainPolicy.current(grants)));
            briefing.put("status", "ok");
        } catch (Exception e) {
            briefing.put("status", "partial");
            briefing.put("error", e.getMessage());
        }

        return ResponseEntity.ok(briefing);
    }
}
