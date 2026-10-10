package io.orphos.eip.docingest.source;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a {@link SourceConnector} by {@link SourceKind}. Populated from every
 * {@code SourceConnector} bean on the classpath, so adding a new kind (FTP,
 * Confluence, Teams) is just adding a bean — no change here.
 */
@Component
public class SourceConnectorRegistry {

    private final Map<SourceKind, SourceConnector> byKind;

    public SourceConnectorRegistry(List<SourceConnector> connectors) {
        this.byKind = connectors.stream()
                .collect(Collectors.toUnmodifiableMap(SourceConnector::kind, Function.identity()));
    }

    public Optional<SourceConnector> find(SourceKind kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    /** Connector for {@code kind}, or a clear error if that kind isn't implemented yet. */
    public SourceConnector require(SourceKind kind) {
        SourceConnector c = byKind.get(kind);
        if (c == null) {
            throw new UnsupportedOperationException("Source connector for " + kind + " is not implemented yet");
        }
        return c;
    }

    public boolean supports(SourceKind kind) {
        return byKind.containsKey(kind);
    }
}
