package io.orphos.eip.agent.runtime.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.agent.runtime.DiscoveredServer;
import io.orphos.eip.agent.runtime.DiscoveredTool;
import io.orphos.eip.agent.runtime.RegistryClient;
import io.orphos.eip.agent.runtime.mcp.McpGateway;
import io.orphos.eip.agent.spec.AgentManifest;
import io.orphos.eip.agent.spec.ApprovalState;
import io.orphos.eip.agent.spec.ProposedAction;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * The execute-on-approval path (TO-BE design §5.3, §8). Lists pending proposals for the single
 * approval inbox and, on approval, runs the write tool via the MCP gateway — resolving the tool
 * name to whichever live server advertises it — then transitions the {@link ProposedAction} to
 * EXECUTED (or FAILED). Rejection transitions to REJECTED without running anything.
 */
public class ApprovalService {

    private static final Logger log = LogManager.getLogger(ApprovalService.class);

    private final ApprovalStore store;
    private final RegistryClient registry;
    private final McpGateway gateway;
    private final AgentManifest manifest;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalService(ApprovalStore store, RegistryClient registry, McpGateway gateway,
                           AgentManifest manifest) {
        this.store = store;
        this.registry = registry;
        this.gateway = gateway;
        this.manifest = manifest;
    }

    /** Pending proposals for a tenant — backs the single approval inbox (design §5.2). */
    public List<ProposedAction> pending(UUID tenantId) {
        return store.findPending(tenantId);
    }

    /**
     * Approves and executes a proposal. Returns the tool's result text. Marks the action EXECUTED on
     * success or FAILED on error (and rethrows), so the inbox reflects the true outcome.
     */
    public String approve(UUID actionId) {
        ProposedAction action = store.findById(actionId)
                .orElseThrow(() -> new NoSuchElementException("Unknown proposed action: " + actionId));
        if (action.state() != ApprovalState.PENDING) {
            throw new IllegalStateException("Action " + actionId + " is not PENDING (state=" + action.state() + ").");
        }
        UUID tenantId = action.tenantId();
        // Defense-in-depth allow-list re-check (design §9): the write proposed at plan time must
        // still be a tool the proposing agent is allowed to call at execute time. Guards against a
        // tampered/stale proposal in the shared store, or a capability set narrowed since it was queued.
        enforceAllowList(action);
        return TenantContextHolder.callWith(TenantContext.of(tenantId, null), () -> {
            try {
                List<DiscoveredServer> servers = registry.activeMcpServers(tenantId);
                String result = gateway.execute(tenantId, servers, action.toolName(), writeArgs(action));
                store.updateState(actionId, ApprovalState.EXECUTED);
                log.info("Executed approved action {} (tool={}, tenant={}).", actionId, action.toolName(), tenantId);
                return result;
            } catch (RuntimeException e) {
                store.updateState(actionId, ApprovalState.FAILED);
                log.warn("Approved action {} FAILED during execution: {}", actionId, e.getMessage());
                throw e;
            }
        });
    }

    /**
     * Edit-before-approve (design §8): replaces a PENDING proposal's arguments (and human summary),
     * staying PENDING. If the action's tool is bound to a confirmation token (its {@link DiscoveredTool}
     * declares a {@code prepareTool}), the token is re-issued for the edited content by calling that
     * companion tool on the owning MCP server — so a human edit does not invalidate the draft→send
     * binding. The runtime stays tool-agnostic: it learns the companion tool + token field from the
     * registry, never the token format itself.
     */
    public ProposedAction edit(UUID actionId, Map<String, Object> newArguments, String humanSummary) {
        ProposedAction action = store.findById(actionId)
                .orElseThrow(() -> new NoSuchElementException("Unknown proposed action: " + actionId));
        if (action.state() != ApprovalState.PENDING) {
            throw new IllegalStateException("Action " + actionId + " is not PENDING (state=" + action.state() + ").");
        }
        // The tool cannot be changed by an edit, so the same allow-list guarantee still holds.
        enforceAllowList(action);

        UUID tenantId = action.tenantId();
        Map<String, Object> args = new LinkedHashMap<>(newArguments == null ? Map.of() : newArguments);
        rebindConfirmationToken(tenantId, action.toolName(), args);

        String summary = (humanSummary != null && !humanSummary.isBlank()) ? humanSummary : action.humanSummary();
        store.updateArguments(actionId, args, summary);
        log.info("Edited pending action {} (tool={}, tenant={}).", actionId, action.toolName(), tenantId);
        return store.findById(actionId).orElse(action);
    }

    /**
     * If the tool is bound to a confirmation token, re-mint it for the edited arguments by invoking the
     * tool's declared {@code prepareTool} on whichever live server advertises it, then write the fresh
     * token into the declared argument field. No-op for tools with no token binding (e.g. decision/ingest).
     */
    private void rebindConfirmationToken(UUID tenantId, String toolName, Map<String, Object> args) {
        List<DiscoveredServer> servers = registry.activeMcpServers(tenantId);
        DiscoveredTool tool = servers.stream()
                .flatMap(s -> s.tools().stream())
                .filter(t -> toolName.equals(t.name()))
                .findFirst().orElse(null);
        if (tool == null || tool.prepareTool() == null || tool.prepareTool().isBlank()) {
            return; // tool has no confirmation-token binding
        }
        String prepareTool = tool.prepareTool();
        String tokenField = tool.confirmationTokenField();
        try {
            String argsJson = mapper.writeValueAsString(args);
            String token = stripQuotes(gateway.execute(tenantId, servers, prepareTool, argsJson).strip());
            if (tokenField != null && !tokenField.isBlank()) {
                args.put(tokenField, token);
            }
            log.info("Re-issued confirmation token for edited action (tool={}, via={}).", toolName, prepareTool);
        } catch (Exception e) {
            throw new IllegalStateException("Could not re-issue confirmation token for '" + toolName
                    + "' via '" + prepareTool + "': " + e.getMessage(), e);
        }
    }

    private static String stripQuotes(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Rejects a pending proposal; nothing is executed. */
    public void reject(UUID actionId) {
        ProposedAction action = store.findById(actionId)
                .orElseThrow(() -> new NoSuchElementException("Unknown proposed action: " + actionId));
        if (action.state() != ApprovalState.PENDING) {
            throw new IllegalStateException("Action " + actionId + " is not PENDING (state=" + action.state() + ").");
        }
        store.updateState(actionId, ApprovalState.REJECTED);
        log.info("Rejected action {} (tool={}).", actionId, action.toolName());
    }

    /**
     * Verifies the action's tool is one the proposing agent is allowed to call. When this runtime
     * hosts the proposing agent (its manifest matches {@code action.agentId}), the tool must be in
     * that agent's declared {@code required ∪ optional} capabilities; a mismatch marks the action
     * FAILED and refuses. If this process does not host the proposing agent (a central inbox
     * approving another agent's proposal), the manifest can't authoritatively bound it here, so we
     * log and let the tool's own MCP-server auth/classification be the gate.
     */
    private void enforceAllowList(ProposedAction action) {
        if (manifest == null || !manifest.agentId().equals(action.agentId())) {
            log.debug("Approval for '{}' not locally bounded (this runtime hosts '{}') — relying on the "
                    + "MCP server's own authorization.", action.agentId(),
                    manifest == null ? "<none>" : manifest.agentId());
            return;
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (manifest.requiredCapabilities() != null) allowed.addAll(manifest.requiredCapabilities());
        if (manifest.optionalCapabilities() != null) allowed.addAll(manifest.optionalCapabilities());
        if (!allowed.contains(action.toolName())) {
            store.updateState(action.actionId(), ApprovalState.FAILED);
            throw new SecurityException("Refusing to execute action " + action.actionId() + ": tool '"
                    + action.toolName() + "' is not in agent '" + manifest.agentId()
                    + "' allow-list " + allowed + ".");
        }
    }

    private String writeArgs(ProposedAction action) {
        try {
            return mapper.writeValueAsString(action.arguments() == null ? java.util.Map.of() : action.arguments());
        } catch (Exception e) {
            return "{}";
        }
    }
}
