package io.orphos.eip.mailinsight.model;

import java.util.Set;

/**
 * Origin vocabulary for {@link MailInsightDto#source()} — shared by both
 * "Tasks for Action" and "Notifications" since they're both backed by the
 * same {@code MailInsight} document type (distinguished only by {@code kind}).
 *
 * <p>{@code EMAIL} covers every mailbox-access protocol this service currently
 * supports (Microsoft Graph, IMAP for Google/Zoho/other) — the protocol is an
 * implementation detail of {@code MailMonitorConfig.provider}, not a distinct
 * channel to the end user. {@code SLACK}/{@code MS_TEAMS} are ingested by the
 * communication-channel connectors (see
 * {@link io.orphos.eip.mailinsight.channels.CommChannelKind}); {@code DISCORD}
 * and {@code GOOGLE_CHAT} are reserved for connectors that are declared but not
 * yet implemented (a row tagged with one round-trips unchanged today).
 * {@code DATA} is not a communication channel at all — it marks items
 * {@link io.orphos.eip.mailinsight.datasource.DataSourceReader} synthesizes
 * on the fly from the tenant's live ontology data (decisions, incidents,
 * alerts, SLA breaches, budget overruns) rather than from a monitored inbox
 * or chat channel.</p>
 */
public final class SourceChannel {

    public static final String EMAIL       = "EMAIL";
    public static final String MS_TEAMS    = "MS_TEAMS";
    public static final String SLACK       = "SLACK";
    public static final String DISCORD     = "DISCORD";
    public static final String GOOGLE_CHAT = "GOOGLE_CHAT";
    public static final String DATA        = "DATA";

    public static final Set<String> ALL = Set.of(EMAIL, MS_TEAMS, SLACK, DISCORD, GOOGLE_CHAT, DATA);

    /** Channels ingested from a chat/collaboration platform (everything except EMAIL and DATA). */
    public static final Set<String> COMMUNICATION_CHANNELS = Set.of(MS_TEAMS, SLACK, DISCORD, GOOGLE_CHAT);

    private SourceChannel() {}

    /**
     * Maps a {@code MailMonitorConfig.provider} value (MS_GRAPH | GOOGLE | ZOHO |
     * IMAP — all mailbox-access protocols) to the channel it represents. Falls
     * back to {@link #EMAIL} for null/blank/unrecognised values so legacy
     * configs and sample-mode scans still get a valid channel.
     */
    public static String fromMailProvider(String provider) {
        if (provider == null || provider.isBlank()) return EMAIL;
        String p = provider.trim().toUpperCase();
        return switch (p) {
            case "MS_GRAPH", "GOOGLE", "ZOHO", "IMAP" -> EMAIL;
            case "MS_TEAMS", "SLACK" -> p;
            default -> EMAIL;
        };
    }
}
