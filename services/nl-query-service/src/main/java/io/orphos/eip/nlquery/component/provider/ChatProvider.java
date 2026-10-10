package io.orphos.eip.nlquery.component.provider;

import io.orphos.eip.nlquery.vo.ProviderOverrides;

public interface ChatProvider {

    /** Stable identifier used by the API and UI (e.g. "inhouse", "gemma"). */
    String id();

    /** Human-readable label shown in the UI. */
    String label();

    /** One-line description shown in the provider picker. */
    String description();

    /** True if the provider is wired to a working backend right now. */
    boolean isAvailable(ProviderOverrides overrides);

    /** Returns true when the provider is in-process and needs no external secrets. */
    boolean isInternal();

    /** Send a single prompt and return the raw response text. */
    String chat(String prompt, ProviderOverrides overrides);

    /**
     * Optional model-specific guidance appended to the prompt before the call.
     * Lets each provider nudge its own model toward the structured briefing
     * contract (e.g. honouring the ===FOLLOWUPS=== delimiter). Default is
     * empty — providers override only when needed.
     */
    default String tuningSuffix() { return ""; }

    /**
     * Reports the model name the provider will actually use for the next call
     * given the supplied overrides. Used by the usage recorder so audit rows
     * carry the resolved model rather than just the provider id.
     */
    default String resolveModelName(ProviderOverrides overrides) { return id(); }

    /**
     * Base URL of the Ollama runtime serving this provider, or {@code null}
     * for providers not backed by a local Ollama container. Lets the
     * availability probe check that the model is actually loaded.
     */
    default String ollamaBaseUrl() { return null; }
}
