package io.orphos.eip.mailinsight.channels;

import io.orphos.eip.mailinsight.graph.GraphItem;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Placeholder Google Chat connector so the kind is enumerable in the admin UI as
 * "coming soon". {@link #supported()} is false — see {@link DiscordConnector}.
 */
@Component
public class GoogleChatConnector implements CommChannelConnector {

    @Override
    public CommChannelKind kind() {
        return CommChannelKind.GOOGLE_CHAT;
    }

    @Override
    public boolean supported() {
        return false;
    }

    @Override
    public ProbeResult test(Descriptor desc) {
        return ProbeResult.fail("Google Chat connector is not implemented yet");
    }

    @Override
    public List<GraphItem> listMessages(Descriptor desc, String externalId, int windowDays) {
        throw new UnsupportedOperationException("Google Chat connector is not implemented yet");
    }
}
