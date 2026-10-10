package io.orphos.eip.webbff.service;

import io.orphos.eip.webbff.config.WebBffProperties;
import io.orphos.eip.webbff.model.DashboardQuery;
import io.orphos.eip.webbff.model.DownstreamCall;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Declares which downstream calls make up each dashboard screen.
 *
 * <p>Kept apart from the controller so a screen's composition is data, not control flow:
 * adding a panel is one entry in a list, and the whole egress surface of the BFF can be
 * read off this one file. Every path here is a route the shell would otherwise call
 * directly — this BFF changes how many round-trips that takes, not what is fetched.</p>
 */
@Component
public class ScreenCatalog {

    /** Aggregate key prefix for an ontology-declared widget, e.g. {@code persona:revenue}. */
    public static final String PERSONA_PREFIX = "persona:";

    /** Aggregate key prefix for a data-onboarded widget, e.g. {@code metric:w-42}. */
    public static final String METRIC_PREFIX = "metric:";

    private final WebBffProperties.Downstream downstream;

    /** The widget board's fixed sources, by aggregate key. Insertion order is response order. */
    private final Map<String, Function<String, DownstreamCall>> dashboardSources = new LinkedHashMap<>();

    public ScreenCatalog(WebBffProperties props) {
        this.downstream = props.getDownstream();

        // One entry per source the tiles read, NOT per tile: briefing/latest alone feeds
        // the budget, incident and system-health tiles, which used to fetch it four times.
        dashboardSources.put("briefing", currency -> new DownstreamCall("briefing",
                downstream.getBriefing(), "/api/briefing/latest"));
        dashboardSources.put("sla", currency -> new DownstreamCall("sla",
                downstream.getPriorityEngine(), "/api/priority/sla"));
        dashboardSources.put("decisions", currency -> new DownstreamCall("decisions",
                downstream.getDecisionLog(), "/api/decisions"));
        dashboardSources.put("alerts", currency -> new DownstreamCall("alerts",
                downstream.getNotification(), "/api/alerts"));
        // The zenitus summaries take the reader's currency for their headline totals only;
        // breakdown rows stay in the reported unit, so the drill-downs can share this call.
        dashboardSources.put("hrSummary", currency -> new DownstreamCall("hrSummary",
                downstream.getBriefing(), withCurrency("/api/zenitus/hr/summary", currency)));
        dashboardSources.put("cashflow", currency -> new DownstreamCall("cashflow",
                downstream.getBriefing(), withCurrency("/api/zenitus/cashflow/summary", currency)));
    }

    /**
     * Shell startup: everything the authenticated shell reads before it can lay out the
     * page — active plugins (tabs), the reader's widget layout, role display aliases, the
     * dashboard access map and the corpus size under the co-pilot dock.
     *
     * <p>Branding is deliberately absent: it is fetched unauthenticated, before login, so
     * the sign-in screen is already themed, and cannot wait on a JWT-gated call.</p>
     */
    public List<DownstreamCall> bootstrap() {
        return List.of(
                new DownstreamCall("plugins", downstream.getPlatformRegistry(),
                        "/api/platform/plugins/active"),
                new DownstreamCall("dashboardLayout", downstream.getWidgets(),
                        "/api/dashboard/layout/me"),
                new DownstreamCall("roleAliases", downstream.getPlatformCore(),
                        "/api/roles/aliases"),
                new DownstreamCall("accessGrants", downstream.getPlatformCore(),
                        "/api/access/grants"),
                new DownstreamCall("documentCount", downstream.getDocIngest(),
                        "/api/doc-ingest/documents/count"));
    }

    /** The fixed source names {@link #dashboard} accepts in {@code sources}. */
    public Set<String> dashboardSourceNames() {
        return dashboardSources.keySet();
    }

    /**
     * The widget board: the fixed sources the visible tiles read, plus one call per
     * ontology-declared and data-onboarded widget on the board.
     */
    public List<DownstreamCall> dashboard(DashboardQuery query) {
        List<DownstreamCall> calls = new ArrayList<>();
        for (String source : query.sources()) {
            calls.add(dashboardSources.get(source).apply(query.displayCurrency()));
        }
        for (String id : query.personaIds()) {
            calls.add(new DownstreamCall(PERSONA_PREFIX + id, downstream.getWidgets(),
                    withCurrency("/api/dashboard/persona-metrics/" + id, query.displayCurrency())));
        }
        for (String id : query.metricIds()) {
            calls.add(new DownstreamCall(METRIC_PREFIX + id, downstream.getPlatformCore(),
                    "/api/dashboard/metrics/" + id));
        }
        return calls;
    }

    /** Appends {@code displayCurrency} when the reader picked one; no parameter otherwise. */
    private static String withCurrency(String path, String currency) {
        if (currency == null) {
            return path;
        }
        return UriComponentsBuilder.fromPath(path)
                .queryParam("displayCurrency", currency)
                .build()
                .toUriString();
    }
}
