package io.orphos.eip.agent.runtime;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import java.time.Duration;
import java.util.UUID;

/**
 * Default {@link PlannerModelFactory}: a local Ollama tool-calling model built from
 * {@link AgentRuntimeProperties.Planner}. Tenant/usage-type aware routing (design §15) is a
 * documented follow-up — this default returns the same configured model for every tenant, which is
 * the correct behaviour for the local-only baseline. Override the {@link PlannerModelFactory} bean
 * to route to an external tool-calling provider per tenant.
 */
public class OllamaPlannerModelFactory implements PlannerModelFactory {

    private final AgentRuntimeProperties props;

    public OllamaPlannerModelFactory(AgentRuntimeProperties props) {
        this.props = props;
    }

    @Override
    public ChatModel modelFor(UUID tenantId, String usageType) {
        AgentRuntimeProperties.Planner p = props.getPlanner();
        return OllamaChatModel.builder()
                .baseUrl(p.getBaseUrl())
                .modelName(p.getModel())
                .timeout(Duration.ofSeconds(p.getTimeoutSeconds()))
                .build();
    }
}
