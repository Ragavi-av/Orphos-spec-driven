package io.orphos.eip.agent.runtime;

import dev.langchain4j.model.chat.ChatModel;

import java.util.UUID;

/**
 * Supplies the tool-calling {@link ChatModel} the planner runs with (TO-BE design §9, §15). Made an
 * injectable bean so the hardcoded model choice no longer lives inside {@link AgentPlanner}: a
 * deployment (or a tenant with its own external tool-calling model key) can override this bean to
 * route planning at an OpenAI-compatible endpoint, Anthropic, etc., while the default keeps serving
 * a local tool-calling model.
 *
 * <p>The method receives {@code tenantId} + {@code usageType} so the intended per-tenant routing
 * ("agent-planning" via nl-query's provider assignment, design §15) can be layered in without
 * touching the planner: nl-query would expose a tool-calling gateway that this factory resolves the
 * per-tenant model from. Until that gateway exists, {@link OllamaPlannerModelFactory} returns the
 * configured local model regardless of tenant.</p>
 */
public interface PlannerModelFactory {

    /**
     * The chat model to plan with for a given tenant + usage type.
     *
     * @param tenantId  the tenant the run is for (never {@code null} in normal operation)
     * @param usageType the manifest's {@code plannerUsageType} (e.g. {@code "agent-planning"}), or {@code null}
     * @return a tool-calling-capable {@link ChatModel}
     */
    ChatModel modelFor(UUID tenantId, String usageType);
}
