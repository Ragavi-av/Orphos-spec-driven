package io.orphos.eip.dataingestion.component;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * The Camel route {@code ZohoItemReader} calls (via {@code ProducerTemplate}) once per page.
 *
 * <p>This is Camel's actual contribution to the Zoho batch pipeline: a declarative
 * redelivery/retry policy around the page fetch, distinct from — and not layered on top of —
 * this codebase's {@code EipCircuitBreakers}/{@code EipRetries} (those guard other services'
 * internal HTTP calls; this route is the dedicated integration layer for Zoho specifically, per
 * the "Apache Camel integration" ask). The actual HTTP/OAuth work is delegated to
 * {@link ZohoPageFetcher}, which reuses the existing {@link ErpHttpClient} — the route does not
 * reimplement Zoho's pagination or auth.</p>
 */
@Component
public class ZohoCamelRoute extends RouteBuilder {

    /** The endpoint {@code ZohoItemReader} sends a {@code ZohoPageRequest} to per page. */
    public static final String FETCH_PAGE_ENDPOINT = "direct:zohoFetchPage";

    @Override
    public void configure() {
        // Transient failures (Zoho rate-limiting, a network blip) get 2 retries with backoff at
        // the route level before the exception reaches the ItemReader/Step. Not handled=true —
        // once redeliveries are exhausted the exception still propagates, so Spring Batch's own
        // step failure/skip handling (see ZohoBatchConfig) sees it.
        onException(Exception.class)
                .maximumRedeliveries(2)
                .redeliveryDelay(500)
                .backOffMultiplier(2.0)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .log(LoggingLevel.WARN, "Zoho page fetch attempt failed: ${exception.message}")
                .handled(false);

        from(FETCH_PAGE_ENDPOINT)
                .routeId("zoho-fetch-page")
                .bean(ZohoPageFetcher.class, "fetchPage");
    }
}
