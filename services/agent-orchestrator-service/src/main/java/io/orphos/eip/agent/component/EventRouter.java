package io.orphos.eip.agent.component;

import io.orphos.eip.agent.spec.AgentInvocation;
import io.orphos.eip.agent.spec.Trigger;
import io.orphos.eip.agent.vo.DiscoveredAgent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Routes an inbound event to the agents that declared an {@link Trigger.Event} for it
 * (TO-BE design §12 Phase 4). Discovers the tenant's live agents from the registry, matches on
 * {@code eventType}, skips DEGRADED ones, and fires each via {@link AgentInvoker}. Shared by the
 * Kafka listener and the HTTP event ingress.
 */
@Component
public class EventRouter {

    private static final Logger log = LogManager.getLogger(EventRouter.class);

    private final OrchestratorRegistryClient registry;
    private final AgentInvoker invoker;

    public EventRouter(OrchestratorRegistryClient registry, AgentInvoker invoker) {
        this.registry = registry;
        this.invoker = invoker;
    }

    /**
     * Fires every event-triggered agent that subscribes to {@code eventType} for {@code tenantId}.
     *
     * @return the number of agents invoked
     */
    public int route(UUID tenantId, String eventType, Map<String, Object> payload) {
        if (tenantId == null || eventType == null || eventType.isBlank()) {
            log.warn("Ignoring event with missing tenantId/eventType.");
            return 0;
        }
        int fired = 0;
        for (DiscoveredAgent agent : registry.activeAgents(tenantId)) {
            if (agent.degraded()) {
                continue;
            }
            if (agent.trigger() instanceof Trigger.Event evt && eventType.equals(evt.eventType())) {
                Map<String, Object> input = new HashMap<>();
                input.put("eventType", eventType);
                if (payload != null) input.putAll(payload);
                boolean ok = invoker.invoke(agent.baseUrl(), new AgentInvocation(
                        tenantId, agent.agentId(), new Trigger.Event(eventType), input, UUID.randomUUID()));
                if (ok) fired++;
            }
        }
        log.info("Event '{}' for tenant {} fired {} agent(s).", eventType, tenantId, fired);
        return fired;
    }
}
