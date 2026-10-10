package io.orphos.eip.webbff.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.orphos.eip.common.http.EipHttpHeaders;
import io.orphos.eip.webbff.model.DownstreamCall;
import io.orphos.eip.webbff.model.SourceResult;
import io.orphos.eip.webbff.model.SourceStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/**
 * Executes one downstream GET and classifies the outcome.
 *
 * <p>Never throws: a downstream that is slow, broken or refusing the caller becomes a
 * non-OK {@link SourceResult}, because one failing tile must not fail the whole screen.
 * Failures are logged here — the only place with the URL — and reduced to a short
 * message before they reach the browser.</p>
 */
@Component
public class DownstreamGateway {

    private static final Logger log = LogManager.getLogger(DownstreamGateway.class);

    private final RestTemplate http;

    public DownstreamGateway(@Qualifier("webBffRestTemplate") RestTemplate http) {
        this.http = http;
    }

    public SourceResult fetch(DownstreamCall call, CallerIdentity caller) {
        String url = call.baseUrl() + call.path();
        try {
            // A URI, not a String: RestTemplate treats a String as a template and would
            // re-encode the already-built query string.
            ResponseEntity<JsonNode> response = http.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(headers(caller)), JsonNode.class);

            HttpStatusCode status = response.getStatusCode();
            if (status.is2xxSuccessful()) {
                return SourceResult.ok(call.name(), response.getBody());
            }
            log.warn("downstream {} returned {} for GET {}", call.name(), status.value(), url);
            return SourceResult.failed(call.name(), classify(status),
                    "downstream responded " + status.value());

        } catch (Exception e) {
            // Transport-level: connect refused, DNS, read timeout, malformed body.
            log.warn("downstream {} failed for GET {}", call.name(), url, e);
            return SourceResult.failed(call.name(), SourceStatus.UNAVAILABLE,
                    e.getClass().getSimpleName());
        }
    }

    private HttpHeaders headers(CallerIdentity caller) {
        HttpHeaders headers = new HttpHeaders();
        if (!caller.authorization().isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, caller.authorization());
        }
        if (!caller.tenantId().isBlank()) {
            headers.set(EipHttpHeaders.TENANT_ID, caller.tenantId());
        }
        return headers;
    }

    private SourceStatus classify(HttpStatusCode status) {
        if (status.value() == 401 || status.value() == 403) {
            return SourceStatus.FORBIDDEN;
        }
        if (status.value() == 404) {
            return SourceStatus.NOT_FOUND;
        }
        return SourceStatus.UNAVAILABLE;
    }
}
