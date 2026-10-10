package io.orphos.eip.mailinsight.channels;

import io.orphos.eip.mailinsight.graph.GraphItem;

import java.util.List;
import java.util.Map;

/**
 * A connector for one communication-channel platform. One implementation per
 * {@link CommChannelKind}. Implementations are stateless Spring beans — all
 * per-connection state (workspace/team ids, base urls, credentials) arrives in
 * the {@link Descriptor}, so a single bean serves every tenant's connections
 * (mirrors doc-ingest's SourceConnector pattern).
 *
 * <p>Messages are normalised into the shared {@link GraphItem} record so they
 * flow through the existing classify → persist → vector-index pipeline
 * unchanged. Sending a reply is optional — connectors that cannot (or must not)
 * post back leave the default {@link #sendMessage} in place.</p>
 */
public interface CommChannelConnector {

    CommChannelKind kind();

    /** True once a real implementation exists; stubbed connectors override to false. */
    default boolean supported() {
        return true;
    }

    /**
     * Cheap connectivity + auth probe; never throws — failures come back as
     * {@code ok=false} with an actionable message.
     */
    ProbeResult test(Descriptor desc);

    /**
     * Messages from one monitored account (a channel/conversation identified by
     * {@code externalId}) created within the trailing {@code windowDays} window,
     * newest-first is not required — the pipeline dedupes and sorts.
     */
    List<GraphItem> listMessages(Descriptor desc, String externalId, int windowDays);

    /**
     * Post a reply/message back to the given account (channel/conversation).
     * Default: unsupported — the comms agent's send action surfaces this as an
     * actionable error rather than silently dropping the message.
     */
    default void sendMessage(Descriptor desc, String externalId, String text) {
        throw new UnsupportedOperationException(kind() + " does not support sending messages");
    }

    /**
     * All per-connection state. {@code config} holds non-secret settings
     * (directoryId/clientId/baseUrl…) as stored in {@code configJson};
     * {@code credentialJson} is the resolved secret payload (bot token, client
     * secret) as JSON — may be blank when the connection has none configured.
     */
    record Descriptor(CommChannelKind kind, Map<String, Object> config, String credentialJson) {}

    record ProbeResult(boolean ok, String message, int sampleCount) {
        public static ProbeResult ok(int sampleCount) { return new ProbeResult(true, "OK", sampleCount); }
        public static ProbeResult fail(String message) { return new ProbeResult(false, message, 0); }
    }
}
