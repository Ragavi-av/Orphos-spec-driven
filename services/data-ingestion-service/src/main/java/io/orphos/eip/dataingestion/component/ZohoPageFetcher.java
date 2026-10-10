package io.orphos.eip.dataingestion.component;

import io.orphos.eip.dataingestion.vo.ZohoPageRequest;
import org.springframework.stereotype.Component;

/**
 * The single step the Camel route ({@link ZohoCamelRoute}) delegates to: fetch one page.
 * Deliberately thin — all the Zoho-specific work (OAuth2 refresh-token auth, pagination-style
 * query params, dotted-path JSON extraction) already lives in {@link ErpHttpClient} and is reused
 * as-is, exactly as the existing (non-batch) {@code RestSourceExtractor} path uses it. Camel's
 * contribution here is the route around this call (see {@link ZohoCamelRoute}'s redelivery
 * policy), not a reimplementation of the HTTP/auth mechanics.
 */
@Component
public class ZohoPageFetcher {

    private final ErpHttpClient http;

    public ZohoPageFetcher(ErpHttpClient http) {
        this.http = http;
    }

    /** Invoked by the Camel route via {@code .bean(ZohoPageFetcher.class, "fetchPage")}. */
    public ErpHttpClient.Page fetchPage(ZohoPageRequest req) {
        return http.fetch(req.connection(), req.object(), req.pageValue(), req.cursor(), req.extraQuery());
    }
}
