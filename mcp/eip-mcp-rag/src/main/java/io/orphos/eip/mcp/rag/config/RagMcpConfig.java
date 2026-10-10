package io.orphos.eip.mcp.rag.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.orphos.eip.mcp.rag.tools.RagTools;
import io.orphos.eip.mcp.spec.McpToolTracing;
import io.orphos.eip.mcp.spec.McpTracePropagator;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link RagTools} methods as MCP tools served over Streamable HTTP (design §3). */
@Configuration
@EnableConfigurationProperties(RagMcpProperties.class)
public class RagMcpConfig {

    @Bean
    public ToolCallbackProvider ragToolCallbacks(RagTools ragTools, ObjectProvider<Tracer> tracer) {
        return McpToolTracing.traced(MethodToolCallbackProvider.builder()
                .toolObjects(ragTools)
                .build(), tracer.getIfAvailable());
    }

    /**
     * Injects the current trace context onto this server's outbound calls, so a tool call logged
     * here and the doc-ingest / nl-query work it triggers share one traceId. {@link ObjectProvider}
     * on both arguments rather than plain injection: neither bean exists when tracing is disabled, and
     * the server must still start and serve tools in that case.
     */
    @Bean
    public McpTracePropagator mcpTracePropagator(ObjectProvider<Tracer> tracer,
                                                 ObjectProvider<Propagator> propagator) {
        return new McpTracePropagator(tracer.getIfAvailable(), propagator.getIfAvailable());
    }
}
