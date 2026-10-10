package io.orphos.eip.mailinsight.channels;

import io.orphos.eip.mailinsight.graph.GraphItem;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Placeholder Discord connector so the kind is enumerable in the admin UI as
 * "coming soon". {@link #supported()} is false, so the registry reports it as
 * pending and the admin API refuses to create connections of this kind until a
 * real implementation lands (mirrors doc-ingest's pending connectors).
 */
@Component
public class DiscordConnector implements CommChannelConnector {

    @Override
    public CommChannelKind kind() {
        return CommChannelKind.DISCORD;
    }

    @Override
    public boolean supported() {
        return false;
    }

    @Override
    public ProbeResult test(Descriptor desc) {
        return ProbeResult.fail("Discord connector is not implemented yet");
    }

    @Override
    public List<GraphItem> listMessages(Descriptor desc, String externalId, int windowDays) {
        throw new UnsupportedOperationException("Discord connector is not implemented yet");
    }
}
