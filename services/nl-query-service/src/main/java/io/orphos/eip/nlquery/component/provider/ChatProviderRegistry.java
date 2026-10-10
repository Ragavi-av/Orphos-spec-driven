package io.orphos.eip.nlquery.component.provider;

import io.orphos.eip.nlquery.vo.ProviderOverrides;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChatProviderRegistry {

    private static final Logger log = LogManager.getLogger(ChatProviderRegistry.class);

    /**
     * Id of the bundled in-house provider. This is {@link OllamaChatProvider}'s own
     * id as well as the ultimate fallback — it is NOT the same thing as "which
     * provider a caller gets when it does not name one", which is
     * {@link #defaultProviderId}.
     */
    public static final String DEFAULT_PROVIDER_ID = "inhouse";

    private final Map<String, ChatProvider> providers = new LinkedHashMap<>();

    /**
     * Provider used when a caller names none. Configurable so an operator can
     * point the whole service at a fast external model (e.g. {@code gemma} for
     * Google AI) without every caller having to pass a provider id, and without
     * a per-tenant usage assignment being configured.
     */
    private final String defaultProviderId;

    public ChatProviderRegistry(List<ChatProvider> discovered,
                                @Value("${nlquery.default-provider:" + DEFAULT_PROVIDER_ID + "}")
                                String defaultProviderId) {
        // Preserve insertion order so the in-house provider stays first in the picker.
        discovered.stream()
                .sorted((a, b) -> Boolean.compare(!a.isInternal(), !b.isInternal()))
                .forEach(p -> providers.put(p.id(), p));
        this.defaultProviderId =
                (defaultProviderId == null || defaultProviderId.isBlank())
                        ? DEFAULT_PROVIDER_ID : defaultProviderId.trim();
        if (!providers.containsKey(this.defaultProviderId)) {
            log.warn("Configured default provider '{}' is not registered — falling back to '{}'",
                    this.defaultProviderId, DEFAULT_PROVIDER_ID);
        }
    }

    public ChatProvider resolve(String id) {
        if (id == null || id.isBlank()) {
            return defaultProvider();
        }
        ChatProvider p = providers.get(id);
        if (p == null) {
            throw new IllegalArgumentException("Unknown AI provider: " + id);
        }
        return p;
    }

    /**
     * The configured default, but only if it can actually serve a request — an
     * external provider whose API key is missing would otherwise fail every
     * unqualified call. Falls back to the always-available in-house provider.
     */
    private ChatProvider defaultProvider() {
        ChatProvider configured = providers.get(defaultProviderId);
        if (configured != null) {
            if (configured.isAvailable(ProviderOverrides.empty())) return configured;
            log.warn("Default provider '{}' is unavailable (missing API key?) — falling back to '{}'",
                    defaultProviderId, DEFAULT_PROVIDER_ID);
        }
        return providers.get(DEFAULT_PROVIDER_ID);
    }

    /**
     * The configured default provider id, as advertised to clients by
     * {@code GET /api/nl-query/providers}. Reports the in-house id when the
     * configured provider is not registered or has no usable key, so a client
     * never selects a provider that would fail.
     */
    public String defaultProviderId() {
        ChatProvider p = defaultProvider();
        return p == null ? DEFAULT_PROVIDER_ID : p.id();
    }

    /** Lookup by id without throwing — returns null when the provider isn't registered. */
    public ChatProvider find(String id) {
        return id == null ? null : providers.get(id);
    }

    public Collection<ChatProvider> all() {
        return providers.values();
    }
}
