package io.orphos.eip.mailinsight.channels;

import io.orphos.eip.mailinsight.inference.NlQueryClient;
import io.orphos.eip.mailinsight.model.MailInsightDto;
import io.orphos.eip.mailinsight.model.SourceChannel;
import io.orphos.eip.mailinsight.repo.MailInsightRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The AI actions behind channel items in the Tasks/Notifications feed and behind
 * the comms MCP tools ({@code comms.draft_reply}/{@code comms.send_message}).
 * Two-step contract so nothing is posted unreviewed: {@code draft} returns an
 * editable proposal (side-effect-free), {@code send} posts exactly what was
 * confirmed back to the originating channel via its connector.
 */
@Service
public class ChannelAgentService {

    private static final Logger log = LogManager.getLogger(ChannelAgentService.class);

    public static final String ACTION_REPLY = "REPLY";

    static final String DRAFT_PROMPT_KEY = "comms-drafting.reply";
    static final String DRAFT_USAGE_TYPE = "comms-drafting";

    private static final String DEFAULT_DRAFT_TEMPLATE = """
            You are drafting a concise, professional reply to a team chat message for a CXO.
            Reply with ONLY the message text — no preamble, no markdown, no signature block.
            Keep it short and appropriate for {{CHANNEL}}.

            Original message:
            - from: {{FROM}}
            - text: {{BODY}}

            Extra instruction from the user (may be empty): {{INSTRUCTION}}
            """;

    private final MailInsightRepository insightRepo;
    private final ChannelConfigRepository configRepo;
    private final CommChannelConnectorRegistry registry;
    private final ChannelScanService scanService;
    private final NlQueryClient nlQuery;
    private final boolean enabled;

    public ChannelAgentService(MailInsightRepository insightRepo,
                               ChannelConfigRepository configRepo,
                               CommChannelConnectorRegistry registry,
                               ChannelScanService scanService,
                               NlQueryClient nlQuery,
                               @Value("${mail-insight.channels.agent.enabled:true}") boolean enabled) {
        this.insightRepo = insightRepo;
        this.configRepo = configRepo;
        this.registry = registry;
        this.scanService = scanService;
        this.nlQuery = nlQuery;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Produce an editable reply draft for a channel item. Never sends. Throws
     * {@link IllegalArgumentException} if the item is unknown or not a channel item.
     */
    public Map<String, Object> draft(String itemId, String instruction) {
        MailInsightDto item = requireChannelItem(itemId);
        String channel = prettyChannel(item.source());
        String body = item.summary() == null || item.summary().isBlank() ? item.title() : item.summary();

        String template = nlQuery.promptText(DRAFT_PROMPT_KEY).orElse(DEFAULT_DRAFT_TEMPLATE);
        String prompt = template
                .replace("{{CHANNEL}}", channel)
                .replace("{{FROM}}", nullSafe(item.senderDisplay()))
                .replace("{{BODY}}", nullSafe(body))
                .replace("{{INSTRUCTION}}", instruction == null ? "" : instruction);

        Optional<String> completion = nlQuery.complete(prompt, DRAFT_USAGE_TYPE);
        String bodyText = completion.map(String::trim).filter(s -> !s.isBlank())
                .orElseGet(() -> "Thanks — noted. I'll follow up on this shortly.");

        Map<String, Object> out = new HashMap<>();
        out.put("itemId", itemId);
        out.put("action", ACTION_REPLY);
        out.put("channel", item.source());
        out.put("bodyText", bodyText);
        return out;
    }

    /**
     * Post a reply back to the channel the item came from. Routes item →
     * account → connection → connector; throws {@link IllegalStateException}
     * with an actionable message when routing/config is incomplete.
     */
    public Map<String, Object> send(String itemId, String bodyText) {
        if (!enabled) throw new IllegalStateException("The communications agent is disabled for this tenant.");
        if (bodyText == null || bodyText.isBlank()) {
            throw new IllegalArgumentException("bodyText is required");
        }
        MailInsightDto item = requireChannelItem(itemId);

        Map<String, Object> account = configRepo.findAccount(item.mailboxId());
        if (account == null) {
            throw new IllegalStateException("The channel this item came from is no longer monitored.");
        }
        Map<String, Object> config = configRepo.getConnectionById(str(account, "channelConnectionId"));
        if (config == null) {
            throw new IllegalStateException("The channel connection for this item no longer exists.");
        }
        CommChannelKind kind = CommChannelKind.parse(str(config, "kind"));
        if (kind == null || !registry.supports(kind)) {
            throw new IllegalStateException("No connector available for this channel kind.");
        }
        CommChannelConnector connector = registry.require(kind);
        CommChannelConnector.Descriptor desc = scanService.resolveDescriptor(config, kind);
        connector.sendMessage(desc, str(account, "externalId"), bodyText);

        log.info("Comms agent sent a reply to {} account {}", kind, account.get("id"));
        Map<String, Object> out = new HashMap<>();
        out.put("sent", true);
        out.put("itemId", itemId);
        out.put("channel", item.source());
        return out;
    }

    private MailInsightDto requireChannelItem(String itemId) {
        MailInsightDto item = insightRepo.findById(itemId);
        if (item == null) {
            throw new IllegalArgumentException("Unknown item: " + itemId);
        }
        if (!isChannelSource(item.source())) {
            throw new IllegalArgumentException("Item " + itemId + " is not a communication-channel item.");
        }
        return item;
    }

    private static boolean isChannelSource(String source) {
        if (source == null) return false;
        String s = source.trim().toUpperCase();
        return !SourceChannel.EMAIL.equals(s) && !SourceChannel.DATA.equals(s);
    }

    private static String prettyChannel(String source) {
        if (source == null) return "the channel";
        return switch (source.trim().toUpperCase()) {
            case SourceChannel.SLACK -> "Slack";
            case SourceChannel.MS_TEAMS -> "Microsoft Teams";
            default -> "the channel";
        };
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
