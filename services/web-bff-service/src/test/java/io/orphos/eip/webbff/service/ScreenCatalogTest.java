package io.orphos.eip.webbff.service;

import io.orphos.eip.webbff.config.WebBffProperties;
import io.orphos.eip.webbff.model.DashboardQuery;
import io.orphos.eip.webbff.model.DownstreamCall;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dashboard screen is built from browser-supplied ids, so these cover the two
 * things that matter: the board gets exactly the calls it asked for, and nothing the
 * browser sends reaches a downstream URL unvalidated.
 */
class ScreenCatalogTest {

    private final ScreenCatalog catalog = new ScreenCatalog(new WebBffProperties());

    private DashboardQuery parse(String sources, String persona, String metrics, String currency) {
        return DashboardQuery.parse(sources, persona, metrics, currency, catalog.dashboardSourceNames());
    }

    @Test
    void fetchesOnlyTheRequestedSources() {
        List<DownstreamCall> calls = catalog.dashboard(parse("sla,briefing", null, null, null));

        assertThat(calls).extracting(DownstreamCall::name).containsExactly("sla", "briefing");
        assertThat(calls.get(0).path()).isEqualTo("/api/priority/sla");
    }

    @Test
    void addsOneCallPerPersonaAndMetricWidget() {
        List<DownstreamCall> calls = catalog.dashboard(parse(null, "revenue,ebitda", "w-42", null));

        assertThat(calls).extracting(DownstreamCall::name)
                .containsExactly("persona:revenue", "persona:ebitda", "metric:w-42");
        assertThat(calls.get(0).baseUrl()).isEqualTo("http://eip-widgets:8095");
        assertThat(calls.get(2).path()).isEqualTo("/api/dashboard/metrics/w-42");
    }

    @Test
    void forwardsTheDisplayCurrencyOnlyToMoneySources() {
        List<DownstreamCall> calls = catalog.dashboard(parse("cashflow,sla", "revenue", null, "JPY"));

        assertThat(calls.get(0).path()).isEqualTo("/api/zenitus/cashflow/summary?displayCurrency=JPY");
        assertThat(calls.get(1).path()).isEqualTo("/api/priority/sla");
        assertThat(calls.get(2).path()).isEqualTo("/api/dashboard/persona-metrics/revenue?displayCurrency=JPY");
    }

    @Test
    void collapsesDuplicateIds() {
        List<DownstreamCall> calls = catalog.dashboard(parse("sla,sla", "revenue,revenue", null, null));

        assertThat(calls).extracting(DownstreamCall::name).containsExactly("sla", "persona:revenue");
    }

    @Test
    void rejectsAnUnknownSource() {
        assertThatThrownBy(() -> parse("sla,secrets", null, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsAnIdThatCouldEscapeItsPath() {
        assertThatThrownBy(() -> parse(null, "../../admin", null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> parse(null, null, "a?x=1", null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsAMalformedCurrency() {
        assertThatThrownBy(() -> parse("cashflow", null, null, "usd&x=1"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void capsTheFanOut() {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i <= DashboardQuery.MAX_CALLS; i++) {
            ids.append("w").append(i).append(',');
        }
        assertThatThrownBy(() -> parse(null, null, ids.toString(), null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
