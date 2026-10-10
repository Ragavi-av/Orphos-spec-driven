package io.orphos.eip.webbff.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One downstream call's result: the payload plus enough context for the dashboard to
 * explain itself when a tile cannot be filled.
 *
 * @param name   the key this source appears under in the aggregate's {@code data} map
 * @param status how the call ended — see {@link SourceStatus}
 * @param body   the parsed payload, or {@code null} for any non-OK status
 * @param detail short human-readable cause for non-OK statuses, {@code null} when OK.
 *               Deliberately a message and never the downstream's raw error body, which
 *               can carry internal hostnames and stack traces the browser must not see.
 */
public record SourceResult(String name, SourceStatus status, JsonNode body, String detail) {

    public static SourceResult ok(String name, JsonNode body) {
        return new SourceResult(name, SourceStatus.OK, body, null);
    }

    public static SourceResult failed(String name, SourceStatus status, String detail) {
        return new SourceResult(name, status, null, detail);
    }

    public boolean isOk() {
        return status == SourceStatus.OK;
    }
}
