package io.orphos.eip.admin.component;

import io.orphos.eip.admin.service.EventNotificationService;
import io.orphos.eip.admin.util.EventCatalog;
import io.orphos.eip.admin.vo.EventDtos;
import io.orphos.eip.common.http.EipRestTemplates;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls the {@code /actuator/health} endpoints of the configured services and
 * emits SERVICE_DOWN / SERVICE_RECOVERED events on state transitions.
 *
 * <p>Events are platform-scoped (no tenantId), so they reach every tenant
 * that enabled a rule for them — typically a PLATFORM_ADMIN or TENANT_ADMIN
 * audience. Only transitions alert: a service that stays down does not
 * re-alert every poll (a repeat notice is sent after
 * {@code admin.events.health.remind-after} while still down, so a prolonged
 * outage isn't a single easily-missed email).</p>
 *
 * <p>Targets are configured as a comma-separated list of
 * {@code name=healthUrl} pairs; blank disables monitoring entirely.</p>
 */
@Component
public class ServiceHealthMonitor {

    private static final Logger log = LogManager.getLogger(ServiceHealthMonitor.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final EventNotificationService events;
    private final Map<String, String> targets;   // name -> health URL
    private final Duration remindAfter;

    /** name -> last observed UP state (absent = not yet polled, assumed UP). */
    private final Map<String, Boolean> lastUp = new ConcurrentHashMap<>();
    /** name -> when we last alerted for the ongoing outage. */
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    private final RestTemplate rest;

    public ServiceHealthMonitor(
            EipRestTemplates restTemplates,
            EventNotificationService events,
            @Value("${admin.events.health.targets:}") String targetsSpec,
            @Value("${admin.events.health.remind-after:PT6H}") Duration remindAfter) {
        // Lenient is essential, not a preference: a Spring Boot actuator health
        // endpoint answers 503 when the service is DOWN, which is precisely the
        // case this monitor exists to observe. A throwing RestTemplate would push
        // every real outage into the catch block below and report the exception
        // class instead of "HTTP 503".
        this.rest = restTemplates.createLenient(HTTP_TIMEOUT, HTTP_TIMEOUT);
        this.events = events;
        this.targets = parseTargets(targetsSpec);
        this.remindAfter = remindAfter;
        if (this.targets.isEmpty()) {
            log.info("Service health monitor disabled — no admin.events.health.targets configured");
        } else {
            log.info("Service health monitor watching {} services: {}",
                    this.targets.size(), this.targets.keySet());
        }
    }

    @Scheduled(
            fixedDelayString = "${admin.events.health.interval:PT60S}",
            initialDelayString = "${admin.events.health.initial-delay:PT120S}")
    public void poll() {
        for (Map.Entry<String, String> t : targets.entrySet()) {
            String name = t.getKey();
            String url = t.getValue();
            String detail = "";
            boolean up;
            try {
                // A URI, not a String: RestTemplate would treat a String as a URI
                // template and try to expand any braces in an operator-supplied URL.
                ResponseEntity<String> resp = rest.getForEntity(URI.create(url), String.class);
                String body = resp.getBody() == null ? "" : resp.getBody();
                up = resp.getStatusCode().value() == 200 && !body.contains("\"DOWN\"");
                if (!up) detail = "HTTP " + resp.getStatusCode().value();
            } catch (Exception e) {
                up = false;
                detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            transition(name, url, up, detail);
        }
    }

    private void transition(String name, String url, boolean up, String detail) {
        boolean wasUp = lastUp.getOrDefault(name, true);
        lastUp.put(name, up);

        if (up && !wasUp) {
            lastAlertAt.remove(name);
            log.info("Service {} recovered", name);
            emit(EventCatalog.SERVICE_RECOVERED, "INFO", name, url,
                    "Service '" + name + "' is reachable again.", detail);
            return;
        }
        if (!up) {
            Instant lastAlert = lastAlertAt.get(name);
            boolean firstAlert = wasUp || lastAlert == null;
            boolean remind = lastAlert != null && Instant.now().isAfter(lastAlert.plus(remindAfter));
            if (firstAlert || remind) {
                lastAlertAt.put(name, Instant.now());
                log.warn("Service {} is DOWN ({})", name, detail);
                emit(EventCatalog.SERVICE_DOWN, "CRITICAL", name, url,
                        "Service '" + name + "' failed its health check"
                                + (remind ? " (still down — reminder)." : "."), detail);
            }
        }
    }

    private void emit(String eventType, String severity, String name, String url,
                      String message, String detail) {
        try {
            events.dispatch(new EventDtos.EventIngestRequest(
                    eventType, severity, "admin-console-service",
                    "Service health: " + name, message,
                    null, // platform-scoped: fan out to all subscribed tenants
                    Map.of("serviceName", name, "healthUrl", url, "detail", detail == null ? "" : detail),
                    Instant.now().toString()));
        } catch (Exception e) {
            log.error("Health event {} for {} not dispatched: {}", eventType, name, e.getMessage());
        }
    }

    private static Map<String, String> parseTargets(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) return out;
        for (String pair : spec.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) continue;
            out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return out;
    }
}
