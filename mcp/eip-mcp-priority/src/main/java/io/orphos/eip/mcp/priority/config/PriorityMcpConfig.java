package io.orphos.eip.mcp.priority.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.orphos.eip.mcp.priority.tools.PriorityTools;
import io.orphos.eip.mcp.spec.McpToolTracing;
import io.orphos.eip.mcp.spec.McpTracePropagator;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link PriorityTools} methods as MCP tools. The Spring AI MCP server starter serves
 * every {@link ToolCallbackProvider} bean over Streamable HTTP (TO-BE design §3).
 */
@Configuration
@EnableConfigurationProperties(PriorityMcpProperties.class)
public class PriorityMcpConfig {

    @Bean
    public ToolCallbackProvider priorityToolCallbacks(PriorityTools priorityTools, ObjectProvider<Tracer> tracer) {
        return McpToolTracing.traced(MethodToolCallbackProvider.builder()
                .toolObjects(priorityTools)
                .build(), tracer.getIfAvailable());
    }

    /**
     * Injects the current trace context onto this server's outbound calls, so a tool call logged
     * here and the priority-engine work it triggers share one traceId. {@link ObjectProvider} on both
     * arguments rather than plain injection: neither bean exists when tracing is disabled, and the
     * server must still start and serve tools in that case.
     */
    @Bean
    public McpTracePropagator mcpTracePropagator(ObjectProvider<Tracer> tracer,
                                                 ObjectProvider<Propagator> propagator) {
        return new McpTracePropagator(tracer.getIfAvailable(), propagator.getIfAvailable());
    }
}
