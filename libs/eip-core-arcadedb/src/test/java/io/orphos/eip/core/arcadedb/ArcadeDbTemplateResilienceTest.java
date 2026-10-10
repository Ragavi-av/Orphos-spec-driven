package io.orphos.eip.core.arcadedb;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the read/write-asymmetric retry rule described in {@link ArcadeDbTemplate}'s class
 * javadoc: a read may retry on any translated I/O or 5xx failure, but a write may only retry on a
 * failure proven to have happened before the request reached the server.
 */
class ArcadeDbTemplateResilienceTest {

    private static final ResponseEntity<String> OK = new ResponseEntity<>("{\"result\":[]}", HttpStatus.OK);

    private RestTemplate mockRest() {
        return mock(RestTemplate.class);
    }

    @SuppressWarnings("unchecked")
    private void stub(RestTemplate rest, Object... behaviors) {
        var stubbing = when(rest.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)));
        for (Object behavior : behaviors) {
            if (behavior instanceof Throwable t) {
                stubbing = stubbing.thenThrow(t);
            } else {
                stubbing = stubbing.thenReturn((ResponseEntity<String>) behavior);
            }
        }
    }

    private ArcadeDbProperties props() {
        ArcadeDbProperties p = new ArcadeDbProperties();
        p.setUrl("http://eip-arcadedb:2480");
        p.setDatabase("orphos");
        p.setUser("root");
        p.setPassword("secret");
        return p;
    }

    @Test
    void queryRetriesOnConnectFailureAndEventuallySucceeds() {
        RestTemplate rest = mockRest();
        stub(rest, new ResourceAccessException("connect failed", new ConnectException("refused")), OK);
        ArcadeDbTemplate template = new ArcadeDbTemplate(props(), rest);

        assertThat(template.query("SELECT FROM V", Map.of())).isEmpty();
        verify(rest, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void queryRetriesOn5xxAndEventuallySucceeds() {
        RestTemplate rest = mockRest();
        stub(rest, HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", new org.springframework.http.HttpHeaders(), new byte[0], null), OK);
        ArcadeDbTemplate template = new ArcadeDbTemplate(props(), rest);

        assertThat(template.query("SELECT FROM V", Map.of())).isEmpty();
        verify(rest, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void commandDoesNotRetryOn5xx() {
        RestTemplate rest = mockRest();
        stub(rest, HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", new org.springframework.http.HttpHeaders(), new byte[0], null), OK);
        ArcadeDbTemplate template = new ArcadeDbTemplate(props(), rest);

        // A 5xx means the server may already have applied the write — must fail immediately,
        // not retry into a possible duplicate.
        assertThatThrownBy(() -> template.command("INSERT INTO V SET x = 1", Map.of()))
                .isInstanceOf(io.orphos.eip.common.tenant.TenantDataUnavailableException.class);
        verify(rest, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void commandDoesNotRetryOnAmbiguousReadTimeout() {
        RestTemplate rest = mockRest();
        stub(rest, new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out")), OK);
        ArcadeDbTemplate template = new ArcadeDbTemplate(props(), rest);

        // A read-timeout after the request was sent is ambiguous (write may have already
        // committed with the response lost) — must not retry.
        assertThatThrownBy(() -> template.commandNoResult("INSERT INTO V SET x = 1", Map.of()))
                .isInstanceOf(io.orphos.eip.common.tenant.TenantDataUnavailableException.class);
        verify(rest, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void commandRetriesOnlyOnProvenPreRequestConnectFailure() {
        RestTemplate rest = mockRest();
        stub(rest, new ResourceAccessException("connect failed", new ConnectException("refused")), OK);
        ArcadeDbTemplate template = new ArcadeDbTemplate(props(), rest);

        // Connect-refused is proof the request never reached the server — safe to retry even
        // for a write.
        assertThat(template.command("INSERT INTO V SET x = 1", Map.of())).isEmpty();
        verify(rest, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }
}
