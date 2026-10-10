package io.orphos.eip.nlquery.component;

import io.orphos.eip.nlquery.component.provider.ChatProvider;
import io.orphos.eip.nlquery.vo.TenantProviderConfig;
import io.orphos.eip.secrets.SecretsClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped provider configuration: the admin-controlled toggle, API key,
 * model, and default-provider flag for each {@link ChatProvider} within a
 * tenant.
 *
 * <p>Split persistence by sensitivity:</p>
 * <ul>
 *   <li>The <b>API key</b> stays in OpenBao at
 *       {@code tenants/{tenantId}/providers/{providerId}} (field {@code api_key})
 *       so the secret never leaves the vault.</li>
 *   <li>The <b>non-secret</b> state (enabled / default / model) lives in ArcadeDB
 *       via {@link TenantProviderMetaStore}. OpenBao runs in dev mode (in-memory)
 *       here and is wiped on restart; keeping the admin's enabled/default/model
 *       choices in the durable database is what makes them survive — the key is
 *       re-entered if the vault is reset, but the configuration is not lost.</li>
 * </ul>
 *
 * <p>Resolution priority for {@link io.orphos.eip.nlquery.component.provider.ChatProvider#chat}
 * is request override → per-user → tenant → server default; see the providers
 * for the implementation. The tenant-default flag is consumed by the
 * {@code /api/nl-query/providers} response so the UI picks the right initial
 * selection without leaking tenant state into localStorage.</p>
 */
@Component
public class TenantProviderConfigStore {

    static final String FIELD_API_KEY = "api_key";

    private final SecretsClient vault;
    private final TenantProviderMetaStore meta;

    public TenantProviderConfigStore(SecretsClient vault, TenantProviderMetaStore meta) {
        this.vault = vault;
        this.meta = meta;
    }

    public boolean isBackendConfigured() {
        return vault.isConfigured();
    }

    /** Returns the saved config or an empty record (enabled=false) if nothing is stored yet. */
    public TenantProviderConfig read(UUID tenantId, String providerId) {
        if (tenantId == null || providerId == null || providerId.isBlank()) {
            return TenantProviderConfig.empty();
        }
        TenantProviderMetaStore.TenantProviderMeta m = meta.read(tenantId, providerId);
        Optional<String> apiKey = vault.isConfigured()
                ? vault.readField(path(tenantId, providerId), FIELD_API_KEY).filter(s -> !s.isBlank())
                : Optional.empty();
        return new TenantProviderConfig(m.enabled(), apiKey, m.model(), m.isDefault());
    }

    /**
     * Persist the full record for a (tenant, provider) pair. The non-secret
     * fields (enabled / default / model) go to the durable ArcadeDB mirror; the
     * API key is written to OpenBao when present, or removed when absent.
     */
    public void write(UUID tenantId, String providerId, TenantProviderConfig cfg) {
        requireValid(tenantId, providerId);
        if (cfg == null) cfg = TenantProviderConfig.empty();
        meta.upsert(tenantId, providerId, cfg.enabled(), cfg.isDefault(), cfg.model());
        if (vault.isConfigured()) {
            if (cfg.hasApiKey()) {
                vault.writeField(path(tenantId, providerId), FIELD_API_KEY, cfg.apiKey().get());
            } else {
                // No key (in-house provider, or key explicitly cleared) → drop the
                // OpenBao secret document so a stale key never lingers.
                vault.delete(path(tenantId, providerId));
            }
        }
    }

    /**
     * Overwrites the config preserving the existing API key when {@code apiKey}
     * is null. Use this for "save settings" flows where the admin did not retype
     * the key — secrets stay put, the rest rotates. To clear a key explicitly,
     * pass an empty string.
     */
    public void writePreservingKey(UUID tenantId,
                                   String providerId,
                                   boolean enabled,
                                   String apiKey,
                                   String model,
                                   boolean isDefault) {
        TenantProviderConfig existing = read(tenantId, providerId);
        Optional<String> resolvedKey;
        if (apiKey == null) {
            resolvedKey = existing.apiKey();
        } else if (apiKey.isBlank()) {
            resolvedKey = Optional.empty();
        } else {
            resolvedKey = Optional.of(apiKey.trim());
        }
        // Mirror the API-key semantics so a "save settings" that doesn't carry
        // the model (null / absent) keeps the previously stored model instead of
        // silently wiping it. Only an explicit blank string clears it.
        Optional<String> resolvedModel;
        if (model == null) {
            resolvedModel = existing.model();
        } else if (model.isBlank()) {
            resolvedModel = Optional.empty();
        } else {
            resolvedModel = Optional.of(model.trim());
        }
        write(tenantId, providerId,
                new TenantProviderConfig(enabled, resolvedKey, resolvedModel, isDefault));
    }

    public void delete(UUID tenantId, String providerId) {
        if (tenantId == null || providerId == null || providerId.isBlank()) return;
        meta.delete(tenantId, providerId);
        if (vault.isConfigured()) vault.delete(path(tenantId, providerId));
    }

    /**
     * Convenience for the chat providers: returns the stored API key only when
     * the tenant has the provider enabled. Disabled rows never leak keys into
     * call paths, even if the column is still populated.
     */
    public Optional<String> readEnabledKey(UUID tenantId, String providerId) {
        TenantProviderConfig cfg = read(tenantId, providerId);
        return cfg.enabled() ? cfg.apiKey() : Optional.empty();
    }

    public Optional<String> readEnabledModel(UUID tenantId, String providerId) {
        TenantProviderConfig cfg = read(tenantId, providerId);
        return cfg.enabled() ? cfg.model() : Optional.empty();
    }

    public boolean isEnabled(UUID tenantId, String providerId) {
        return read(tenantId, providerId).enabled();
    }

    public boolean isDefault(UUID tenantId, String providerId) {
        TenantProviderConfig cfg = read(tenantId, providerId);
        return cfg.enabled() && cfg.isDefault();
    }

    private void requireValid(UUID tenantId, String providerId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
    }

    private String path(UUID tenantId, String providerId) {
        return "tenants/" + tenantId + "/providers/" + sanitise(providerId);
    }

    private String sanitise(String s) {
        return s.replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }
}
