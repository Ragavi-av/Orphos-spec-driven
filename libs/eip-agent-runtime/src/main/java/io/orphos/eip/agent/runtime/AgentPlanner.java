package io.orphos.eip.agent.runtime;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolProvider;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * The planner loop (TO-BE design §5.3, §8): a langchain4j {@link AiServices} assistant configured
 * with the agent's goal prompt (system message), the MCP tool provider, and a hard step budget.
 * langchain4j drives the plan → call tool → observe → iterate cycle until the model produces a final
 * answer or the budget is exhausted.
 *
 * <p>The chat model comes from an injectable {@link PlannerModelFactory} (design §9, §15) — it is no
 * longer hardcoded here, so a deployment/tenant can route planning to an external tool-calling model.
 * Token usage is captured from the run's {@link Result} and logged for cost visibility.</p>
 */
public class AgentPlanner {

    private static final Logger log = LogManager.getLogger(AgentPlanner.class);

    private final AgentRuntimeProperties props;
    private final PlannerModelFactory modelFactory;
    private final ObservationRegistry observations;

    public AgentPlanner(AgentRuntimeProperties props, PlannerModelFactory modelFactory,
                        ObservationRegistry observations) {
        this.props = props;
        this.modelFactory = modelFactory;
        this.observations = observations != null ? observations : ObservationRegistry.NOOP;
    }

    /** The minimal assistant contract langchain4j proxies; the tool-calling loop is implicit. */
    interface Assistant {
        Result<String> chat(String userMessage);
    }

    /**
     * Runs the planner to satisfy {@code goalPrompt} for the given {@code task}, using the supplied
     * MCP tool provider ({@code null} when no tools are available → the model answers from reasoning
     * alone). The loop is bounded by {@code eip.agent.runtime.planner.max-steps} (design §9).
     *
     * @param tenantId     tenant the run is for — selects the planner model + tags usage
     * @param usageType    the manifest's {@code plannerUsageType} (e.g. {@code "agent-planning"})
     * @param goalPrompt   resolved system prompt (see {@link GoalPromptResolver})
     * @param task         the user message built from the invocation
     * @param toolProvider aggregated MCP tools, already restricted to the agent's allow-list
     */
    public String run(UUID tenantId, String usageType, String goalPrompt, String task, ToolProvider toolProvider) {
        int maxSteps = props.getPlanner().getMaxSteps();
        ChatModel model = modelFactory.modelFor(tenantId, usageType);

        AiServices<Assistant> builder = AiServices.builder(Assistant.class)
                .chatModel(model)
                // Bounded-loop guard (design §9): cap plan→act→observe iterations so a confused
                // planner can never spin unbounded against the tool servers.
                .maxSequentialToolsInvocations(maxSteps);
        if (goalPrompt != null && !goalPrompt.isBlank()) {
            builder = builder.systemMessage(goalPrompt);
        }
        if (toolProvider != null) {
            builder = builder.toolProvider(toolProvider);
        }
        Assistant assistant = builder.build();

        log.debug("Planner running (tenant={}, usageType={}, maxSteps={}, tools={})",
                tenantId, usageType, maxSteps, toolProvider instanceof McpToolProvider);

        // Span around the whole plan→act→observe loop (design §16). NOOP registry ⇒ no overhead.
        Observation obs = Observation.createNotStarted("eip.agent.plan", observations)
                .contextualName("plan")
                .lowCardinalityKeyValue("planner.model", String.valueOf(props.getPlanner().getModel()))
                .lowCardinalityKeyValue("planner.usage_type", usageType == null ? "default" : usageType)
                .highCardinalityKeyValue("planner.max_steps", String.valueOf(maxSteps));
        obs.start();
        try (Observation.Scope scope = obs.openScope()) {
            Result<String> result = assistant.chat(task == null ? "" : task);
            TokenUsage usage = result.tokenUsage();
            if (usage != null) {
                // Cost visibility (design §5.3): surface planner spend per run, as span tags + a log line.
                obs.highCardinalityKeyValue("tokens.input", String.valueOf(usage.inputTokenCount()));
                obs.highCardinalityKeyValue("tokens.output", String.valueOf(usage.outputTokenCount()));
                obs.highCardinalityKeyValue("tokens.total", String.valueOf(usage.totalTokenCount()));
                log.info("Planner run complete (tenant={}, usageType={}, tokens in/out/total={}/{}/{})",
                        tenantId, usageType, usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
            }
            return result.content();
        } catch (RuntimeException e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }
}
