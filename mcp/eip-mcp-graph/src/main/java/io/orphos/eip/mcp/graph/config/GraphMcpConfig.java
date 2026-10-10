package io.orphos.eip.mcp.graph.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.orphos.eip.mcp.graph.tools.GraphTools;
import io.orphos.eip.mcp.spec.McpToolTracing;
import io.orphos.eip.mcp.spec.McpTracePropagator;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link GraphTools} methods as MCP tools served over Streamable HTTP (design §3). */
@Configuration
@EnableConfigurationProperties(GraphMcpProperties.class)
public class GraphMcpConfig {

    @Bean
    public ToolCallbackProvider graphToolCallbacks(GraphTools graphTools, ObjectProvider<Tracer> tracer) {
        return McpToolTracing.traced(MethodToolCallbackProvider.builder()
                .toolObjects(graphTools)
                .build(), tracer.getIfAvailable());
    }

    /**
     * Injects the current trace context onto this server's outbound calls, so a SPARQL query logged
     * here and the graph-proxy work it triggers share one traceId. {@link ObjectProvider} on both
     * arguments rather than plain injection: neither bean exists when tracing is disabled, and the
     * server must still start and serve tools in that case.
     */
    @Bean
    public McpTracePropagator mcpTracePropagator(ObjectProvider<Tracer> tracer,
                                                 ObjectProvider<Propagator> propagator) {
        return new McpTracePropagator(tracer.getIfAvailable(), propagator.getIfAvailable());
    }
}
