package io.orphos.eip.agent.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.agent.spec.AgentInvocation;
import io.orphos.eip.agent.util.OrchestratorProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Routes an {@link AgentInvocation} to a standalone agent's {@code /invoke} endpoint
 * (TO-BE design §7, §8). Fire-and-log for the scheduled read-only slice — the agent does its own
 * MCP tool calls via the runtime gateway and returns a suggestion result.
 */
@Component
public class AgentInvoker {

    private static final Logger log = LogManager.getLogger(AgentInvoker.class);

    private final OrchestratorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile HttpClient http;

    public AgentInvoker(OrchestratorProperties props) {
        this.props = props;
    }

    private HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                }
                client = http;
            }
        }
        return client;
    }

    /** POST the invocation to {@code baseUrl + /invoke}. Returns true on a 2xx. */
    public boolean invoke(String baseUrl, AgentInvocation invocation) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Agent '{}' has no baseUrl — cannot invoke.", invocation.agentId());
            return false;
        }
        try {
            String body = mapper.writeValueAsString(invocation);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(baseUrl) + "/invoke"))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", props.getInternalToken())
                    .header("X-Tenant-Id", invocation.tenantId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 == 2) {
                log.info("Invoked agent '{}' for tenant {} (correlation {}).",
                        invocation.agentId(), invocation.tenantId(), invocation.correlationId());
                return true;
            }
            log.warn("Agent '{}' /invoke returned {}: {}", invocation.agentId(), res.statusCode(), res.body());
            return false;
        } catch (Exception e) {
            log.warn("Agent '{}' /invoke failed: {}", invocation.agentId(), e.getMessage());
            return false;
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
