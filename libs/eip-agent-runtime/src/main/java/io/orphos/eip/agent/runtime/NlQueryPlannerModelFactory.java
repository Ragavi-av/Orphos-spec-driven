package io.orphos.eip.agent.runtime;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import io.orphos.eip.core.ai.NlQueryInternalClient;
import io.orphos.eip.core.ai.NlQueryInternalClient.PlannerModelDescriptor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PlannerModelFactory} that routes planning through nl-query's planner-model gateway
 * (TO-BE design §9, §15): it asks nl-query which tool-calling model this tenant should plan with for
 * the {@code agent-planning} usage type, so the model is chosen per tenant by the platform's single
 * AI-config authority rather than hardcoded in the agent.
 *
 * <p>For an {@code ollama}-kind descriptor the agent builds the model directly (its native
 * dependency). For an {@code external} kind — a provider the agent does not host locally, or any case
 * where the gateway is disabled/unreachable — it falls back to {@code delegate} (the configured local
 * model). Supporting external tool-calling providers agent-side is a one-dependency extension
 * (add the matching langchain4j provider module and a {@code case} below); no secret ever crosses the
 * gateway, so nothing leaks in the meantime.</p>
 */
public class NlQueryPlannerModelFactory implements PlannerModelFactory {

    private static final Logger log = LogManager.getLogger(NlQueryPlannerModelFactory.class);

    private final AgentRuntimeProperties props;
    private final NlQueryInternalClient nlQuery;
    private final PlannerModelFactory delegate;

    public NlQueryPlannerModelFactory(AgentRuntimeProperties props, NlQueryInternalClient nlQuery,
                                      PlannerModelFactory delegate) {
        this.props = props;
        this.nlQuery = nlQuery;
        this.delegate = delegate;
    }

    @Override
    public ChatModel modelFor(UUID tenantId, String usageType) {
        Optional<PlannerModelDescriptor> resolved = nlQuery.resolvePlannerModel(tenantId, usageType);
        if (resolved.isEmpty()) {
            log.debug("No planner model from nl-query (tenant={}, usageType={}) — using local default.",
                    tenantId, usageType);
            return delegate.modelFor(tenantId, usageType);
        }
        PlannerModelDescriptor d = resolved.get();
        if (d.isOllama() && d.baseUrl() != null && !d.baseUrl().isBlank()) {
            log.debug("Planner routed via nl-query (tenant={}): ollama model '{}' at {}.",
                    tenantId, d.model(), d.baseUrl());
            return OllamaChatModel.builder()
                    .baseUrl(d.baseUrl())
                    .modelName(d.model())
                    .timeout(Duration.ofSeconds(props.getPlanner().getTimeoutSeconds()))
                    .build();
        }
        log.info("nl-query assigned external planner provider '{}' (model '{}') for tenant {}, which this "
                + "agent cannot build locally yet — falling back to the local planner model.",
                d.providerId(), d.model(), tenantId);
        return delegate.modelFor(tenantId, usageType);
    }
}
