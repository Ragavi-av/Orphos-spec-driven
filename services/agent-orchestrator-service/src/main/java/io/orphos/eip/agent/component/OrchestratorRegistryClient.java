package io.orphos.eip.agent.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.agent.spec.Trigger;
import io.orphos.eip.agent.util.OrchestratorProperties;
import io.orphos.eip.agent.vo.DiscoveredAgent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the agent registry for the orchestrator (TO-BE design §4: the orchestrator depends only on
 * the registry + contracts, never on a concrete agent). TTL-free per-scan read, matching the
 * registry's own discovery pattern.
 */
@Component
public class OrchestratorRegistryClient {

    private static final Logger log = LogManager.getLogger(OrchestratorRegistryClient.class);

    private final OrchestratorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile HttpClient http;

    public OrchestratorRegistryClient(OrchestratorProperties props) {
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

    /** Agents live (enabled + not DOWN) for {@code tenantId}. */
    public List<DiscoveredAgent> activeAgents(UUID tenantId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base() + "/api/platform/agents/active"))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Internal-Token", props.getInternalToken())
                    .header("X-Tenant-Id", tenantId.toString())
                    .GET()
                    .build();
            HttpResponse<String> res = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                log.warn("Agent discovery for tenant {} returned {}", tenantId, res.statusCode());
                return List.of();
            }
            return parse(mapper.readTree(res.body()));
        } catch (Exception e) {
            log.warn("Agent discovery for tenant {} failed: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    private List<DiscoveredAgent> parse(JsonNode root) {
        List<DiscoveredAgent> out = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode a : root) {
                Trigger trigger = null;
                JsonNode triggerNode = a.path("manifest").path("trigger");
                if (triggerNode != null && !triggerNode.isMissingNode() && !triggerNode.isNull()) {
                    try {
                        trigger = mapper.treeToValue(triggerNode, Trigger.class);
                    } catch (Exception e) {
                        log.debug("Could not parse trigger for agent {}: {}", a.path("agentId").asText(), e.getMessage());
                    }
                }
                out.add(new DiscoveredAgent(
                        a.path("agentId").asText(""),
                        a.path("baseUrl").asText(""),
                        trigger,
                        a.path("degraded").asBoolean(false)));
            }
        }
        return out;
    }

    private String base() {
        String b = props.getRegistryBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
