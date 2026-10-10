package io.orphos.eip.webbff.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The envelope every {@code /api/web/*} screen endpoint returns.
 *
 * <p>Payloads sit under {@code data}, keyed by source name, so the shell reads
 * {@code data.plugins} rather than unpacking a bare merged object. {@code sources}
 * carries the per-call outcome and {@code partial} is a precomputed
 * "any source failed" flag, so the UI can show a degraded banner without walking
 * the status map itself.</p>
 *
 * @param data        source name to payload; a failed source maps to {@code null}
 * @param sources     source name to its {@link SourceResult} minus the body
 * @param partial     {@code true} when at least one source did not return OK
 * @param generatedAt server-side aggregation timestamp, for cache/staleness display
 */
public record AggregateResponse(Map<String, JsonNode> data,
                                Map<String, SourceDiagnostic> sources,
                                boolean partial,
                                Instant generatedAt) {

    /** The status half of a {@link SourceResult}, serialised alongside the data. */
    public record SourceDiagnostic(SourceStatus status, String detail) {
    }

    public static AggregateResponse of(List<SourceResult> results) {
        Map<String, JsonNode> data = new LinkedHashMap<>();
        Map<String, SourceDiagnostic> sources = new LinkedHashMap<>();
        boolean partial = false;
        for (SourceResult r : results) {
            data.put(r.name(), r.body());
            sources.put(r.name(), new SourceDiagnostic(r.status(), r.detail()));
            if (!r.isOk()) {
                partial = true;
            }
        }
        return new AggregateResponse(data, sources, partial, Instant.now());
    }
}
