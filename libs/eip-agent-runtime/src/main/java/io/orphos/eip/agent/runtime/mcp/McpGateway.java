package io.orphos.eip.agent.runtime.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.orphos.eip.agent.runtime.AgentRuntimeProperties;
import io.orphos.eip.agent.runtime.DiscoveredServer;
import io.orphos.eip.agent.runtime.DiscoveredTool;
import io.orphos.eip.agent.runtime.approval.ApprovalStore;
import io.orphos.eip.agent.spec.ApprovalState;
import io.orphos.eip.agent.spec.Autonomy;
import io.orphos.eip.agent.spec.ProposedAction;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
import io.orphos.eip.mcp.spec.McpHeaders;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The MCP gateway client of the agent runtime (TO-BE design §5.3): builds one langchain4j MCP
 * client per discovered server over Streamable HTTP, with the tenant + internal-service headers
 * baked into the transport (design §5.1), and exposes the servers' tools to the planner as one
 * {@link ToolProvider} (invariant I2).
 *
 * <p><b>Write-path (design §8):</b> when the planner calls a <em>write</em> tool, a
 * {@code toolWrapper} intercepts it according to the agent's {@link Autonomy}:
 * <ul>
 *   <li>{@code SUGGEST_ONLY} — blocked (the agent may not write);</li>
 *   <li>{@code ACT_WITH_APPROVAL} — persisted as a PENDING {@link ProposedAction} and NOT executed;</li>
 *   <li>{@code AUTONOMOUS} — executed directly.</li>
 * </ul>
 * Read tools always execute. {@link #execute} runs a specific write tool later, on approval.</p>
 */
public class McpGateway {

    private static final Logger log = LogManager.getLogger(McpGateway.class);

    private final AgentRuntimeProperties props;
    private final ObservationRegistry observations;
    private final EipCircuitBreakers circuitBreakers;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpGateway(AgentRuntimeProperties props, ObservationRegistry observations, EipCircuitBreakers circuitBreakers) {
        this.props = props;
        this.observations = observations != null ? observations : ObservationRegistry.NOOP;
        this.circuitBreakers = circuitBreakers;
    }

    /** Breaker for tool calls served by a given MCP server, so a flaky server fails fast mid-session
     * instead of every tool call eating the full planner timeout (unlike {@link #buildClients}'s
     * degradation, which only covers a server unreachable at session-open). */
    private CircuitBreaker breakerFor(String serverId) {
        return circuitBreakers.breaker("mcp-tool:" + (serverId == null ? "unknown" : serverId));
    }

    /** The serverId hosting {@code toolName}, from the discovered tool list — no extra RPC needed. */
    private static String serverFor(List<DiscoveredServer> servers, String toolName) {
        for (DiscoveredServer s : servers) {
            for (DiscoveredTool t : s.tools()) {
                if (t.name().equals(toolName)) return s.serverId();
            }
        }
        return "unknown";
    }

    private static Map<String, String> toolToServerMap(List<DiscoveredServer> servers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (DiscoveredServer s : servers) {
            for (DiscoveredTool t : s.tools()) {
                map.put(t.name(), s.serverId());
            }
        }
        return map;
    }

    /**
     * Opens a planning session over the given servers for one tenant/agent. Only tools in
     * {@code allowedToolNames} (the agent's declared {@code requiredCapabilities ∪ optionalCapabilities})
     * are exposed to the planner — the per-agent tool allow-list of design §9, enforced via
     * {@link McpToolProvider}'s {@code filterToolNames} so a disallowed tool is never even visible.
     * Write tools are handled per {@code autonomy}; any queued proposals have their ids appended to
     * {@code proposedIdsOut}. The caller MUST close the returned session (try-with-resources).
     */
    public Session open(UUID tenantId, String agentId, Autonomy autonomy, List<DiscoveredServer> servers,
                        Set<String> allowedToolNames, ApprovalStore approvalStore, List<UUID> proposedIdsOut) {
        List<McpClient> clients = buildClients(tenantId, servers);

        // An agent that declares no capabilities gets no tools (safe default), rather than the
        // whole mesh's tools. filterToolNames restricts an otherwise-open provider to the allow-list.
        List<String> allowed = allowedToolNames == null ? List.of() : List.copyOf(allowedToolNames);

        Set<String> writeToolNames = new java.util.HashSet<>();
        for (DiscoveredServer s : servers) writeToolNames.addAll(s.writeToolNames());

        ToolProvider toolProvider = null;
        if (!clients.isEmpty() && !allowed.isEmpty()) {
            Function<ToolExecutor, ToolExecutor> wrapper = writeInterceptor(
                    tenantId, agentId, autonomy, writeToolNames, approvalStore, proposedIdsOut,
                    toolToServerMap(servers));
            toolProvider = McpToolProvider.builder()
                    .mcpClients(clients)
                    .filterToolNames(allowed)
                    .toolWrapper(wrapper)
                    .build();
        } else if (!clients.isEmpty()) {
            log.info("Agent '{}' declares no capabilities — exposing no tools to the planner.", agentId);
        }
        return new Session(clients, toolProvider);
    }

    /**
     * Executes a single tool by name with the given JSON arguments (the execute-on-approval path,
     * design §8). Resolves the tool name to whichever live server advertises it, runs it, and
     * returns the result text. Throws on failure so the approval flow can mark the action FAILED.
     */
    public String execute(UUID tenantId, List<DiscoveredServer> servers, String toolName, String argumentsJson) {
        List<McpClient> clients = buildClients(tenantId, servers);
        CircuitBreaker breaker = breakerFor(serverFor(servers, toolName));
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(toolName)
                    .arguments(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson)
                    .build();
            for (McpClient client : clients) {
                boolean advertises = client.listTools().stream()
                        .map(ToolSpecification::name).anyMatch(toolName::equals);
                if (!advertises) continue;
                ToolExecutionResult result = breaker.executeSupplier(() -> client.executeTool(request));
                if (result.isError()) {
                    throw new IllegalStateException("Tool '" + toolName + "' returned an error: " + result.resultText());
                }
                return result.resultText();
            }
            throw new IllegalStateException("No live MCP server advertises tool '" + toolName + "' for this tenant.");
        } finally {
            closeAll(clients);
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private List<McpClient> buildClients(UUID tenantId, List<DiscoveredServer> servers) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(McpHeaders.TENANT_ID, tenantId.toString());
        if (props.getInternalToken() != null && !props.getInternalToken().isBlank()) {
            headers.put(McpHeaders.INTERNAL_TOKEN, props.getInternalToken());
        }
        List<McpClient> clients = new ArrayList<>();
        for (DiscoveredServer s : servers) {
            if (s.mcpEndpoint() == null || s.mcpEndpoint().isBlank()) continue;
            try {
                McpTransport transport = new StreamableHttpMcpTransport.Builder()
                        .url(s.mcpEndpoint())
                        .customHeaders(headers)
                        .timeout(Duration.ofSeconds(props.getPlanner().getTimeoutSeconds()))
                        .build();
                clients.add(new DefaultMcpClient.Builder()
                        .key(s.serverId())
                        .transport(transport)
                        .build());
            } catch (Exception e) {
                // One unreachable server must not fail the whole session (graceful degradation).
                log.warn("Skipping MCP server '{}' ({}): {}", s.serverId(), s.mcpEndpoint(), e.getMessage());
            }
        }
        return clients;
    }

    private Function<ToolExecutor, ToolExecutor> writeInterceptor(
            UUID tenantId, String agentId, Autonomy autonomy, Set<String> writeToolNames,
            ApprovalStore approvalStore, List<UUID> proposedIdsOut, Map<String, String> toolToServer) {

        return original -> (request, memoryId) -> {
            String name = request.name();
            CircuitBreaker breaker = breakerFor(toolToServer.get(name));
            // One span per tool call (design §16): tool name + outcome (read/blocked/queued/executed).
            Observation obs = Observation.createNotStarted("eip.mcp.tool_call", observations)
                    .contextualName("mcp " + name)
                    .lowCardinalityKeyValue("tool.name", name)
                    .lowCardinalityKeyValue("agent.id", agentId);
            obs.start();
            try (Observation.Scope scope = obs.openScope()) {
                if (!writeToolNames.contains(name)) {
                    obs.lowCardinalityKeyValue("tool.outcome", "executed");
                    // CB fails fast once this tool's server is repeatedly failing mid-session — no
                    // retry here, a tool call may have write side effects the planner can't tell
                    // apart from a read at this layer.
                    return breaker.executeSupplier(() -> original.execute(request, memoryId));
                }
                return switch (autonomy) {
                    case SUGGEST_ONLY -> {
                        obs.lowCardinalityKeyValue("tool.outcome", "blocked");
                        yield "Blocked: agent '" + agentId
                                + "' is SUGGEST_ONLY and cannot execute the write tool '" + name + "'.";
                    }
                    case ACT_WITH_APPROVAL -> {
                        UUID actionId = UUID.randomUUID();
                        ProposedAction proposal = new ProposedAction(actionId, tenantId, agentId, name,
                                parseArgs(request.arguments()),
                                "Proposed by " + agentId + ": " + name,
                                Instant.now(), ApprovalState.PENDING);
                        approvalStore.save(proposal);
                        if (proposedIdsOut != null) proposedIdsOut.add(actionId);
                        obs.lowCardinalityKeyValue("tool.outcome", "queued_for_approval");
                        log.info("Agent '{}' proposed write '{}' (actionId={}) for approval.", agentId, name, actionId);
                        yield "Queued for approval (actionId=" + actionId + "). The write was NOT executed; "
                                + "a human must approve it before it runs.";
                    }
                    case AUTONOMOUS -> {
                        obs.lowCardinalityKeyValue("tool.outcome", "executed");
                        yield breaker.executeSupplier(() -> original.execute(request, memoryId));
                    }
                };
            } catch (RuntimeException e) {
                obs.error(e);
                throw e;
            } finally {
                obs.stop();
            }
        };
    }

    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            return mapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("raw", argumentsJson);
        }
    }

    private static void closeAll(List<McpClient> clients) {
        for (McpClient c : clients) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    /**
     * A tenant-scoped set of live MCP clients + the aggregated {@link ToolProvider}. AutoCloseable so
     * the runtime releases the clients after each planning run.
     */
    public static final class Session implements AutoCloseable {

        private final List<McpClient> clients;
        private final ToolProvider toolProvider;

        Session(List<McpClient> clients, ToolProvider toolProvider) {
            this.clients = clients;
            this.toolProvider = toolProvider;
        }

        /** The aggregated tool provider, or {@code null} when no server was reachable. */
        public ToolProvider toolProvider() {
            return toolProvider;
        }

        public int serverCount() {
            return clients.size();
        }

        @Override
        public void close() {
            closeAll(clients);
        }
    }
}
