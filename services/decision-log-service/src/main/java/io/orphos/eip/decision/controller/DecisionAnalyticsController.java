package io.orphos.eip.decision.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.decision.service.DecisionAnalyticsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** Decision_Analytics_Dashboard API (Req 8): aggregates + CSV/JSON export. */
@RestController
@RequestMapping("/api/decisions/analytics")
public class DecisionAnalyticsController {

    private final DecisionAnalyticsService analytics;

    public DecisionAnalyticsController(DecisionAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    public ResponseEntity<Object> analytics() {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        return ResponseEntity.ok(analytics.analytics(tenantId));
    }

    /** {@code ?format=csv} downloads the projection for Excel; {@code json} returns the aggregates (Req 8.4). */
    @GetMapping("/export")
    public ResponseEntity<Object> export(@RequestParam(defaultValue = "csv") String format) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=decision-analytics.json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(analytics.analytics(tenantId));
        }
        byte[] csv = analytics.exportCsv(tenantId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=decisions.csv")
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv);
    }
}
