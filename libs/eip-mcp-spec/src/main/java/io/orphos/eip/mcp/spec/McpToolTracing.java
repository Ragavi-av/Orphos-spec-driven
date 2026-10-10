package io.orphos.eip.mcp.spec;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Arrays;

/**
 * Gives every MCP tool invocation its own span (TO-BE design §14).
 *
 * <p>Without this, an MCP server produces exactly one span per HTTP request to {@code /mcp}. That
 * is enough to correlate logs, but not to tell <em>which tool</em> a log line or an upstream call
 * belongs to — and a Streamable-HTTP session can carry several {@code tools/call} messages. Wrapping
 * each {@link ToolCallback} in a child span means each tool call gets its own spanId under the
 * inbound request's traceId, and every log line and outbound call made while it runs inherits that
 * pair through the MDC and {@link McpTracePropagator} respectively.</p>
 *
 * <p>Apply it once per server, where the {@code ToolCallbackProvider} bean is built:</p>
 * <pre>{@code
 * @Bean
 * public ToolCallbackProvider graphToolCallbacks(GraphTools graphTools, Tracer tracer) {
 *     return McpToolTracing.traced(MethodToolCallbackProvider.builder()
 *             .toolObjects(graphTools)
 *             .build(), tracer);
 * }
 * }</pre>
 *
 * <p>The decoration is transparent: {@link ToolCallback#getToolDefinition()} and
 * {@link ToolCallback#getToolMetadata()} pass straight through, so the tool schema advertised over
 * MCP is byte-for-byte what the undecorated provider advertised. A {@code null} tracer (tracing
 * disabled, or actuator absent) returns the provider unchanged.</p>
 */
public final class McpToolTracing {

    /** Span tag carrying the MCP tool name, so a trace can be filtered by tool in Jaeger. */
    public static final String TAG_TOOL_NAME = "mcp.tool.name";

    /** Span name prefix, keeping MCP tool spans greppable alongside the HTTP spans around them. */
    private static final String SPAN_NAME_PREFIX = "mcp.tool ";

    private McpToolTracing() {}

    /**
     * Wraps every callback of {@code delegate} so each tool call runs in its own child span.
     *
     * @param delegate the provider to decorate
     * @param tracer   the current tracer, or {@code null} to leave {@code delegate} untouched
     */
    public static ToolCallbackProvider traced(ToolCallbackProvider delegate, Tracer tracer) {
        if (delegate == null || tracer == null) {
            return delegate;
        }
        ToolCallback[] traced = Arrays.stream(delegate.getToolCallbacks())
                .map(callback -> (ToolCallback) new TracedToolCallback(callback, tracer))
                .toArray(ToolCallback[]::new);
        return () -> traced;
    }

    /**
     * One MCP tool, executed inside a child of whatever span is current — the server span Spring
     * Boot opened for the {@code /mcp} request in the normal case.
     *
     * <p>Failures are recorded on the span and rethrown unchanged. Deciding whether a tool failure
     * degrades to a message or surfaces as an error belongs to the tool (a read degrades, a send
     * must not), so this must not swallow anything.</p>
     */
    private record TracedToolCallback(ToolCallback delegate, Tracer tracer) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return inSpan(() -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return inSpan(() -> delegate.call(toolInput, toolContext));
        }

        private String inSpan(java.util.function.Supplier<String> work) {
            String toolName = getToolDefinition().name();
            Span span = tracer.nextSpan().name(SPAN_NAME_PREFIX + toolName).tag(TAG_TOOL_NAME, toolName);
            try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
                return work.get();
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
        }
    }
}
