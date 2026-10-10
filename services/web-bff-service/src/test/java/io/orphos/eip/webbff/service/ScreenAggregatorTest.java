package io.orphos.eip.webbff.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.orphos.eip.webbff.client.CallerIdentity;
import io.orphos.eip.webbff.client.DownstreamGateway;
import io.orphos.eip.webbff.config.WebBffProperties;
import io.orphos.eip.webbff.model.AggregateResponse;
import io.orphos.eip.webbff.model.DownstreamCall;
import io.orphos.eip.webbff.model.SourceResult;
import io.orphos.eip.webbff.model.SourceStatus;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the three behaviours the dashboard depends on: the fan-out really is
 * concurrent, one dead downstream does not take the screen down, and a downstream
 * that never answers is bounded by the fan-out budget.
 */
class ScreenAggregatorTest {

    private static final CallerIdentity CALLER = new CallerIdentity("Bearer t", "tenant-1");

    private static final DownstreamCall CALL_A = new DownstreamCall("a", "http://a", "/a");
    private static final DownstreamCall CALL_B = new DownstreamCall("b", "http://b", "/b");

    private ScreenAggregator aggregator(DownstreamGateway gateway, Duration budget) {
        WebBffProperties props = new WebBffProperties();
        props.getFanOut().setTimeout(budget);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getFanOut().getPoolSize());
        executor.setMaxPoolSize(props.getFanOut().getPoolSize());
        executor.initialize();

        return new ScreenAggregator(gateway, executor, props);
    }

    @Test
    void collectsEverySourceUnderItsOwnKey() {
        DownstreamGateway gateway = mock(DownstreamGateway.class);
        when(gateway.fetch(eq(CALL_A), any()))
                .thenReturn(SourceResult.ok("a", JsonNodeFactory.instance.textNode("A")));
        when(gateway.fetch(eq(CALL_B), any()))
                .thenReturn(SourceResult.ok("b", JsonNodeFactory.instance.textNode("B")));

        AggregateResponse response =
                aggregator(gateway, Duration.ofSeconds(5)).aggregate(List.of(CALL_A, CALL_B), CALLER);

        assertThat(response.partial()).isFalse();
        assertThat(response.data().get("a").asText()).isEqualTo("A");
        assertThat(response.data().get("b").asText()).isEqualTo("B");
        assertThat(response.sources().get("a").status()).isEqualTo(SourceStatus.OK);
    }

    @Test
    void oneFailingSourceDegradesRatherThanFailsTheScreen() {
        DownstreamGateway gateway = mock(DownstreamGateway.class);
        when(gateway.fetch(eq(CALL_A), any()))
                .thenReturn(SourceResult.ok("a", JsonNodeFactory.instance.textNode("A")));
        when(gateway.fetch(eq(CALL_B), any()))
                .thenReturn(SourceResult.failed("b", SourceStatus.UNAVAILABLE, "ResourceAccessException"));

        AggregateResponse response =
                aggregator(gateway, Duration.ofSeconds(5)).aggregate(List.of(CALL_A, CALL_B), CALLER);

        assertThat(response.partial()).isTrue();
        assertThat(response.data().get("a")).isNotNull();
        assertThat(response.data().get("b")).isNull();
        assertThat(response.sources().get("b").status()).isEqualTo(SourceStatus.UNAVAILABLE);
        assertThat(response.sources().get("b").detail()).isEqualTo("ResourceAccessException");
    }

    @Test
    void runsCallsConcurrentlyRatherThanInSequence() throws Exception {
        // Each call blocks until both have started; only a concurrent fan-out can release it.
        CountDownLatch bothStarted = new CountDownLatch(2);
        DownstreamGateway gateway = mock(DownstreamGateway.class);
        when(gateway.fetch(any(), any())).thenAnswer(invocation -> {
            DownstreamCall call = invocation.getArgument(0);
            bothStarted.countDown();
            if (!bothStarted.await(2, TimeUnit.SECONDS)) {
                return SourceResult.failed(call.name(), SourceStatus.UNAVAILABLE, "not concurrent");
            }
            return SourceResult.ok(call.name(), JsonNodeFactory.instance.textNode("ok"));
        });

        AggregateResponse response =
                aggregator(gateway, Duration.ofSeconds(5)).aggregate(List.of(CALL_A, CALL_B), CALLER);

        assertThat(response.partial()).isFalse();
    }

    @Test
    void boundsTheScreenByTheFanOutBudget() {
        DownstreamGateway gateway = mock(DownstreamGateway.class);
        when(gateway.fetch(eq(CALL_A), any()))
                .thenReturn(SourceResult.ok("a", JsonNodeFactory.instance.textNode("A")));
        when(gateway.fetch(eq(CALL_B), any())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return SourceResult.ok("b", JsonNodeFactory.instance.textNode("B"));
        });

        long start = System.nanoTime();
        AggregateResponse response =
                aggregator(gateway, Duration.ofMillis(300)).aggregate(List.of(CALL_A, CALL_B), CALLER);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(3_000);
        assertThat(response.partial()).isTrue();
        assertThat(response.data().get("a")).isNotNull();
        assertThat(response.sources().get("b").status()).isEqualTo(SourceStatus.UNAVAILABLE);
        assertThat(response.sources().get("b").detail()).isEqualTo("timed out");
    }
}
