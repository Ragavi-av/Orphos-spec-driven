package io.orphos.eip.agent.runtime;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.orphos.eip.agent.runtime.approval.ApprovalStore;
import io.orphos.eip.agent.runtime.mcp.McpGateway;
import io.orphos.eip.agent.spec.AgentInvocation;
import io.orphos.eip.agent.spec.AgentManifest;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ties the runtime together (TO-BE design §8): binds the tenant, resolves the goal prompt, discovers
 * the tenant's live MCP servers, runs the planner loop against the agent's allow-listed tools, and
 * returns the result. One instance per agent process, parameterized by the agent's declared
 * {@link AgentManifest}.
 *
 * <p>Both autonomy paths are live: read-only / SUGGEST_ONLY returns suggestions directly, while an
 * ACT_WITH_APPROVAL agent's write-tool calls are intercepted by the {@link McpGateway} and persisted
 * as {@link io.orphos.eip.agent.spec.ProposedAction}s for the approval inbox (executed later via
 * {@link io.orphos.eip.agent.runtime.approval.ApprovalService}).</p>
 */
public class AgentEngine {

    private static final Logger log = LogManager.getLogger(AgentEngine.class);

    private final AgentManifest manifest;
    private final RegistryClient registry;
    private final McpGateway gateway;
    private final AgentPlanner planner;
    private final ApprovalStore approvalStore;
    private final GoalPromptResolver promptResolver;
    private final ObservationRegistry observations;

    public AgentEngine(AgentManifest manifest, RegistryClient registry, McpGateway gateway,
                       AgentPlanner planner, ApprovalStore approvalStore, GoalPromptResolver promptResolver,
                       ObservationRegistry observations) {
        this.manifest = manifest;
        this.registry = registry;
        this.gateway = gateway;
        this.planner = planner;
        this.approvalStore = approvalStore;
        this.promptResolver = promptResolver;
        this.observations = observations != null ? observations : ObservationRegistry.NOOP;
    }

    public AgentResult handle(AgentInvocation invocation) {
        if (manifest == null) {
            throw new IllegalStateException(
                    "No AgentManifest bean declared — an agent app must expose its AgentManifest as a bean.");
        }
        UUID tenantId = invocation.tenantId();
        if (tenantId == null) {
            throw new IllegalArgumentException("AgentInvocation.tenantId is required");
        }
        UUID correlationId = invocation.correlationId() != null ? invocation.correlationId() : UUID.randomUUID();

        // One span per agent run (design §16 tracing → OTLP/Jaeger). NOOP registry ⇒ no overhead.
        Observation obs = Observation.createNotStarted("eip.agent.invoke", observations)
                .contextualName("agent " + manifest.agentId())
                .lowCardinalityKeyValue("agent.id", manifest.agentId())
                .highCardinalityKeyValue("tenant.id", tenantId.toString())
                .highCardinalityKeyValue("correlation.id", correlationId.toString());
        obs.start();
        try (Observation.Scope scope = obs.openScope()) {
            return TenantContextHolder.callWith(TenantContext.of(tenantId, null), () -> {
                List<DiscoveredServer> servers = registry.activeMcpServers(tenantId);
                // Per-tenant autonomy policy (design §12 Phase 4): the registry's effective autonomy wins;
                // fall back to the declared manifest autonomy if the registry is unreachable.
                io.orphos.eip.agent.spec.Autonomy autonomy = registry.effectiveAutonomy(tenantId, manifest.agentId());
                if (autonomy == null) autonomy = manifest.autonomy();
                obs.lowCardinalityKeyValue("agent.autonomy", autonomy.name());
                // Per-agent tool allow-list (design §9): the planner may only see the tools this agent
                // declared — required ∪ optional — never the whole mesh's tools.
                Set<String> allowedTools = declaredCapabilities();
                List<UUID> proposedIds = new ArrayList<>();
                try (McpGateway.Session session = gateway.open(
                        tenantId, manifest.agentId(), autonomy, servers, allowedTools, approvalStore, proposedIds)) {
                    String goalPrompt = promptResolver.resolve(manifest.goalPromptKey());
                    String task = buildTask(invocation);
                    String output = planner.run(tenantId, manifest.plannerUsageType(), goalPrompt, task,
                            session.toolProvider());
                    obs.highCardinalityKeyValue("mcp.servers", String.valueOf(session.serverCount()));
                    obs.highCardinalityKeyValue("proposed.actions", String.valueOf(proposedIds.size()));
                    log.info("Agent '{}' completed (tenant={}, correlation={}, mcpServers={}, proposedActions={})",
                            manifest.agentId(), tenantId, correlationId, session.serverCount(), proposedIds.size());
                    return new AgentResult(manifest.agentId(), correlationId, output,
                            session.serverCount(), List.copyOf(proposedIds));
                }
            });
        } catch (RuntimeException e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    /** Required ∪ optional capabilities — the tool names this agent is allowed to call. */
    private Set<String> declaredCapabilities() {
        Set<String> caps = new LinkedHashSet<>();
        if (manifest.requiredCapabilities() != null) caps.addAll(manifest.requiredCapabilities());
        if (manifest.optionalCapabilities() != null) caps.addAll(manifest.optionalCapabilities());
        return caps;
    }

    /** Builds the planner's user message from the invocation trigger + inputs. */
    private String buildTask(AgentInvocation invocation) {
        Map<String, Object> input = invocation.input();
        String inputs = (input == null || input.isEmpty())
                ? "(no additional inputs)"
                : input.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining("\n"));
        return "Trigger: " + invocation.trigger() + "\nInputs:\n" + inputs
                + "\n\nUse the available tools to gather the data you need, then produce the result.";
    }
}
