package io.orphos.eip.mailinsight.channels;

/**
 * Kinds of communication channel a tenant can attach as a monitored connection —
 * a chat/collaboration platform whose messages are ingested, classified and
 * surfaced in the same Tasks/Notifications feed as email (see
 * {@link io.orphos.eip.mailinsight.model.SourceChannel}).
 *
 * <p>Each kind maps to exactly one {@link CommChannelConnector} bean. SLACK and
 * MS_TEAMS ship with real connectors; DISCORD and GOOGLE_CHAT are declared here
 * so the admin UI can list them as "coming soon" and so a row tagged with one
 * round-trips unchanged — their connector beans throw
 * {@link UnsupportedOperationException} until implemented (mirrors the
 * doc-ingest SourceKind "pending" pattern).</p>
 */
public enum CommChannelKind {
    SLACK,
    MS_TEAMS,
    DISCORD,
    GOOGLE_CHAT;

    /** Tolerant parse — returns null for null/blank/unknown values. */
    public static CommChannelKind parse(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
