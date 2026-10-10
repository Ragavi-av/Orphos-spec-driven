package io.orphos.eip.mcp.mail.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.orphos.eip.mcp.mail.service.DraftTokens;
import io.orphos.eip.mcp.mail.tools.MailTools;
import io.orphos.eip.mcp.spec.McpToolTracing;
import io.orphos.eip.mcp.spec.McpTracePropagator;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link MailTools} methods as MCP tools served over Streamable HTTP (design §3). */
@Configuration
@EnableConfigurationProperties(MailMcpProperties.class)
public class MailMcpConfig {

    @Bean
    public ToolCallbackProvider mailToolCallbacks(MailTools mailTools, ObjectProvider<Tracer> tracer) {
        return McpToolTracing.traced(MethodToolCallbackProvider.builder()
                .toolObjects(mailTools)
                .build(), tracer.getIfAvailable());
    }

    /**
     * Signs and verifies the draft-then-send confirmation tokens. One instance for the whole
     * application: the signing key is fixed at construction, so every token issued by
     * {@code mail.draft_reply} must be verifiable by {@code mail.send_email} on any later call.
     */
    @Bean
    public DraftTokens draftTokens(MailMcpProperties props) {
        return new DraftTokens(props.getInternalToken());
    }

    /**
     * Injects the current trace context onto this server's outbound calls, so a tool call logged
     * here and the mail-insight work it triggers share one traceId. {@link ObjectProvider} on both
     * arguments rather than plain injection: neither bean exists when tracing is disabled, and the
     * server must still start and serve tools in that case.
     */
    @Bean
    public McpTracePropagator mcpTracePropagator(ObjectProvider<Tracer> tracer,
                                                 ObjectProvider<Propagator> propagator) {
        return new McpTracePropagator(tracer.getIfAvailable(), propagator.getIfAvailable());
    }
}
