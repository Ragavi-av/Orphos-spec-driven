package io.orphos.eip.admin.onboarding.controller;

import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.WidgetProposal;
import io.orphos.eip.admin.onboarding.registry.TenantDataRegistry;
import io.orphos.eip.admin.onboarding.service.WidgetMetricsService;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serves the tenant's dynamic (data-driven) dashboard widgets and their live
 * values. Lives under {@code /api/dashboard/} (already proxied to admin-service),
 * alongside the user/admin layout endpoints.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardWidgetsController {

    private final TenantDataRegistry registry;
    private final WidgetMetricsService metrics;

    public DashboardWidgetsController(TenantDataRegistry registry, WidgetMetricsService metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    /** The tenant's dynamic widget descriptors (empty if no data has been onboarded). */
    @GetMapping("/widgets")
    public List<WidgetProposal> widgets() {
        return registry.listWidgets(TenantContextHolder.requireTenantId());
    }

    /** Live value + detail rows for one dynamic widget. */
    @GetMapping("/metrics/{widgetId}")
    public Map<String, Object> metric(@PathVariable String widgetId) {
        return metrics.compute(TenantContextHolder.requireTenantId(), widgetId);
    }
}
