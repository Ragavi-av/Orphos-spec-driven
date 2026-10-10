package io.orphos.eip.webbff.service;

import io.orphos.eip.webbff.client.CallerIdentity;
import io.orphos.eip.webbff.client.DownstreamGateway;
import io.orphos.eip.webbff.config.WebBffProperties;
import io.orphos.eip.webbff.model.AggregateResponse;
import io.orphos.eip.webbff.model.DownstreamCall;
import io.orphos.eip.webbff.model.SourceResult;
import io.orphos.eip.webbff.model.SourceStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a screen's downstream calls in parallel and assembles the response.
 *
 * <p>Parallel rather than sequential because a screen's latency is what the dashboard
 * feels: five sequential 200ms calls is a second of blank panels, the same five in
 * parallel is 200ms. Each call is independent — no screen feeds one downstream's output
 * into another's input — so there is nothing to order them for.</p>
 */
@Service
public class ScreenAggregator {

    private static final Logger log = LogManager.getLogger(ScreenAggregator.class);

    private final DownstreamGateway gateway;
    private final Executor executor;
    private final long timeoutMillis;

    public ScreenAggregator(DownstreamGateway gateway,
                            @Qualifier("webBffFanOutExecutor") ThreadPoolTaskExecutor executor,
                            WebBffProperties props) {
        this.gateway = gateway;
        this.executor = executor;
        this.timeoutMillis = props.getFanOut().getTimeout().toMillis();
    }

    /**
     * Fetches every call concurrently and returns once all have settled or the fan-out
     * budget expires, whichever comes first.
     *
     * <p>The budget is applied to the batch as a whole rather than per call: the client is
     * waiting on the slowest one, so that is the number worth bounding. A call still running
     * when the budget expires is reported UNAVAILABLE and abandoned — its thread is released
     * by the underlying read timeout, which is always the shorter leash of the two in a
     * correctly configured deployment.</p>
     */
    public AggregateResponse aggregate(List<DownstreamCall> calls, CallerIdentity caller) {
        List<CompletableFuture<SourceResult>> futures = new ArrayList<>(calls.size());
        for (DownstreamCall call : calls) {
            futures.add(CompletableFuture.supplyAsync(() -> gateway.fetch(call, caller), executor));
        }

        // Wait once on the combined future so the budget covers the batch, not each call.
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("fan-out exceeded {}ms budget; returning partial aggregate", timeoutMillis);
        } catch (InterruptedException e) {
            // Restore the flag: the container, not this method, decides what to do about it.
            Thread.currentThread().interrupt();
            log.warn("fan-out interrupted; returning partial aggregate");
        } catch (Exception e) {
            // gateway.fetch never throws, so this is a pool-level failure, not a downstream one.
            log.error("fan-out failed unexpectedly; returning partial aggregate", e);
        }

        List<SourceResult> results = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            results.add(settled(calls.get(i), futures.get(i)));
        }
        return AggregateResponse.of(results);
    }

    /** The call's result if it finished, otherwise an UNAVAILABLE placeholder. */
    private SourceResult settled(DownstreamCall call, CompletableFuture<SourceResult> future) {
        if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
            return SourceResult.failed(call.name(), SourceStatus.UNAVAILABLE, "timed out");
        }
        // Safe: isDone() and not exceptional, so this returns immediately without blocking.
        return future.join();
    }
}
