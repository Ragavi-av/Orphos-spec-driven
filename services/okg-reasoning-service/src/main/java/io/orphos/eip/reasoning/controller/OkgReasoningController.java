package io.orphos.eip.reasoning.controller;

import io.orphos.eip.reasoning.service.InsightsQueryService;
import io.orphos.eip.reasoning.service.InsightsQueryService.Insight;
import io.orphos.eip.reasoning.service.ReasoningService;
import io.orphos.eip.reasoning.service.ReasoningService.CycleResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Tenant-facing reasoning insights (any authenticated tenant user may read
 * their own tenant's derived facts — no admin role required, matching the
 * pattern for other read endpoints like onboarding's dynamic widget data).
 */
@RestController
@RequestMapping("/api/okg-reasoning")
public class OkgReasoningController {

    private final InsightsQueryService insights;
    private final ReasoningService reasoning;

    public OkgReasoningController(InsightsQueryService insights, ReasoningService reasoning) {
        this.insights = insights;
        this.reasoning = reasoning;
    }

    /** All derived facts (overdue issues, blocked issues, overloaded people) for the current tenant. */
    @GetMapping("/insights")
    public Map<String, List<Insight>> insights() {
        return insights.currentTenantInsightsByType();
    }

    /** Admin-triggered on-demand reasoning cycle — useful right after a Jira sync, before the next scheduled tick. */
    @PostMapping("/admin/run")
    @PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin') or hasAuthority('ROLE_service_internal')")
    public CycleResult triggerRun() {
        return reasoning.reasonForCurrentTenant();
    }
}
