package io.orphos.eip.nlquery.vo;

/**
 * Per-request overrides supplied by the caller. Both fields are optional:
 * when null/blank the provider falls back to its configured defaults.
 * Secrets are never persisted server-side — they are accepted only for the
 * duration of a single request.
 */
public record ProviderOverrides(String apiKey, String model) {
    public static ProviderOverrides empty() {
        return new ProviderOverrides(null, null);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasModel() {
        return model != null && !model.isBlank();
    }
}
