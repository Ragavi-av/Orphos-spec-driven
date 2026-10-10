package io.orphos.eip.dataingestion.vo;

import java.util.Map;

/**
 * The Camel exchange body for {@code direct:zohoFetchPage} — everything
 * {@code ZohoPageFetcher} needs to pull one page, mirroring the arguments
 * {@code ErpHttpClient.fetch(...)} already takes for the existing (non-batch)
 * Zoho extraction path in {@code RestSourceExtractor}.
 */
public record ZohoPageRequest(
        ErpConnection connection,
        ErpConfig.ErpObject object,
        int pageValue,
        String cursor,
        Map<String, String> extraQuery) {
}
