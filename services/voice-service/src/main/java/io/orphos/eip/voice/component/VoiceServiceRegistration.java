package io.orphos.eip.voice.component;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Self-registers this service with platform-registry so it appears in the
 * Capability Catalog and gets heartbeat-polled like every other base.
 *
 * <p>Registration is what makes voice DISCOVERABLE rather than merely running:
 * the registry's {@code RegistryHeartbeatPoller} then reports it UP or DOWN, and
 * the admin Voice tab reads that to tell an operator whether the module is
 * actually serving. Because voice is started and stopped by an admin at runtime
 * (see AdminServicesController), registration is retried rather than attempted
 * once — this container can come up before the registry is ready, and will come
 * up again every time voice is switched back on.</p>
 */
@Component
public class VoiceServiceRegistration {

    private static final Logger log = LogManager.getLogger(VoiceServiceRegistration.class);

    private final String registryUrl;
    private final String healthUrl;
    private final String internalToken;
    private final String version;
    private final HttpClient http;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public VoiceServiceRegistration(
            @Value("${voice.registry.url:http://eip-platform-registry:8093}") String registryUrl,
            @Value("${voice.self.health-url:http://eip-voice:8099/actuator/health}") String healthUrl,
            @Value("${eip.security.internal-service-token:}") String internalToken,
            @Value("${voice.self.version:1.0.0}") String version) {
        this.registryUrl = registryUrl == null ? "" : registryUrl.replaceAll("/+$", "");
        this.healthUrl = healthUrl;
        this.internalToken = internalToken;
        this.version = version;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnStartup() {
        attempt();
    }

    /**
     * Retry until it sticks. Cheap (one POST every two minutes, and none at all
     * once registered), and it removes an ordering constraint between this
     * container and the registry that neither can enforce on the other.
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 120_000)
    public void retryUntilRegistered() {
        if (!registered.get()) attempt();
    }

    private void attempt() {
        if (registryUrl.isEmpty()) return;
        String body = """
                {"serviceId":"voice-transcription",
                 "displayName":"Voice — Speech to Text",
                 "tier":"BASE",
                 "version":"%s",
                 "healthUrl":"%s",
                 "capabilities":["voice.transcribe","voice.status"]}
                """.formatted(version, healthUrl);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(registryUrl + "/api/platform/services/register"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (internalToken != null && !internalToken.isBlank()) {
                b.header("X-Internal-Token", internalToken);
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                registered.set(true);
                log.info("Registered 'voice-transcription' with platform-registry.");
            } else {
                log.warn("Registry rejected voice registration: HTTP {}", res.statusCode());
            }
        } catch (Exception e) {
            log.debug("Registry not reachable yet ({}) — will retry.", e.getMessage());
        }
    }
}
