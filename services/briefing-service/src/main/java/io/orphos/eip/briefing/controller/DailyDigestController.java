package io.orphos.eip.briefing.controller;

import io.orphos.eip.briefing.service.DailyDigestService;
import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Feeds the dashboard's greeting banner: today's headline plus the two or three
 * things that need the signed-in executive personally.
 *
 * <p>Separate from {@link BriefingController} because the contract differs —
 * /latest returns raw result sets for the widget board to reduce, this returns
 * text already written for display. Both read the same rows underneath.</p>
 */
@RestController
@RequestMapping("/api/briefing")
public class DailyDigestController {

    private final DailyDigestService digestService;
    private final AccessGrantSource grants;

    public DailyDigestController(DailyDigestService digestService, AccessGrantSource grants) {
        this.digestService = digestService;
        this.grants = grants;
    }

    /**
     * @param lang    UI language the prose should be written in ("en" / "ja")
     * @param refresh true to bypass the per-tenant, per-persona cache (header refresh button)
     */
    @GetMapping("/daily-digest")
    public ResponseEntity<Map<String, Object>> dailyDigest(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "refresh", required = false, defaultValue = "false") boolean refresh) {

        String tenantId = TenantContextHolder.requireTenantId().toString();
        // Each persona gets a digest of its own business domains (PersonaDomainPolicy).
        return ResponseEntity.ok(digestService.digest(tenantId, lang, refresh,
                PersonaDomainPolicy.current(grants)));
    }
}
