package io.orphos.eip.common.http;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;

/**
 * Factory for {@link RestTemplate} instances that are always timeout-bounded.
 *
 * <p>Injected as a bean by {@link EipHttpAutoConfiguration}, so no class in the
 * platform needs {@code new RestTemplate()} (which has no timeouts) or its own
 * {@code RestTemplateBuilder} boilerplate. Callers that are happy with the
 * configured defaults can inject the ready-made {@code eipRestTemplate} bean
 * instead of using this factory at all.</p>
 *
 * <h2>Choosing a variant</h2>
 * <p>{@link #create()} and friends keep RestTemplate's normal behaviour: a 4xx or
 * 5xx response throws {@code HttpStatusCodeException}. {@link #createLenient()}
 * and friends suppress that so the caller can inspect
 * {@code ResponseEntity.getStatusCode()} directly — which is what code ported
 * from {@code java.net.http.HttpClient} expects, since that client also returns
 * error responses rather than throwing.</p>
 */
public class EipRestTemplates {

    /**
     * Treats every response, including 4xx/5xx, as non-exceptional. Used by
     * {@code createLenient*} so ported code can branch on the status code the way
     * it did against {@code HttpResponse.statusCode()}.
     */
    private static final ResponseErrorHandler PASS_THROUGH = new ResponseErrorHandler() {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) {
            // Unreachable: hasError() never reports an error.
        }
    };

    private final EipHttpProperties props;

    public EipRestTemplates(EipHttpProperties props) {
        this.props = props;
    }

    /** A client using the configured {@code eip.http.*} timeouts; throws on 4xx/5xx. */
    public RestTemplate create() {
        return create(props.getConnectTimeout(), props.getReadTimeout());
    }

    /** As {@link #create()} but with a caller-chosen read timeout. */
    public RestTemplate create(Duration readTimeout) {
        return create(props.getConnectTimeout(), readTimeout);
    }

    /** As {@link #create()} but with both timeouts chosen by the caller. */
    public RestTemplate create(Duration connectTimeout, Duration readTimeout) {
        return new RestTemplateBuilder()
                .connectTimeout(connectTimeout == null ? props.getConnectTimeout() : connectTimeout)
                .readTimeout(readTimeout == null ? props.getReadTimeout() : readTimeout)
                .build();
    }

    /** A client using the configured timeouts that returns 4xx/5xx instead of throwing. */
    public RestTemplate createLenient() {
        return createLenient(props.getConnectTimeout(), props.getReadTimeout());
    }

    /** As {@link #createLenient()} but with a caller-chosen read timeout. */
    public RestTemplate createLenient(Duration readTimeout) {
        return createLenient(props.getConnectTimeout(), readTimeout);
    }

    /** As {@link #createLenient()} but with both timeouts chosen by the caller. */
    public RestTemplate createLenient(Duration connectTimeout, Duration readTimeout) {
        RestTemplate rest = create(connectTimeout, readTimeout);
        rest.setErrorHandler(PASS_THROUGH);
        return rest;
    }
}
