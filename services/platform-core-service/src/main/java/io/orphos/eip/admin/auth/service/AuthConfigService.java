package io.orphos.eip.admin.auth.service;

import io.orphos.eip.admin.auth.dto.Dtos;
import io.orphos.eip.admin.auth.model.AuthMethodType;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * A4 - Authentication method configuration.
 *
 * Manages global enabled-flags and the list of registered IdentityProvider records.
 * Secrets in provider configs are redacted on read.
 *
 * <p>OIDC providers are additionally federated into the tenant's Keycloak realm
 * (via {@link KeycloakProvisioningClient}) so the "Sign in with …" button appears
 * on the login page. Persisting the record alone is not enough — Keycloak is the
 * thing that renders the login form.</p>
 */
@Service
public class AuthConfigService {

    private static final Logger log = LogManager.getLogger(AuthConfigService.class);

    private final AuthRepository repo;
    private final IdpSecretStore idpSecrets;
    private final KeycloakProvisioningClient keycloak;
    private final TenantRepository tenants;

    @Autowired
    public AuthConfigService(AuthRepository repo, IdpSecretStore idpSecrets,
                             KeycloakProvisioningClient keycloak, TenantRepository tenants) {
        this.repo = repo;
        this.idpSecrets = idpSecrets;
        this.keycloak = keycloak;
        this.tenants = tenants;
    }

    /** Test-only constructor - lets unit tests verify guardrails without standing up OpenBao or Keycloak. */
    AuthConfigService(AuthRepository repo) {
        this(repo, new IdpSecretStore(new io.orphos.eip.admin.secrets.SecretRefService(
                new io.orphos.eip.secrets.SecretsClient("", "", "", "", ""))), null, null);
    }

    public Dtos.AuthMethodConfigView getConfig() {
        Map<String, Object> row = repo.getOrInitAuthMethodConfig();
        return new Dtos.AuthMethodConfigView(
                bool(row.get("localEnabled"), true),
                bool(row.get("oidcEnabled"), false),
                bool(row.get("samlEnabled"), false),
                bool(row.get("ldapEnabled"), false),
                AuthMethodType.valueOf(Objects.toString(row.getOrDefault("defaultMethod", "LOCAL"))));
    }

    public Dtos.AuthMethodConfigView updateConfig(Dtos.AuthMethodConfigRequest req, String actorKey) {
        // Guardrail: at least one method must remain enabled, otherwise the
        // system locks itself out.
        if (!req.localEnabled() && !req.oidcEnabled() && !req.samlEnabled() && !req.ldapEnabled()) {
            throw new IllegalArgumentException("at least one authentication method must remain enabled");
        }
        if (req.defaultMethod() == null) {
            throw new IllegalArgumentException("defaultMethod is required");
        }
        repo.updateAuthMethodConfig(req.localEnabled(), req.oidcEnabled(), req.samlEnabled(),
                req.ldapEnabled(), req.defaultMethod());
        repo.appendAudit(actorKey, "AUTH_CONFIG_UPDATE", "AuthMethod", Map.of(
                "localEnabled", req.localEnabled(),
                "oidcEnabled", req.oidcEnabled(),
                "samlEnabled", req.samlEnabled(),
                "ldapEnabled", req.ldapEnabled(),
                "defaultMethod", req.defaultMethod().name()
        ));
        return getConfig();
    }

    public List<Dtos.IdentityProviderView> listProviders() {
        return repo.listProviders().stream().map(this::toView).toList();
    }

    public Dtos.IdentityProviderView upsertProvider(Dtos.IdentityProviderRequest req, String actorKey) {
        validateProviderConfig(req);
        // Federate OIDC providers into the tenant's Keycloak realm FIRST. If this
        // fails (bad issuer, unreachable IdP), we throw before persisting so the
        // admin sees a clear error instead of a saved-but-non-functional record.
        if (req.type() == AuthMethodType.OIDC) {
            federateOidcToKeycloak(req);
        }
        // Strip secret-bearing keys out of the config before it touches ArcadeDB:
        // each is rewritten to an opaque vault:... reference held in OpenBao.
        Map<String, Object> sanitisedConfig = idpSecrets.extractAndStore(req.name(), req.config());
        String id = repo.upsertProvider(req.name(), req.type(), req.enabled(),
                req.allowSelfRegistration(), req.allowedEmailDomains(), sanitisedConfig);
        repo.appendAudit(actorKey, "PROVIDER_UPSERT", req.name(),
                Map.of("type", req.type().name(), "enabled", req.enabled()));
        return repo.findProviderByName(req.name()).map(this::toView)
                .orElseThrow(() -> new IllegalStateException("provider " + id + " not found after write"));
    }

    /**
     * Pushes an OIDC provider into the current tenant's Keycloak realm so its
     * login button is rendered. The client secret is taken from the request when
     * present; on an edit that omits it (or sends the redacted {@code ***}), we
     * fall back to the secret already stored for this provider.
     */
    private void federateOidcToKeycloak(Dtos.IdentityProviderRequest req) {
        if (keycloak == null || tenants == null) return;   // unit-test / no-Keycloak path
        String realm = currentRealm();
        if (realm == null || realm.isBlank()) {
            throw new IllegalStateException("Cannot resolve the Keycloak realm for the current tenant; "
                    + "SSO cannot be provisioned.");
        }
        Map<String, Object> cfg = new HashMap<>(req.config() == null ? Map.of() : req.config());
        Object secret = cfg.get("clientSecret");
        if (secret == null || secret.toString().isBlank() || "***".equals(secret.toString())) {
            // Reuse the previously-stored secret so a config edit that doesn't
            // re-enter it doesn't silently drop the credential.
            Object stored = resolveProviderConfig(req.name()).get("clientSecret");
            if (stored != null) cfg.put("clientSecret", stored);
        }
        keycloak.upsertOidcIdentityProvider(realm, idpAlias(req.name()), req.name(), cfg, req.enabled());
    }

    /** Resolves the Keycloak realm name for the tenant bound to the current request. */
    private String currentRealm() {
        UUID tenantId = TenantContextHolder.requireTenantId();
        return tenants.findById(tenantId).map(Tenant::keycloakRealm).orElse(null);
    }

    /** Stable, Keycloak-safe alias derived from the provider's display name. */
    static String idpAlias(String name) {
        String a = name == null ? "" : name.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        return a.isBlank() ? "oidc" : a;
    }

    /**
     * Runtime resolution path - returns a config map with vault refs replaced
     * by their resolved plaintext values. Only callers that actually need to
     * make an outbound call (OIDC token exchange, LDAP manager bind, SAML
     * signing) should use this; everything else should call {@link #listProviders()}
     * or {@link #toView(Map)} so secrets stay opaque.
     */
    public Map<String, Object> resolveProviderConfig(String providerName) {
        return repo.findProviderByName(providerName)
                .map(row -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cfg = (Map<String, Object>) row.getOrDefault("config", Map.of());
                    return idpSecrets.resolveAll(cfg);
                })
                .orElse(Map.of());
    }

    public void deleteProvider(String name, String actorKey) {
        // Remove the federated Keycloak identity provider first so the login
        // button disappears even if the DB cleanup below partially fails.
        if (keycloak != null && tenants != null) {
            String realm = currentRealm();
            if (realm != null && !realm.isBlank()) {
                keycloak.deleteIdentityProvider(realm, idpAlias(name));
            }
        }
        // Best-effort secret cleanup before the row goes - prevents orphaned
        // vault entries when a provider is removed.
        repo.findProviderByName(name).ifPresent(row -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) row.getOrDefault("config", Map.of());
            idpSecrets.purge(cfg);
        });
        repo.deleteProvider(name);
        repo.appendAudit(actorKey, "PROVIDER_DELETE", name, Map.of());
    }

    /** Per-type minimal config validation. */
    private void validateProviderConfig(Dtos.IdentityProviderRequest req) {
        Map<String, Object> cfg = req.config() == null ? Map.of() : req.config();
        switch (req.type()) {
            case OIDC -> requireKeys(cfg, "issuerUri", "clientId");
            case SAML -> requireKeys(cfg, "metadataUrl", "entityId");
            case LDAP -> requireKeys(cfg, "url", "base", "userDnPattern");
            case LOCAL -> { /* no extra config */ }
        }
    }

    private static void requireKeys(Map<String, Object> cfg, String... keys) {
        for (String k : keys) {
            Object v = cfg.get(k);
            if (v == null || v.toString().isBlank()) {
                throw new IllegalArgumentException("config." + k + " is required");
            }
        }
    }

    private Dtos.IdentityProviderView toView(Map<String, Object> row) {
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) row.getOrDefault("config", Map.of());
        Map<String, Object> safe = new HashMap<>(cfg);
        // Secret values are already vault refs at rest (no plaintext left in
        // ArcadeDB); we still redact them on read so the admin UI doesn't
        // expose the ref token to operators who shouldn't be copy-pasting it.
        for (String k : IdpSecretStore.SECRET_KEYS) {
            if (safe.containsKey(k)) safe.put(k, "***");
        }
        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) row.getOrDefault("allowedEmailDomains", List.of());
        return new Dtos.IdentityProviderView(
                Objects.toString(row.get("id")),
                Objects.toString(row.get("name")),
                AuthMethodType.valueOf(Objects.toString(row.getOrDefault("type", "LOCAL"))),
                bool(row.get("enabled"), false),
                bool(row.get("allowSelfRegistration"), false),
                domains,
                safe);
    }

    private static boolean bool(Object o, boolean fallback) {
        if (o instanceof Boolean b) return b;
        if (o == null) return fallback;
        return Boolean.parseBoolean(o.toString());
    }
}
