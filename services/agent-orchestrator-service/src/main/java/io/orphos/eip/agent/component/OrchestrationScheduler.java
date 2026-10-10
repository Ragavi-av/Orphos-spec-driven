package io.orphos.eip.agent.component;

import io.orphos.eip.agent.spec.AgentInvocation;
import io.orphos.eip.agent.spec.Trigger;
import io.orphos.eip.agent.util.OrchestratorProperties;
import io.orphos.eip.agent.vo.DiscoveredAgent;
import io.orphos.eip.common.tenant.ActiveTenantsProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The orchestrator's scheduled trigger loop (TO-BE design §7, §8): for each active tenant, read the
 * live agents from the registry and fire the schedule-triggered, non-DEGRADED ones by POSTing an
 * {@link AgentInvocation} to their {@code /invoke} endpoint. The agent then runs its own planner +
 * MCP gateway and returns a suggestion (read-only slice).
 *
 * <p>This is a coarse scan (every {@code eip.orchestrator.scan-interval-ms}); precise per-agent cron
 * evaluation and event-driven triggering are Phase 4.</p>
 */
@Component
public class OrchestrationScheduler {

    private static final Logger log = LogManager.getLogger(OrchestrationScheduler.class);

    private final OrchestratorProperties props;
    private final OrchestratorRegistryClient registry;
    private final AgentInvoker invoker;
    private final ObjectProvider<ActiveTenantsProvider> tenants;

    public OrchestrationScheduler(OrchestratorProperties props, OrchestratorRegistryClient registry,
                                  AgentInvoker invoker, ObjectProvider<ActiveTenantsProvider> tenants) {
        this.props = props;
        this.registry = registry;
        this.invoker = invoker;
        this.tenants = tenants;
    }

    @Scheduled(initialDelayString = "60000", fixedDelayString = "${eip.orchestrator.scan-interval-ms:900000}")
    public void scan() {
        if (!props.isEnabled()) {
            return;
        }
        ActiveTenantsProvider provider = tenants.getIfAvailable();
        if (provider == null) {
            log.debug("No ActiveTenantsProvider bean — orchestrator has no tenants to scan.");
            return;
        }
        Set<UUID> tenantIds;
        try {
            tenantIds = provider.activeTenantIds();
        } catch (Exception e) {
            log.warn("Could not resolve active tenants: {}", e.getMessage());
            return;
        }
        for (UUID tenantId : tenantIds) {
            for (DiscoveredAgent agent : registry.activeAgents(tenantId)) {
                if (agent.degraded()) {
                    log.info("Skipping DEGRADED agent '{}' for tenant {}.", agent.agentId(), tenantId);
                    continue;
                }
                if (agent.trigger() instanceof Trigger.Schedule) {
                    invoker.invoke(agent.baseUrl(), new AgentInvocation(
                            tenantId, agent.agentId(), agent.trigger(), Map.of(), UUID.randomUUID()));
                }
            }
        }
    }
}
