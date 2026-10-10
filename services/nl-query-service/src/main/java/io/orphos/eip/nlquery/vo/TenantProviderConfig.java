package io.orphos.eip.nlquery.vo;

import io.orphos.eip.nlquery.component.provider.ChatProvider;
import java.util.Optional;

/**
 * Tenant-scoped configuration for a single {@link ChatProvider}.
 *
 * <p>The {@code apiKey} is never echoed back over HTTP — admin endpoints return
 * a redacted summary ({@code configured: true/false}) so the secret leaves
 * OpenBao only when a chat call actually needs it.</p>
 *
 * <p>{@code isDefault} marks the tenant's preferred provider; the end-user UI
 * uses it as the initial selection when no per-user preference is stored.
 * Only one provider per tenant should carry the flag; the admin controller
 * enforces single-default semantics when persisting.</p>
 */
public record TenantProviderConfig(
        boolean enabled,
        Optional<String> apiKey,
        Optional<String> model,
        boolean isDefault) {

    public TenantProviderConfig {
        if (apiKey == null) apiKey = Optional.empty();
        if (model  == null) model  = Optional.empty();
    }

    public static TenantProviderConfig empty() {
        return new TenantProviderConfig(false, Optional.empty(), Optional.empty(), false);
    }

    public boolean hasApiKey() {
        return apiKey.isPresent() && !apiKey.get().isBlank();
    }
}
