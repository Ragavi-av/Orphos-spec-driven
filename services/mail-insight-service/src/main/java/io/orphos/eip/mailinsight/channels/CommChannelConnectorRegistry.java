package io.orphos.eip.mailinsight.channels;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Collects every {@link CommChannelConnector} bean and indexes it by
 * {@link CommChannelKind}. Adding a new channel platform is just adding a bean —
 * no change here (mirrors doc-ingest's SourceConnectorRegistry).
 */
@Component
public class CommChannelConnectorRegistry {

    private final Map<CommChannelKind, CommChannelConnector> byKind;

    public CommChannelConnectorRegistry(List<CommChannelConnector> connectors) {
        this.byKind = connectors.stream()
                .collect(Collectors.toUnmodifiableMap(CommChannelConnector::kind, Function.identity()));
    }

    public Optional<CommChannelConnector> find(CommChannelKind kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    public CommChannelConnector require(CommChannelKind kind) {
        CommChannelConnector c = byKind.get(kind);
        if (c == null) {
            throw new UnsupportedOperationException("No connector registered for channel kind " + kind);
        }
        return c;
    }

    /** True when a real (non-stub) connector backs this kind. */
    public boolean supports(CommChannelKind kind) {
        CommChannelConnector c = byKind.get(kind);
        return c != null && c.supported();
    }
}
