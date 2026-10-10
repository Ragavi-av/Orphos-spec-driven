package io.orphos.eip.webbff.model;

/**
 * Outcome of one downstream call, reported per source in every aggregate response.
 *
 * <p>The web dashboard needs to tell "the service answered, and the answer was empty"
 * apart from "the service never answered" — the two render very differently (an empty
 * state vs. a degraded badge). A BFF that collapses both into a null body throws that
 * distinction away, so every fan-out result carries one of these instead.</p>
 */
public enum SourceStatus {

    /** 2xx. {@code body} holds the parsed payload (possibly an empty array/object). */
    OK,

    /** 401/403 from the downstream service — the caller's token was rejected, not the BFF's. */
    FORBIDDEN,

    /** 404. The route exists in config but the downstream has nothing at that path. */
    NOT_FOUND,

    /** 5xx, connect/read timeout, or transport failure. The one status worth alerting on. */
    UNAVAILABLE
}
