package io.orphos.eip.registry.component;

import io.orphos.eip.registry.entity.AgentRegistrationDoc;
import io.orphos.eip.registry.entity.McpServerDoc;
import io.orphos.eip.registry.repository.AgentRegistrationRepository;
import io.orphos.eip.registry.repository.McpServerRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Heartbeat poller for registered agents and MCP tool servers (TO-BE design §6). Mirrors the poller
 * in {@link PlatformServiceRegistry} — without it, an agent/MCP-server row kept whatever status it
 * had at registration, so the {@code /active} endpoints' "not DOWN" filter and the DEGRADED coverage
 * check never actually fired when a deployable went down. This closes that gap so a crashed agent or
 * tool server drops out of discovery and dependent agents degrade gracefully.
 */
@Component
public class RegistryHeartbeatPoller {

    private static final Logger log = LogManager.getLogger(RegistryHeartbeatPoller.class);

    private final AgentRegistrationRepository agents;
    private final McpServerRepository servers;
    private volatile HttpClient http;

    public RegistryHeartbeatPoller(AgentRegistrationRepository agents, McpServerRepository servers) {
        this.agents = agents;
        this.servers = servers;
    }

    /** Polls every registered agent + MCP server health URL and records UP/DOWN. Runs 1 min after startup, then every 60s. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void pollHealth() {
        for (AgentRegistrationDoc a : agents.findAll()) {
            if (a.getHealthUrl() == null || a.getHealthUrl().isBlank()) continue;
            String status = probe(a.getHealthUrl()) ? "UP" : "DOWN";
            String lastSeen = "UP".equals(status) ? Instant.now().toString() : a.getLastSeenAt();
            try {
                agents.updateStatus(a.getAgentId(), status, lastSeen);
            } catch (Exception e) {
                // Unlike a probe failure (expected, handled above as DOWN), this is the registry's
                // own datastore rejecting the write — a persistent problem would otherwise run
                // silently every 60s at DEBUG with nothing to alert an operator.
                log.warn("Agent heartbeat update failed for {}: {}", a.getAgentId(), e.getMessage());
            }
        }
        for (McpServerDoc s : servers.findAll()) {
            if (s.getHealthUrl() == null || s.getHealthUrl().isBlank()) continue;
            String status = probe(s.getHealthUrl()) ? "UP" : "DOWN";
            String lastSeen = "UP".equals(status) ? Instant.now().toString() : s.getLastSeenAt();
            try {
                servers.updateStatus(s.getServerId(), status, lastSeen);
            } catch (Exception e) {
                log.warn("MCP-server heartbeat update failed for {}: {}", s.getServerId(), e.getMessage());
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
}
