package io.orphos.eip.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.registry.vo.PlatformServiceDto;
import io.orphos.eip.registry.vo.RegisterServiceRequest;
import io.orphos.eip.registry.entity.PlatformService;
import io.orphos.eip.registry.repository.PlatformServiceRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Registry for platform <em>services</em> — the tier-model bases and other
 * backend capability providers (as opposed to {@link PluginRegistryService},
 * which handles Module-Federation UI plugins). Provides discovery (list live
 * bases + capabilities) and a heartbeat poller that marks each service UP/DOWN
 * so dependent agents can degrade gracefully (invariant I7, design §16).
 */
@Service
public class PlatformServiceRegistry {

    private static final Logger log = LogManager.getLogger(PlatformServiceRegistry.class);

    private final PlatformServiceRepository services;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile HttpClient http;

    public PlatformServiceRegistry(PlatformServiceRepository services) {
        this.services = services;
    }

    public List<PlatformServiceDto> findAll() {
        return services.findAll().stream().map(PlatformServiceDto::from).toList();
    }

    /** Upsert a service registration (self-registration at startup, or admin action). */
    public PlatformServiceDto register(RegisterServiceRequest req) {
        PlatformService s = services.findById(req.serviceId()).orElseGet(PlatformService::new);
        s.setServiceId(req.serviceId());
        s.setDisplayName(req.displayName());
        s.setTier(req.tier() != null && !req.tier().isBlank() ? req.tier() : "BASE");
        s.setVersion(req.version());
        s.setHealthUrl(req.healthUrl());
        s.setCapabilitiesJson(writeJson(req.capabilities()));
        s.setRegisteredAt(Instant.now());
        // status/lastSeenAt are owned by the heartbeat poller — leave as-is on re-register.
        if (s.getStatus() == null || s.getStatus().isBlank()) s.setStatus("UNKNOWN");
        return PlatformServiceDto.from(services.save(s));
    }

    // ── Heartbeat ────────────────────────────────────────────────────────────

    /**
     * Polls every registered service's health URL and records UP/DOWN. A DOWN
     * base is the signal a dependent agent uses to enter DEGRADED rather than
     * hard-fail. Runs a minute after startup, then every 60s.
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void pollHealth() {
        for (PlatformService s : services.findAll()) {
            if (s.getHealthUrl() == null || s.getHealthUrl().isBlank()) continue;
            String status = probe(s.getHealthUrl()) ? "UP" : "DOWN";
            String lastSeen = "UP".equals(status) ? Instant.now().toString() : s.getLastSeenAt();
            try {
                services.updateStatus(s.getServiceId(), status, lastSeen);
            } catch (Exception e) {
                // Unlike a probe failure (expected, handled above as DOWN), this is the registry's
                // own datastore rejecting the write — a persistent problem would otherwise run
                // silently every 60s at DEBUG with nothing to alert an operator.
                log.warn("Heartbeat status update failed for {}: {}", s.getServiceId(), e.getMessage());
            }
        }
    }

    private boolean probe(String healthUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> res = http().send(req, HttpResponse.BodyHandlers.discarding());
            return res.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                }
                client = http;
            }
        }
        return client;
    }

    private String writeJson(List<String> capabilities) {
        try {
            return mapper.writeValueAsString(capabilities == null ? List.of() : capabilities);
        } catch (Exception e) {
            return "[]";
        }
    }
}
