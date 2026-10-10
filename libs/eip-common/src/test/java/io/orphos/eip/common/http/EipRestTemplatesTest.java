package io.orphos.eip.common.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The two guarantees callers rely on: every client is timeout-bounded, and the
 * lenient variant reports error statuses instead of throwing (which is what code
 * ported off {@code java.net.http.HttpClient} expects).
 */
class EipRestTemplatesTest {

    private final EipRestTemplates factory = new EipRestTemplates(new EipHttpProperties());

    @Test
    @DisplayName("a default client carries a request factory, never the timeout-less bare RestTemplate")
    void defaultClientIsConfigured() {
        RestTemplate rest = factory.create();
        assertThat(rest.getRequestFactory()).isNotNull();
    }

    @Test
    @DisplayName("caller-supplied timeouts are honoured, and null falls back to the configured default")
    void callerTimeoutsAreAccepted() {
        assertThat(factory.create(Duration.ofSeconds(1), Duration.ofSeconds(2))).isNotNull();
        assertThat(factory.create(null, null)).isNotNull();
    }

    @Test
    @DisplayName("the standard client throws on 5xx")
    void standardClientThrowsOnServerError() {
        RestTemplate rest = factory.create();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("/boom")).andRespond(withServerError());

        assertThatThrownBy(() -> rest.getForEntity("/boom", String.class))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    @DisplayName("the lenient client hands back the 5xx response instead of throwing")
    void lenientClientReturnsServerError() {
        RestTemplate rest = factory.createLenient();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("/boom")).andRespond(withServerError().body("upstream exploded"));

        ResponseEntity<String> res = rest.getForEntity("/boom", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).isEqualTo("upstream exploded");
    }

    @Test
    @DisplayName("the lenient client still passes 2xx through untouched")
    void lenientClientReturnsSuccess() {
        RestTemplate rest = factory.createLenient(Duration.ofSeconds(5));
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("/ok")).andRespond(withSuccess().body("fine"));

        assertThat(rest.getForEntity("/ok", String.class).getBody()).isEqualTo("fine");
    }
}
