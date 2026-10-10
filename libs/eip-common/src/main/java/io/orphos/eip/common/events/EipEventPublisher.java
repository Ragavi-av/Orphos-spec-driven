package io.orphos.eip.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.http.EipHttpHeaders;
import io.orphos.eip.common.http.EipRestTemplates;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Fire-and-forget client for the platform event-communication framework.
 *
 * <p>Services publish noteworthy activities (admin user granted, ingestion
 * failure, service outage, error-level log conditions) here; the events land
 * on admin-console-service's {@code POST /api/internal/events} endpoint, which
 * matches them against the per-tenant notification rules configured in the
 * Admin &rarr; Communications &rarr; Event Alerts tab and emails the configured
 * audience (platform admins, tenant admins, all users, or a specific role).</p>
 *
 * <p>Publishing must never break the business operation that emitted the
 * event: delivery is async with a short timeout and every failure is
 * swallowed after a WARN log. When the ingest URL or shared internal token is
 * not configured the publisher degrades to a no-op.</p>
 *
 * <p>Auth mirrors the existing service-to-service pattern (see
 * nl-query-service's InternalCompletionController): a shared
 * {@code X-Internal-Token} sourced from the {@code eip/internal-service#token}
 * secret.</p>
 *
 * <h2>Why there is an executor here</h2>
 * <p>{@link RestTemplate} is synchronous, so the "async" half of the contract
 * above is now this class's own responsibility &mdash; it used to come free from
 * {@code HttpClient.sendAsync}. One daemon thread drains a bounded queue: a
 * caller never blocks on the network, and if the queue fills (ingest down, or an
 * event storm) the event is dropped with a WARN rather than back-pressuring the
 * business path that emitted it. Dropping is the right trade-off here because
 * delivery was already best-effort by contract.</p>
 */
public class EipEventPublisher implements AutoCloseable {

    private static final Logger log = LogManager.getLogger(EipEventPublisher.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Bounded so a wedged ingest endpoint costs memory in kilobytes, not gigabytes. */
    private static final int QUEUE_CAPACITY = 256;

    /** Well-known event types understood by the default rule/template seeds. */
    public static final String USER_ADMIN_CREATED = "USER_ADMIN_CREATED";
    public static final String DOC_INGEST_FAILED  = "DOC_INGEST_FAILED";
    public static final String SERVICE_DOWN       = "SERVICE_DOWN";
    public static final String SERVICE_RECOVERED  = "SERVICE_RECOVERED";
    public static final String LOG_ALERT          = "LOG_ALERT";

    public static final String SEVERITY_INFO     = "INFO";
    public static final String SEVERITY_WARNING  = "WARNING";
    public static final String SEVERITY_CRITICAL = "CRITICAL";

    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ThreadPoolExecutor delivery;

    private final String ingestUrl;
    private final String token;
    private final String source;
    private final boolean enabled;

    public EipEventPublisher(EipRestTemplates restTemplates, String ingestUrl, String token,
                             String source, boolean enabled) {
        // Lenient: the status check in send() reports a rejection itself rather than
        // relying on an exception, exactly as it did against HttpResponse.statusCode().
        this.rest = restTemplates.createLenient(TIMEOUT, TIMEOUT);
        this.ingestUrl = ingestUrl == null ? "" : ingestUrl.trim();
        this.token = token == null ? "" : token.trim();
        this.source = source == null || source.isBlank() ? "unknown" : source.trim();
        this.enabled = enabled;
        this.delivery = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, "eip-event-publisher");
                    t.setDaemon(true);   // must never hold up JVM shutdown
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());   // turned into a WARN by publish()
        if (!isActive()) {
            log.info("EipEventPublisher inactive (enabled={}, url set={}, token set={}) - "
                    + "events will not be forwarded", enabled, !this.ingestUrl.isEmpty(), !this.token.isEmpty());
        }
    }

    public boolean isActive() {
        return enabled && !ingestUrl.isEmpty() && !token.isEmpty();
    }

    /**
     * Publishes a tenant-scoped event. Pass {@code tenantId == null} for
     * platform-scoped events (e.g. SERVICE_DOWN) &mdash; those are matched against
     * every tenant's rules by the ingest side.
     */
    public void publish(String eventType, String severity, String subject, String message,
                        String tenantId, Map<String, Object> attributes) {
        if (!isActive()) return;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("eventType", eventType);
            body.put("severity", severity == null ? SEVERITY_WARNING : severity);
            body.put("source", source);
            body.put("subject", subject == null ? eventType : subject);
            body.put("message", message == null ? "" : message);
            if (tenantId != null && !tenantId.isBlank()) body.put("tenantId", tenantId);
            body.put("attributes", attributes == null ? Map.of() : attributes);
            body.put("occurredAt", Instant.now().toString());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(EipHttpHeaders.INTERNAL_TOKEN, token);
            // Serialized on the calling thread so an unwritable payload fails here,
            // attributable to its caller, rather than later on the delivery thread.
            HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            String url = ingestUrl.replaceAll("/+$", "") + "/api/internal/events";

            delivery.execute(() -> send(url, entity, eventType, tenantId));
        } catch (RejectedExecutionException e) {
            log.warn("Event {} dropped: delivery queue full ({} pending)", eventType, delivery.getQueue().size());
        } catch (Exception e) {
            // Publishing is best-effort by contract - never propagate.
            log.warn("Event {} could not be published: {}", eventType, e.getMessage());
        }
    }

    /** Runs on the delivery thread; mirrors the branches of the old whenComplete() callback. */
    private void send(String url, HttpEntity<String> entity, String eventType, String tenantId) {
        try {
            ResponseEntity<String> resp = rest.postForEntity(url, entity, String.class);
            if (resp.getStatusCode().value() >= 300) {
                log.warn("Event {} rejected by ingest endpoint: HTTP {} {}",
                        eventType, resp.getStatusCode().value(), truncate(resp.getBody()));
            } else if (log.isDebugEnabled()) {
                log.debug("Event {} accepted (tenant={})", eventType, tenantId);
            }
        } catch (Exception e) {
            log.warn("Event {} not delivered to {}: {}", eventType, ingestUrl, e.getMessage());
        }
    }

    /**
     * Stops accepting new events and gives in-flight deliveries a moment to drain.
     * Spring calls this on context shutdown via destroy-method inference.
     */
    @Override
    public void close() {
        delivery.shutdown();
        try {
            if (!delivery.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                delivery.shutdownNow();
            }
        } catch (InterruptedException e) {
            delivery.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
