package io.orphos.eip.nlquery.vo;

import java.util.List;

/**
 * The trust envelope attached to every generated answer. It tells the reader
 * <em>how much to trust</em> a briefing without the reader having to inspect
 * the underlying data:
 *
 * <ul>
 *   <li>{@link #grounding()} — was the answer built from the tenant's real data
 *       (GROUNDED), partly (PARTIAL), or synthesised from best practice when no
 *       matching data existed (SYNTHESIZED, i.e. Rule 6 fired)?</li>
 *   <li>{@link #confidence()} — a 0..1 score folding grounding, the
 *       self-check verdict, and any deterministic contradictions.</li>
 *   <li>{@link #verification()} — did the post-generation groundedness
 *       self-check pass, flag unsupported claims, or get skipped?</li>
 *   <li>{@link #caveats()} — human-readable notes (unsupported claims from the
 *       self-check, contradictions from the OWL reasoner cross-check).</li>
 *   <li>{@link #sources()} — provenance: which retrieved records fed the answer,
 *       with their relevance scores, so the briefing is auditable even though
 *       Rule 2 forbids raw IDs in the prose itself.</li>
 * </ul>
 *
 * <p>Everything here is best-effort. When a signal cannot be computed (the
 * self-check errors, the reasoner is unreachable) the pipeline degrades to a
 * lower-confidence, SKIPPED verification rather than failing the answer.</p>
 */
public record TrustSignal(
        Grounding grounding,
        double confidence,
        Verification verification,
        List<String> caveats,
        List<Source> sources) {

    /** How strongly the answer is anchored in the tenant's actual data. */
    public enum Grounding { GROUNDED, PARTIAL, SYNTHESIZED }

    /** Outcome of the post-generation groundedness self-check. */
    public enum Verification { PASSED, FLAGGED, SKIPPED }

    /** One retrieved record that informed the answer (provenance). */
    public record Source(String type, String id, double relevance) {}
}
