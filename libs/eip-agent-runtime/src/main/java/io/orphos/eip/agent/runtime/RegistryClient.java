package io.orphos.eip.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.orphos.eip.agent.spec.AgentRegistration;
import io.orphos.eip.agent.spec.Autonomy;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
import io.orphos.eip.mcp.spec.McpHeaders;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Thin HTTP client to platform-registry-service (TO-BE design §6): the agent self-registers its
 * manifest at startup and discovers the MCP servers live for a tenant. Service-to-service auth uses
 * the shared internal token ({@link McpHeaders#INTERNAL_TOKEN}); discovery is tenant-scoped via
 * {@link McpHeaders#TENANT_ID}.
 *
 * <p>Every call is best-effort: a non-2xx or an unreachable registry degrades to a logged warning
 * and a neutral return value, never an exception, because an agent must still start and serve
 * traffic when the registry is down. The {@link RestTemplate} is therefore built lenient — it hands
 * back error responses instead of throwing, which is what the status checks below rely on.</p>
 *
 * <p>One circuit breaker guards all three calls (registration, autonomy lookup, MCP-server
 * discovery) since they share the same downstream — if platform-registry-service is down, all
 * three should stop blocking for up to {@code READ_TIMEOUT} apiece and start failing instantly.
 * Every agent process makes these calls repeatedly (registration at startup/retry,
 * autonomy/discovery per planning cycle), so a registry outage would otherwise mean every one of
 * those calls hangs for 15s before falling back — the breaker turns that into an immediate,
 * already-handled "unreachable" the moment the outage is detected.</p>
 */
public class RegistryClient {

    private static final Logger log = LogManager.getLogger(RegistryClient.class);

    /** Longest read any registry call needs (registration and discovery); see class notes. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final AgentRuntimeProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest;
    private final CircuitBreaker breaker;

    public RegistryClient(AgentRuntimeProperties props, EipRestTemplates restTemplates, EipCircuitBreakers circuitBreakers) {
        this.props = props;
        this.rest = restTemplates.createLenient(CONNECT_TIMEOUT, READ_TIMEOUT);
        this.breaker = circuitBreakers.breaker("platform-registry");
    }

    /** Upsert this agent's registration. Best-effort: logs and returns false on failure. */
    public boolean registerAgent(AgentRegistration registration) {
        try {
            HttpHeaders headers = internalHeaders(null);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(registration), headers);

            ResponseEntity<String> res = breaker.executeSupplier(() -> rest.exchange(
                    base() + "/api/platform/agents/register", HttpMethod.POST, entity, String.class));
            if (res.getStatusCode().is2xxSuccessful()) {
                log.info("Agent '{}' registered with platform-registry.", registration.manifest().agentId());
                return true;
            }
            log.warn("Agent registration returned {}: {}", res.getStatusCode().value(), res.getBody());
            return false;
        } catch (Exception e) {
            log.warn("Agent registration failed (registry unreachable?): {}", e.getMessage());
            return false;
        }
    }

    /**
     * The effective autonomy the registry says applies to {@code agentId} for {@code tenantId}
     * (per-tenant override ∨ manifest). Returns {@code null} if the registry is unreachable or the
     * value is unparseable, so the caller can fall back to its declared manifest autonomy.
     */
    public Autonomy effectiveAutonomy(UUID tenantId, String agentId) {
        try {
            String url = base() + "/api/platform/agents/" + agentId + "/tenants/" + tenantId + "/autonomy";
            ResponseEntity<String> res = breaker.executeSupplier(() -> rest.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(internalHeaders(tenantId)), String.class));
            if (!res.getStatusCode().is2xxSuccessful()) return null;
            String value = mapper.readTree(res.getBody()).path("autonomy").asText("");
            return value.isBlank() ? null : Autonomy.valueOf(value);
        } catch (Exception e) {
            log.debug("Effective-autonomy lookup for {} failed: {}", agentId, e.getMessage());
            return null;
        }
    }

    /** MCP servers live (enabled + not DOWN) for {@code tenantId}, with the tool names each advertises. */
    public List<DiscoveredServer> activeMcpServers(UUID tenantId) {
        try {
            ResponseEntity<String> res = breaker.executeSupplier(() -> rest.exchange(
                    base() + "/api/platform/mcp-servers/active", HttpMethod.GET,
                    new HttpEntity<>(internalHeaders(tenantId)), String.class));
            if (!res.getStatusCode().is2xxSuccessful()) {
                log.warn("MCP-server discovery returned {}", res.getStatusCode().value());
                return List.of();
            }
            return parseServers(mapper.readTree(res.getBody()));
        } catch (Exception e) {
            log.warn("MCP-server discovery failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Internal-token headers, plus the tenant scope when the call is tenant-specific. */
    private HttpHeaders internalHeaders(UUID tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        if (tenantId != null) headers.set(McpHeaders.TENANT_ID, tenantId.toString());
        return headers;
    }

    private static List<DiscoveredServer> parseServers(JsonNode root) {
        List<DiscoveredServer> out = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode s : root) {
                List<DiscoveredTool> tools = new ArrayList<>();
                JsonNode toolsNode = s.get("tools");
                if (toolsNode != null && toolsNode.isArray()) {
                    toolsNode.forEach(t -> {
                        String name = t.path("name").asText("");
                        if (!name.isBlank()) {
                            tools.add(new DiscoveredTool(
                                    name,
                                    t.path("write").asBoolean(false),
                                    t.path("approvalRequired").asBoolean(false),
                                    emptyToNull(t.path("prepareTool").asText("")),
                                    emptyToNull(t.path("confirmationTokenField").asText(""))));
                        }
                    });
                }
                out.add(new DiscoveredServer(
                        s.path("serverId").asText(""),
                        s.path("mcpEndpoint").asText(""),
                        List.copyOf(tools)));
            }
        }
        return out;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String base() {
        String b = props.getRegistryBaseUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
