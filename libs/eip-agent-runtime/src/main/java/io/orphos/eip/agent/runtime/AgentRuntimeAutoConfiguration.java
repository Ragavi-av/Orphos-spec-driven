package io.orphos.eip.agent.runtime;

import io.orphos.eip.agent.runtime.approval.ApprovalService;
import io.orphos.eip.agent.runtime.approval.ApprovalStore;
import io.orphos.eip.agent.runtime.approval.ArcadeApprovalStore;
import io.orphos.eip.agent.runtime.approval.NoopApprovalStore;
import io.orphos.eip.agent.runtime.mcp.McpGateway;
import io.orphos.eip.agent.runtime.web.ApprovalController;
import io.orphos.eip.agent.runtime.web.InvokeController;
import io.micrometer.observation.ObservationRegistry;
import io.orphos.eip.agent.spec.AgentManifest;
import io.orphos.eip.common.http.EipHttpAutoConfiguration;
import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.resilience.EipCircuitBreakers;
import io.orphos.eip.common.resilience.EipResilienceAutoConfiguration;
import io.orphos.eip.core.ai.NlQueryInternalClient;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires {@code eip-agent-runtime} for any agent that depends on it (TO-BE design §5.3). An agent
 * becomes a thin Spring Boot app: declare an {@link AgentManifest} bean, set
 * {@code eip.agent.runtime.*}, and this autoconfiguration provides the registry client, MCP gateway,
 * planner, approval store, {@code /invoke} endpoint, and startup self-registration.
 */
@AutoConfiguration
@AutoConfigureAfter({EipHttpAutoConfiguration.class, EipResilienceAutoConfiguration.class})
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RegistryClient agentRegistryClient(
            AgentRuntimeProperties props, EipRestTemplates restTemplates, EipCircuitBreakers circuitBreakers) {
        return new RegistryClient(props, restTemplates, circuitBreakers);
    }

    /** ObservationRegistry from the app's actuator/micrometer autoconfig, or NOOP when tracing is absent. */
    private static ObservationRegistry observations(ObjectProvider<ObservationRegistry> provider) {
        ObservationRegistry reg = provider.getIfAvailable();
        return reg != null ? reg : ObservationRegistry.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    public McpGateway mcpGateway(AgentRuntimeProperties props, ObjectProvider<ObservationRegistry> observations,
                                 EipCircuitBreakers circuitBreakers) {
        return new McpGateway(props, observations(observations), circuitBreakers);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlannerModelFactory plannerModelFactory(AgentRuntimeProperties props,
                                                   ObjectProvider<NlQueryInternalClient> nlQuery) {
        PlannerModelFactory local = new OllamaPlannerModelFactory(props);
        NlQueryInternalClient client = nlQuery.getIfAvailable();
        // Route planning per-tenant via nl-query when its gateway is configured; otherwise plan with
        // the local default. nl-query stays the single authority on per-tenant model selection (§15).
        if (client != null && client.isEnabled()) {
            return new NlQueryPlannerModelFactory(props, client, local);
        }
        return local;
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentPlanner agentPlanner(AgentRuntimeProperties props, PlannerModelFactory modelFactory,
                                     ObjectProvider<ObservationRegistry> observations) {
        return new AgentPlanner(props, modelFactory, observations(observations));
    }

    @Bean
    @ConditionalOnMissingBean
    public GoalPromptResolver goalPromptResolver(AgentRuntimeProperties props) {
        return new GoalPromptResolver(props.getPrompts());
    }

    // ── approval store: ArcadeDB-backed when available, else a loud no-op ──────

    @Bean
    @ConditionalOnBean(ArcadeDbTemplate.class)
    @ConditionalOnMissingBean(ApprovalStore.class)
    public ApprovalStore arcadeApprovalStore(ArcadeDbTemplate arcade) {
        return new ArcadeApprovalStore(arcade);
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalStore.class)
    public ApprovalStore noopApprovalStore() {
        return new NoopApprovalStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentEngine agentEngine(ObjectProvider<AgentManifest> manifest, RegistryClient registry,
                                   McpGateway gateway, AgentPlanner planner, ApprovalStore approvalStore,
                                   GoalPromptResolver promptResolver, ObjectProvider<ObservationRegistry> observations) {
        return new AgentEngine(manifest.getIfAvailable(), registry, gateway, planner, approvalStore, promptResolver,
                observations(observations));
    }

    @Bean
    @ConditionalOnMissingBean
    public InvokeController agentInvokeController(AgentEngine engine) {
        return new InvokeController(engine);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalService approvalService(ApprovalStore store, RegistryClient registry, McpGateway gateway,
                                           ObjectProvider<AgentManifest> manifest) {
        return new ApprovalService(store, registry, gateway, manifest.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalController approvalController(ApprovalService service) {
        return new ApprovalController(service);
    }

    @Bean
    @ConditionalOnBean(AgentManifest.class)
    @ConditionalOnMissingBean
    public AgentSelfRegistrar agentSelfRegistrar(AgentManifest manifest, RegistryClient registry,
                                                 AgentRuntimeProperties props) {
        return new AgentSelfRegistrar(manifest, registry, props);
    }
}
