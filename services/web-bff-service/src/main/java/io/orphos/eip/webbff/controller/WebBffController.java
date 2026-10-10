package io.orphos.eip.webbff.controller;

import io.orphos.eip.webbff.client.CallerIdentity;
import io.orphos.eip.webbff.model.AggregateResponse;
import io.orphos.eip.webbff.model.DashboardQuery;
import io.orphos.eip.webbff.service.ScreenAggregator;
import io.orphos.eip.webbff.service.ScreenCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code /api/web/*} — one endpoint per dashboard screen, each collapsing that screen's
 * fan-out into a single browser round-trip.
 *
 * <p>Read-only by design. Writes stay pointed at the owning service: routing them through
 * a BFF would put an extra hop on the path that most needs a clear error and an accurate
 * status code, and would make this service a second place where write authorization has
 * to be reasoned about.</p>
 */
@RestController
@RequestMapping("/api/web")
public class WebBffController {

    private final ScreenAggregator aggregator;
    private final ScreenCatalog catalog;

    public WebBffController(ScreenAggregator aggregator, ScreenCatalog catalog) {
        this.aggregator = aggregator;
        this.catalog = catalog;
    }

    /** Liveness for the compose healthcheck; deliberately touches no downstream. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "web-bff");
    }

    /** Shell startup: plugins, widget layout, role aliases, access map, document count. */
    @GetMapping("/bootstrap")
    public AggregateResponse bootstrap(HttpServletRequest request) {
        return aggregator.aggregate(catalog.bootstrap(), CallerIdentity.from(request));
    }

    /**
     * Widget board. The caller names what its visible tiles read, so a hidden tile costs
     * nothing: {@code sources} from {@link ScreenCatalog#dashboardSourceNames()},
     * {@code persona} and {@code metrics} as comma-separated widget ids.
     */
    @GetMapping("/dashboard")
    public AggregateResponse dashboard(@RequestParam(required = false) String sources,
                                       @RequestParam(required = false) String persona,
                                       @RequestParam(required = false) String metrics,
                                       @RequestParam(required = false) String displayCurrency,
                                       HttpServletRequest request) {
        DashboardQuery query = DashboardQuery.parse(sources, persona, metrics, displayCurrency,
                catalog.dashboardSourceNames());
        return aggregator.aggregate(catalog.dashboard(query), CallerIdentity.from(request));
    }
}
