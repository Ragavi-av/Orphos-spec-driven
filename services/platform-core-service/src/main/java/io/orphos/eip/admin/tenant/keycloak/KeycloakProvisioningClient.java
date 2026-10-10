package io.orphos.eip.admin.tenant.keycloak;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RolesRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import io.orphos.eip.common.security.EipRoles;
import io.orphos.eip.secrets.SecretsClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Provisions a fresh Keycloak realm per tenant via the Keycloak Admin REST API.
 *
 * <p>The realm template mirrors {@code config/keycloak/eip-realm.json} but is
 * built programmatically because (a) the template contains placeholder Azure
 * IdP credentials we don't want to import blindly into a new tenant, and
 * (b) RealmRepresentation gives us strict typing for downstream changes.</p>
 *
 * <p>Operations are idempotent — {@link #ensureRealm} skips creation if a realm
 * with the same name already exists. This is how the demo tenant's pre-existing
 * {@code eip} realm coexists with the per-tenant naming scheme.</p>
 *
 * <p>The bean authenticates as the {@code admin} user in Keycloak's
 * {@code master} realm; the password is loaded from environment variable
 * {@code KEYCLOAK_ADMIN_PASSWORD}, which is in turn populated from OpenBao at
 * path {@code eip/keycloak-admin#password} via {@code application.yml}'s
 * {@code openbao.bindings} block.</p>
 */
@Component
public class KeycloakProvisioningClient {

    private static final Logger log = LogManager.getLogger(KeycloakProvisioningClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Roles that exist in every tenant realm — the shared catalog; {@code eip-realm.json} must match. */
    private static final List<String> REALM_ROLES = EipRoles.TENANT_BUILT_IN;

    /** Infisical location of the Keycloak master-admin password (mirrors the binding in application.yml). */
    private static final String ADMIN_SECRET_PATH = "eip/keycloak-admin";
    private static final String ADMIN_SECRET_FIELD = "password";

    private final String serverUrl;
    private final String adminUser;
    /**
     * Mutable: initialised from config at construction, but re-resolved from
     * Infisical whenever the cached admin client is (re)built. This is what makes
     * the service recover from a boot-order race where {@code platform-core}
     * started before {@code infisical-seed} had written the secret — the first
     * provisioning call re-reads the now-present password instead of failing
     * forever with a cached 401. See {@link #resolveAdminPassword()}.
     */
    private volatile String adminPassword;
    private final String adminClientId;
    private final List<String> spaRedirectUris;
    private final List<String> spaWebOrigins;

    // Default ("platform") SSO auto-seeded into every tenant realm at creation.
    // When configured, every new tenant gets a "Sign in with Microsoft" button
    // with no per-tenant setup; left blank, tenants start with local login only.
    private final String ssoAlias;
    private final String ssoDisplayName;
    private final String ssoIssuerUri;
    private final String ssoAzureTenantId;
    private final String ssoClientId;
    private final String ssoClientSecret;

    // Single source of truth for realm structure: the SAME template the demo
    // realm is imported from. New tenants are stamped from this file so they get
    // identical scopes / IdP / mappers / client config — no hand-maintained
    // mirror to drift. Falls back to the programmatic builder if unreadable.
    @org.springframework.beans.factory.annotation.Value(
            "${eip.keycloak.provisioning.realm-template-path:/app/keycloak-templates/eip-realm.json}")
    private String realmTemplatePath;

    private Keycloak adminClient;
    private HttpClient httpClient;

    /**
     * Optional — used to re-resolve the admin password at (re)build time so a
     * secret seeded after this service started is still picked up. Field-injected
     * (not constructor) so the {@code @Value} defaults keep working and existing
     * test subclasses need no constructor change. Null when Infisical is not
     * wired (e.g. unit tests): the constructor-supplied password is used as-is.
     */
    @Autowired(required = false)
    private SecretsClient secrets;

    public KeycloakProvisioningClient(
            @Value("${eip.keycloak.admin.url:${KEYCLOAK_URL:http://eip-keycloak:8080}}") String serverUrl,
            @Value("${eip.keycloak.admin.user:admin}") String adminUser,
            @Value("${eip.keycloak.admin.password:${KEYCLOAK_ADMIN_PASSWORD:}}") String adminPassword,
            @Value("${eip.keycloak.admin.client-id:admin-cli}") String adminClientId,
            @Value("${eip.keycloak.provisioning.spa-redirect-uris:http://localhost:3001/*,http://localhost:5173/*}") String redirects,
            @Value("${eip.keycloak.provisioning.spa-web-origins:http://localhost:3001,http://localhost:5173}") String origins,
            @Value("${eip.keycloak.provisioning.sso.alias:azure}") String ssoAlias,
            @Value("${eip.keycloak.provisioning.sso.display-name:Sign in with Microsoft}") String ssoDisplayName,
            @Value("${eip.keycloak.provisioning.sso.issuer-uri:}") String ssoIssuerUri,
            @Value("${eip.keycloak.provisioning.sso.azure-tenant-id:${AZURE_TENANT_ID:}}") String ssoAzureTenantId,
            @Value("${eip.keycloak.provisioning.sso.client-id:${AZURE_CLIENT_ID:}}") String ssoClientId,
            @Value("${eip.keycloak.provisioning.sso.client-secret:${AZURE_CLIENT_SECRET:}}") String ssoClientSecret) {
        this.serverUrl = serverUrl;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.adminClientId = adminClientId;
        this.spaRedirectUris = splitCsv(redirects);
        this.spaWebOrigins = splitCsv(origins);
        this.ssoAlias = ssoAlias;
        this.ssoDisplayName = ssoDisplayName;
        this.ssoIssuerUri = ssoIssuerUri;
        this.ssoAzureTenantId = ssoAzureTenantId;
        this.ssoClientId = ssoClientId;
        this.ssoClientSecret = ssoClientSecret;
    }

    @PostConstruct
    void init() {
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("Keycloak admin password is empty — tenant realm provisioning will fail until "
                    + "KEYCLOAK_ADMIN_PASSWORD is populated (typically via OpenBao binding "
                    + "eip/keycloak-admin#password). This is fine if you're not yet creating tenants.");
        }
    }

    @PreDestroy
    void shutdown() {
        if (adminClient != null) {
            try { adminClient.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Idempotently ensures a realm with {@code realmName} exists, with a
     * {@code tenant} client scope that emits a hardcoded {@code tenant_id} claim
     * matching {@code tenantId}.
     *
     * <p>If the realm already exists, this method <b>also patches</b> the tenant
     * scope to match the supplied tenantId — see {@link #ensureRealmHasTenantScope}.
     * That's the path the demo tenant takes against the pre-existing {@code eip}
     * realm.</p>
     *
     * @return {@code true} if the realm was newly created, {@code false} if it
     *         already existed (the scope is patched either way).
     */
    public synchronized boolean ensureRealm(String realmName, String displayName, String tenantId) {
        // Re-auth wrapper: if the cached admin client holds a stale/blank token
        // (e.g. platform-core booted before the secret was seeded), the first
        // call 401s; we then rebuild the client with a freshly-resolved password
        // and retry once instead of failing the whole tenant provision.
        return withAdminRetry(() -> doEnsureRealm(realmName, displayName, tenantId));
    }

    private boolean doEnsureRealm(String realmName, String displayName, String tenantId) {
        Keycloak kc = client();
        boolean exists;
        try {
            kc.realm(realmName).toRepresentation();
            exists = true;
        } catch (jakarta.ws.rs.NotFoundException nfe) {
            exists = false;
        }

        if (exists) {
            log.info("Keycloak realm '{}' already exists — patching tenant scope only", realmName);
            ensureRealmHasTenantScope(realmName, tenantId);
            // Even on the already-exists path we re-affirm the default admin —
            // ensureDefaultAdminUser is fully idempotent (skips create if the
            // user is there, only adds the role if it is missing) and this
            // guarantees the realm is reachable with admin/admin regardless of
            // whether we just created it or are revisiting it.
            ensureDefaultAdminUser(realmName, displayName);
            // Backfill standard profile/email scopes so tokens carry name/email.
            ensureStandardClientScopes(realmName);
            // Idempotent — only seeds if the realm doesn't already carry the IdP.
            seedDefaultSso(realmName);
            return false;
        }

        // Prefer stamping the realm from the demo's template (single source of
        // truth); fall back to the programmatic builder if it can't be read/parsed.
        RealmRepresentation realm = buildRealmFromTemplate(realmName, displayName, tenantId);
        boolean fromTemplate = realm != null;
        if (!fromTemplate) {
            realm = buildRealmRepresentation(realmName, displayName, tenantId);
        }
        kc.realms().create(realm);
        log.info("Provisioned Keycloak realm '{}' (displayName='{}', tenantId={}, source={})",
                realmName, displayName, tenantId, fromTemplate ? "template" : "programmatic");

        // The template's tenant_id mapper carries a placeholder / the Azure tenant
        // id; force it to THIS tenant's canonical UUID (also attaches the scope to
        // every client). Harmless on the programmatic path too.
        ensureRealmHasTenantScope(realmName, tenantId);
        // Seed a default tenant administrator so the realm is reachable without
        // depending on Azure IdP federation. Credentials are admin/admin —
        // intentionally bootstrap-grade; the first sign-in should reset them.
        ensureDefaultAdminUser(realmName, displayName);
        // Safety nets — idempotent no-ops when the template already supplied these,
        // but they guarantee parity even on the programmatic fallback path.
        ensureStandardClientScopes(realmName);
        seedDefaultSso(realmName);
        return true;
    }

    /**
     * Builds a {@link RealmRepresentation} for a new tenant by stamping the demo
     * realm template ({@code eip-realm.json}) — the SAME file Keycloak imports for
     * the demo — so every tenant gets identical structure. Returns {@code null}
     * if the template can't be read or parsed (caller falls back to the
     * programmatic builder).
     *
     * <p>Per-tenant adjustments applied on top of the template:
     * <ul>
     *   <li>realm name / display name set for this tenant;</li>
     *   <li>Azure {@code clientId/clientSecret} + tenant id substituted from
     *       config when SSO is configured, else the Azure IdP is dropped so we
     *       never create a broken provider;</li>
     *   <li>the demo's org-specific declarative user profile (the
     *       {@code @appverse.dev} email lock) and {@code registrationEmailAsUsername}
     *       are neutralised — they would block the bootstrap {@code admin/admin}
     *       user's {@code admin@<realm>.local} address;</li>
     *   <li>SPA redirect URIs / web origins replaced with the per-environment
     *       configured values.</li>
     * </ul>
     * The {@code tenant_id} claim is corrected to this tenant's UUID by the
     * {@link #ensureRealmHasTenantScope} call in {@link #ensureRealm}.</p>
     */
    private RealmRepresentation buildRealmFromTemplate(String realmName, String displayName, String tenantId) {
        String raw = readRealmTemplate();
        if (raw == null) return null;

        boolean azureConfigured = ssoClientId != null && !ssoClientId.isBlank()
                && ssoAzureTenantId != null && !ssoAzureTenantId.isBlank();
        if (azureConfigured) {
            raw = raw.replace("REPLACE_WITH_AZURE_CLIENT_ID", ssoClientId)
                     .replace("REPLACE_WITH_AZURE_CLIENT_SECRET", ssoClientSecret == null ? "" : ssoClientSecret)
                     .replace("REPLACE_WITH_TENANT_ID", ssoAzureTenantId);
        }

        RealmRepresentation rep;
        try {
            rep = MAPPER.readValue(raw, RealmRepresentation.class);
        } catch (Exception e) {
            log.warn("Could not parse realm template '{}': {} — using programmatic builder",
                    realmTemplatePath, e.getMessage());
            return null;
        }

        rep.setRealm(realmName);
        rep.setDisplayName(displayName != null ? displayName : realmName);
        rep.setEnabled(true);
        // Bootstrap admin uses username (admin/admin) with a synthetic .local
        // email; the demo's email-as-username + @appverse.dev user-profile lock
        // would reject that, so neutralise both for tenant realms.
        rep.setRegistrationEmailAsUsername(false);
        rep.setEditUsernameAllowed(true);
        rep.setComponents(null);

        // Without Azure credentials the IdP placeholders are still in place —
        // drop the provider (and its mappers) so we don't create a broken one.
        if (!azureConfigured) {
            rep.setIdentityProviders(null);
            rep.setIdentityProviderMappers(null);
        }

        // Per-environment SPA URLs override the template's baked-in hosts.
        if (rep.getClients() != null) {
            for (ClientRepresentation c : rep.getClients()) {
                if ("orphos-spa".equals(c.getClientId())) {
                    if (!spaRedirectUris.isEmpty()) c.setRedirectUris(spaRedirectUris);
                    if (!spaWebOrigins.isEmpty()) c.setWebOrigins(spaWebOrigins);
                }
            }
        }
        return rep;
    }

    private String readRealmTemplate() {
        if (realmTemplatePath == null || realmTemplatePath.isBlank()) return null;
        try {
            java.nio.file.Path p = java.nio.file.Path.of(realmTemplatePath);
            if (!java.nio.file.Files.isReadable(p)) {
                log.debug("Realm template not found at '{}' — using programmatic builder", realmTemplatePath);
                return null;
            }
            return java.nio.file.Files.readString(p);
        } catch (Exception e) {
            log.warn("Could not read realm template '{}': {}", realmTemplatePath, e.getMessage());
            return null;
        }
    }

    /**
     * Seeds the platform's default ("shared") SSO identity provider into a realm
     * when {@code eip.keycloak.provisioning.sso.*} (or {@code AZURE_*}) is
     * configured. Idempotent and non-fatal: it skips realms that already carry
     * the alias (e.g. the demo {@code eip} realm's imported {@code azure} IdP)
     * and never throws, so a discovery hiccup can't break tenant creation.
     */
    public synchronized void seedDefaultSso(String realmName) {
        String issuer = resolveSsoIssuer();
        if (ssoClientId == null || ssoClientId.isBlank() || issuer.isBlank()) {
            log.debug("Default SSO not configured (need client-id + issuer/tenant) — "
                    + "skipping seed for realm '{}'", realmName);
            return;
        }
        try {
            // Don't clobber an IdP that's already present — but do backfill its role
            // mappers, which realms seeded by older builds never received.
            try {
                RealmResource realm = client().realm(realmName);
                realm.identityProviders().get(ssoAlias).toRepresentation();
                log.debug("Realm '{}' already has identity provider '{}' — leaving as-is",
                        realmName, ssoAlias);
                ensureIdpRoleMappers(realm, ssoAlias, null);
                return;
            } catch (jakarta.ws.rs.NotFoundException notPresent) {
                // fall through and seed it
            }

            Map<String, Object> cfg = new HashMap<>();
            cfg.put("issuerUri", issuer);
            cfg.put("clientId", ssoClientId);
            if (ssoClientSecret != null && !ssoClientSecret.isBlank()) {
                cfg.put("clientSecret", ssoClientSecret);
            }
            upsertOidcIdentityProvider(realmName, ssoAlias, ssoDisplayName, cfg, true);
            log.info("Seeded default SSO '{}' into realm '{}'", ssoAlias, realmName);
        } catch (Exception e) {
            log.warn("Could not seed default SSO into realm '{}': {} — tenant remains usable via local login",
                    realmName, e.getMessage());
        }
    }

    /** Explicit issuer wins; otherwise derive the Microsoft Entra issuer from the tenant id. */
    private String resolveSsoIssuer() {
        if (ssoIssuerUri != null && !ssoIssuerUri.isBlank()) return ssoIssuerUri.trim();
        if (ssoAzureTenantId != null && !ssoAzureTenantId.isBlank()) {
            return "https://login.microsoftonline.com/" + ssoAzureTenantId.trim() + "/v2.0";
        }
        return "";
    }

    /**
     * Idempotently ensures the realm has a default {@code admin} user with
     * password {@code admin} and the {@code admin} realm role.
     *
     * <p>The user is created with a permanent (non-temporary) credential so the
     * tenant can sign in repeatedly using {@code admin / admin} until they
     * rotate the password from the SCIM user-management UI. Realm role
     * {@code admin} is assigned so the SPA's RBAC gates open immediately.</p>
     *
     * <p>If a user with username {@code admin} already exists, this method only
     * tops up the role assignment (idempotent) and does NOT reset the password —
     * we never overwrite an admin's chosen credential.</p>
     */
    public synchronized void ensureDefaultAdminUser(String realmName, String displayName) {
        ensureRealmAdminUser(realmName, displayName, null, null, "admin");
    }

    /**
     * Idempotently ensures {@code realmName} has an administrator user holding
     * {@code roleName}.
     *
     * <p>When {@code configuredEmail}/{@code configuredPassword} are supplied
     * (the operator's credentials from the setup wizard's first-tenant step),
     * the user is created with that email as its username and that password —
     * so the operator's own identity is the real admin login. When both are
     * blank, this falls back to the bootstrap-grade {@code admin} username with
     * password {@code admin} and a synthetic {@code admin@<realm>.local} email,
     * preserving the previous behaviour.</p>
     *
     * <p>Idempotency across boots and across the several runners that call this:
     * <ul>
     *   <li>a matching user (by target username) is never recreated;</li>
     *   <li>a pre-existing bootstrap {@code admin}/{@code admin@<realm>.local}
     *       user is <b>adopted</b> when configured credentials are present —
     *       renamed to the operator's email and its password reset once — so we
     *       upgrade the bootstrap admin rather than leaving a stale duplicate;</li>
     *   <li>if some user already holds {@code roleName}, no second bootstrap user
     *       is created (this is what keeps a later no-credential backfill pass
     *       from duplicating a custom-email admin).</li>
     * </ul>
     * The password is only ever set on create/adopt, never re-applied on a plain
     * revisit — so an admin who later rotates their own password is not clobbered.</p>
     */
    public synchronized void ensureRealmAdminUser(String realmName, String displayName,
                                                  String configuredEmail, String configuredPassword,
                                                  String roleName) {
        RealmResource realm = client().realm(realmName);

        // Force registrationEmailAsUsername off and editUsernameAllowed on
        // before any user creation/lookup. The former makes Keycloak rewrite
        // the admin's username to the email field; the latter is needed for the
        // adopt/rename path below. Both are idempotent and re-applied each boot.
        try {
            RealmRepresentation rep = realm.toRepresentation();
            boolean changed = false;
            if (Boolean.TRUE.equals(rep.isRegistrationEmailAsUsername())) {
                rep.setRegistrationEmailAsUsername(false);
                changed = true;
            }
            if (!Boolean.TRUE.equals(rep.isEditUsernameAllowed())) {
                rep.setEditUsernameAllowed(true);
                changed = true;
            }
            if (changed) {
                realm.update(rep);
                log.info("Patched realm '{}': registrationEmailAsUsername=false, editUsernameAllowed=true", realmName);
            }
        } catch (Exception e) {
            log.warn("Could not patch realm flags on '{}': {}", realmName, e.getMessage());
        }

        // A realm imported with a declarative user-profile email-domain lock
        // (the demo 'eip' realm restricts emails to @appverse.dev) rejects the
        // admin's email on create with HTTP 400 — whether that's the operator's
        // own domain or the admin@<realm>.local fallback. New tenant realms drop
        // this via buildRealmFromTemplate's setComponents(null); the pre-existing
        // realm needs it relaxed in place. Idempotent no-op when no lock exists.
        relaxAdminEmailProfileLock(realm, realmName);

        // Keycloak stores usernames lower-cased; normalise so exact lookups match.
        boolean custom = notBlank(configuredEmail);
        String targetUsername = custom ? configuredEmail.trim().toLowerCase() : "admin";
        String targetEmail    = custom ? configuredEmail.trim().toLowerCase() : "admin@" + realmName + ".local";
        String targetPassword = notBlank(configuredPassword) ? configuredPassword : "admin";

        // 1. Lookup existing user by target username (exact).
        List<UserRepresentation> matches = realm.users().searchByUsername(targetUsername, true);

        // 2. Adopt a pre-existing bootstrap admin if the target isn't present yet.
        //    Earlier builds (or a blank-credential boot) may have created
        //    'admin' / 'admin@<realm>.local'. With configured credentials we
        //    rename that user to the operator's email and reset its password
        //    once; without, we just normalise the legacy email-username back to
        //    'admin' (previous behaviour).
        if (matches == null || matches.isEmpty()) {
            List<UserRepresentation> legacy = realm.users().searchByUsername("admin", true);
            if (legacy == null || legacy.isEmpty()) {
                legacy = realm.users().searchByUsername("admin@" + realmName + ".local", true);
            }
            if (legacy != null && !legacy.isEmpty()) {
                UserRepresentation u = legacy.get(0);
                if (custom) {
                    u.setUsername(targetUsername);
                    u.setEmail(targetEmail);
                    u.setEmailVerified(true);
                    u.setEnabled(true);
                    realm.users().get(u.getId()).update(u);
                    resetPassword(realm, u.getId(), targetPassword);
                    log.info("Adopted bootstrap admin in realm '{}' as configured admin (id={}) and reset its password",
                            realmName, u.getId());
                } else if (!"admin".equals(u.getUsername())) {
                    u.setUsername("admin");
                    realm.users().get(u.getId()).update(u);
                    log.info("Renamed legacy admin user in realm '{}' back to 'admin' (id={})", realmName, u.getId());
                }
                matches = List.of(u);
            }
        }

        String userId;
        if (matches != null && !matches.isEmpty()) {
            userId = matches.get(0).getId();
            log.info("Admin user '{}' already exists in realm '{}' (id={}) — not recreating",
                    targetUsername, realmName, userId);
        } else {
            // Guard (fallback path only): if some user already holds the target
            // role, the realm is already reachable by an admin — don't create a
            // second bootstrap user. This is what keeps a no-credential backfill
            // pass from duplicating a custom-email admin created by the
            // first-tenant bootstrap. When the operator DID supply credentials
            // (custom), we always create their account, even alongside a
            // pre-existing bootstrap admin such as the platform realm's pa/pa.
            String existingAdminId = custom ? null : findUserWithRealmRole(realm, roleName);
            if (existingAdminId != null) {
                log.info("Realm '{}' already has a user with role '{}' (id={}) — skipping admin create",
                        realmName, roleName, existingAdminId);
                userId = existingAdminId;
            } else {
                UserRepresentation user = new UserRepresentation();
                user.setUsername(targetUsername);
                // Marked verified so the user can sign in without an
                // email-confirmation round trip.
                user.setEmail(targetEmail);
                user.setEmailVerified(true);
                user.setEnabled(true);
                user.setFirstName(displayName != null ? displayName : "Tenant");
                user.setLastName("Admin");

                CredentialRepresentation cred = new CredentialRepresentation();
                cred.setType(CredentialRepresentation.PASSWORD);
                cred.setValue(targetPassword);
                cred.setTemporary(false);
                user.setCredentials(List.of(cred));

                try (jakarta.ws.rs.core.Response resp = realm.users().create(user)) {
                    if (resp.getStatus() >= 300) {
                        throw new RuntimeException("Failed to create admin user in realm '"
                                + realmName + "': HTTP " + resp.getStatus());
                    }
                    userId = extractIdFromLocation(resp);
                }
                log.info("Seeded admin user '{}' in realm '{}' (id={})", targetUsername, realmName, userId);
            }
        }

        // 3. Assign the realm role if missing. listAll covers default-roles
        //    too — we only add what is needed, never strip existing assignments.
        UserResource userRes = realm.users().get(userId);
        boolean hasRole = userRes.roles().realmLevel().listAll().stream()
                .anyMatch(r -> roleName.equals(r.getName()));
        if (!hasRole) {
            try {
                RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
                userRes.roles().realmLevel().add(List.of(role));
                log.info("Granted '{}' role to admin user in realm '{}'", roleName, realmName);
            } catch (jakarta.ws.rs.NotFoundException nfe) {
                log.warn("Realm '{}' has no '{}' role — cannot grant it to the admin user",
                        realmName, roleName);
            }
        }
    }

    /**
     * Removes any {@code pattern} validation from the realm's declarative
     * user-profile {@code email} attribute, so an administrator whose email
     * doesn't match a baked-in domain lock can be created. Targeted: only the
     * pattern (domain lock) is dropped — the standard email/length validations
     * stay. Idempotent and best-effort: absent user-profile config, or a Keycloak
     * that doesn't expose the endpoint, is logged and ignored.
     */
    private void relaxAdminEmailProfileLock(RealmResource realm, String realmName) {
        try {
            org.keycloak.admin.client.resource.UserProfileResource up = realm.users().userProfile();
            org.keycloak.representations.userprofile.config.UPConfig cfg = up.getConfiguration();
            if (cfg == null || cfg.getAttributes() == null) return;
            boolean changed = false;
            for (org.keycloak.representations.userprofile.config.UPAttribute attr : cfg.getAttributes()) {
                if ("email".equals(attr.getName()) && attr.getValidations() != null
                        && attr.getValidations().remove("pattern") != null) {
                    changed = true;
                }
            }
            if (changed) {
                up.update(cfg);
                log.info("Relaxed email-domain lock in realm '{}' user profile so the admin email can be created",
                        realmName);
            }
        } catch (Exception e) {
            log.warn("Could not inspect/relax user-profile email lock on realm '{}': {} — "
                    + "admin creation may fail if a domain lock is present", realmName, e.getMessage());
        }
    }

    /** Sets a permanent (non-temporary) password on a user. */
    private static void resetPassword(RealmResource realm, String userId, String password) {
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(false);
        realm.users().get(userId).resetPassword(cred);
    }

    /** First user id holding {@code roleName} at realm level, or null if none / on error. */
    private String findUserWithRealmRole(RealmResource realm, String roleName) {
        try {
            List<UserRepresentation> members = realm.roles().get(roleName).getUserMembers(0, 1);
            return (members == null || members.isEmpty()) ? null : members.get(0).getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ensures the realm carries the standard OIDC {@code profile} and {@code email}
     * client scopes (with their mappers) and that every client gets them by default.
     *
     * <p>Realms created via {@link #buildRealmRepresentation} supplied an explicit
     * {@code clientScopes} list (roles + tenant only), which suppressed Keycloak's
     * built-in defaults — so issued tokens lacked {@code name} /
     * {@code preferred_username} / {@code email}, surfacing as a "User" placeholder
     * in the SPA header. This backfills them. Idempotent.</p>
     */
    public synchronized void ensureStandardClientScopes(String realmName) {
        RealmResource realm = client().realm(realmName);
        ensureScope(realm, realmName, "profile", profileClientScope());
        ensureScope(realm, realmName, "email", emailClientScope());
    }

    /** Creates the scope if missing, then attaches it as a default everywhere. */
    private void ensureScope(RealmResource realm, String realmName, String name,
                             ClientScopeRepresentation desired) {
        ClientScopeRepresentation existing = realm.clientScopes().findAll().stream()
                .filter(s -> name.equals(s.getName())).findFirst().orElse(null);
        String scopeId;
        if (existing == null) {
            try (jakarta.ws.rs.core.Response resp = realm.clientScopes().create(desired)) {
                if (resp.getStatus() >= 300) {
                    log.warn("Could not create '{}' client scope in realm '{}': HTTP {}",
                            name, realmName, resp.getStatus());
                    return;
                }
                scopeId = extractIdFromLocation(resp);
            }
            log.info("Created '{}' client scope in realm '{}'", name, realmName);
        } else {
            scopeId = existing.getId();
        }

        // Default-default so newly-created clients inherit it.
        try {
            boolean already = realm.getDefaultDefaultClientScopes().stream()
                    .anyMatch(s -> scopeId.equals(s.getId()));
            if (!already) realm.addDefaultDefaultClientScope(scopeId);
        } catch (Exception e) {
            log.warn("Could not register '{}' as default-default in '{}': {}", name, realmName, e.getMessage());
        }

        // Existing clients need explicit addition (default-default applies at create time only).
        for (ClientRepresentation c : realm.clients().findAll()) {
            org.keycloak.admin.client.resource.ClientResource clientRes = realm.clients().get(c.getId());
            boolean has = clientRes.getDefaultClientScopes().stream().anyMatch(s -> scopeId.equals(s.getId()));
            if (!has) {
                try {
                    clientRes.addDefaultClientScope(scopeId);
                } catch (Exception e) {
                    log.debug("Could not add '{}' scope to client '{}': {}", name, c.getClientId(), e.getMessage());
                }
            }
        }
    }

    private static ClientScopeRepresentation profileClientScope() {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName("profile");
        scope.setProtocol("openid-connect");
        Map<String, String> attrs = new HashMap<>();
        attrs.put("include.in.token.scope", "true");
        attrs.put("display.on.consent.screen", "true");
        scope.setAttributes(attrs);
        scope.setProtocolMappers(List.of(
                propertyMapper("username", "username", "preferred_username", "String"),
                attributeMapper("given name", "firstName", "given_name", "String"),
                attributeMapper("family name", "lastName", "family_name", "String"),
                fullNameMapper()));
        return scope;
    }

    private static ClientScopeRepresentation emailClientScope() {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName("email");
        scope.setProtocol("openid-connect");
        Map<String, String> attrs = new HashMap<>();
        attrs.put("include.in.token.scope", "true");
        attrs.put("display.on.consent.screen", "true");
        scope.setAttributes(attrs);
        scope.setProtocolMappers(List.of(
                propertyMapper("email", "email", "email", "String"),
                propertyMapper("email verified", "emailVerified", "email_verified", "boolean")));
        return scope;
    }

    private static ProtocolMapperRepresentation propertyMapper(String name, String userProp,
                                                               String claim, String jsonType) {
        ProtocolMapperRepresentation m = new ProtocolMapperRepresentation();
        m.setName(name);
        m.setProtocol("openid-connect");
        m.setProtocolMapper("oidc-usermodel-property-mapper");
        Map<String, String> cfg = new HashMap<>();
        cfg.put("user.attribute", userProp);
        cfg.put("claim.name", claim);
        cfg.put("jsonType.label", jsonType);
        cfg.put("id.token.claim", "true");
        cfg.put("access.token.claim", "true");
        cfg.put("userinfo.token.claim", "true");
        m.setConfig(cfg);
        return m;
    }

    private static ProtocolMapperRepresentation attributeMapper(String name, String userAttr,
                                                                String claim, String jsonType) {
        ProtocolMapperRepresentation m = new ProtocolMapperRepresentation();
        m.setName(name);
        m.setProtocol("openid-connect");
        m.setProtocolMapper("oidc-usermodel-attribute-mapper");
        Map<String, String> cfg = new HashMap<>();
        cfg.put("user.attribute", userAttr);
        cfg.put("claim.name", claim);
        cfg.put("jsonType.label", jsonType);
        cfg.put("id.token.claim", "true");
        cfg.put("access.token.claim", "true");
        cfg.put("userinfo.token.claim", "true");
        m.setConfig(cfg);
        return m;
    }

    private static ProtocolMapperRepresentation fullNameMapper() {
        ProtocolMapperRepresentation m = new ProtocolMapperRepresentation();
        m.setName("full name");
        m.setProtocol("openid-connect");
        m.setProtocolMapper("oidc-full-name-mapper");
        Map<String, String> cfg = new HashMap<>();
        cfg.put("id.token.claim", "true");
        cfg.put("access.token.claim", "true");
        cfg.put("userinfo.token.claim", "true");
        m.setConfig(cfg);
        return m;
    }

    /**
     * Idempotently ensures the {@code tenant} client scope exists in {@code realmName}
     * and its {@code tenant_id} hardcoded-claim mapper carries the supplied
     * {@code tenantId}, and that every client in the realm has this scope as a
     * default. Used (a) on every {@link #ensureRealm} call so the scope tracks
     * the canonical UUID, and (b) at startup by {@code DemoTenantBootstrap} to
     * retrofit the pre-existing {@code eip} realm without recreating it.
     */
    public synchronized void ensureRealmHasTenantScope(String realmName, String tenantId) {
        org.keycloak.admin.client.resource.RealmResource realm = client().realm(realmName);
        org.keycloak.admin.client.resource.ClientScopesResource scopes = realm.clientScopes();

        // 1) Locate or create the "tenant" client scope.
        ClientScopeRepresentation existing = scopes.findAll().stream()
                .filter(s -> "tenant".equals(s.getName()))
                .findFirst().orElse(null);

        String scopeId;
        if (existing == null) {
            try (jakarta.ws.rs.core.Response resp = scopes.create(tenantClientScope(tenantId))) {
                if (resp.getStatus() >= 300) {
                    throw new RuntimeException("Failed to create 'tenant' client scope: HTTP " + resp.getStatus());
                }
                scopeId = extractIdFromLocation(resp);
            }
            log.info("Created 'tenant' client scope in realm '{}' (id={})", realmName, scopeId);
        } else {
            scopeId = existing.getId();
            // 2) Update the mapper config so the claim value tracks the canonical UUID.
            //    The mapper may have been imported with the REPLACE_WITH_TENANT_ID
            //    placeholder, or with a stale UUID from a prior tenant rebadge.
            patchTenantMapper(scopes.get(scopeId), tenantId);
        }

        // 3) Add as realm default-default scope so newly-created clients inherit it.
        try {
            boolean alreadyDefault = realm.getDefaultDefaultClientScopes().stream()
                    .anyMatch(s -> scopeId.equals(s.getId()));
            if (!alreadyDefault) {
                realm.addDefaultDefaultClientScope(scopeId);
                log.info("Attached 'tenant' scope as default-default in realm '{}'", realmName);
            }
        } catch (Exception e) {
            log.warn("Could not register 'tenant' as default-default scope in '{}': {}",
                    realmName, e.getMessage());
        }

        // 4) Attach the scope to every existing client in the realm. New clients
        //    inherit via the default-default config; existing ones need explicit
        //    addition (Keycloak quirk — default-default applies at client-create time only).
        for (ClientRepresentation client : realm.clients().findAll()) {
            org.keycloak.admin.client.resource.ClientResource clientRes = realm.clients().get(client.getId());
            boolean already = clientRes.getDefaultClientScopes().stream()
                    .anyMatch(s -> scopeId.equals(s.getId()));
            if (!already) {
                try {
                    clientRes.addDefaultClientScope(scopeId);
                    log.debug("Added 'tenant' scope to client '{}' in realm '{}'",
                            client.getClientId(), realmName);
                } catch (Exception e) {
                    log.warn("Could not add 'tenant' scope to client '{}': {}",
                            client.getClientId(), e.getMessage());
                }
            }
        }
    }

    private static void patchTenantMapper(org.keycloak.admin.client.resource.ClientScopeResource scopeRes, String tenantId) {
        ClientScopeRepresentation rep = scopeRes.toRepresentation();
        List<ProtocolMapperRepresentation> mappers = rep.getProtocolMappers();
        ProtocolMapperRepresentation tenantMapper = null;
        if (mappers != null) {
            for (ProtocolMapperRepresentation m : mappers) {
                if ("tenant_id".equals(m.getName())) { tenantMapper = m; break; }
            }
        }
        if (tenantMapper == null) {
            // Mapper missing entirely (e.g. someone created a bare scope) — add it.
            scopeRes.getProtocolMappers().createMapper(buildTenantHardcodedMapper(tenantId)).close();
            return;
        }
        // Force claim.value to match the canonical tenantId, and ensure the
        // mapper is the hardcoded-claim type. If someone left the user-attribute
        // variant in place, replace it.
        Map<String, String> cfg = tenantMapper.getConfig() != null
                ? new HashMap<>(tenantMapper.getConfig())
                : new HashMap<>();
        boolean needsUpdate = !tenantId.equals(cfg.get("claim.value"))
                || !"oidc-hardcoded-claim-mapper".equals(tenantMapper.getProtocolMapper());
        if (!needsUpdate) return;

        tenantMapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        cfg.put("claim.name", "tenant_id");
        cfg.put("claim.value", tenantId);
        cfg.put("jsonType.label", "String");
        cfg.put("id.token.claim", "true");
        cfg.put("access.token.claim", "true");
        cfg.put("userinfo.token.claim", "true");
        tenantMapper.setConfig(cfg);
        scopeRes.getProtocolMappers().update(tenantMapper.getId(), tenantMapper);
    }

    private static String extractIdFromLocation(jakarta.ws.rs.core.Response resp) {
        String location = resp.getHeaderString("Location");
        if (location == null) return null;
        int slash = location.lastIndexOf('/');
        return slash >= 0 ? location.substring(slash + 1) : location;
    }

    /**
     * Lists users in {@code realmName} along with their realm-level role
     * assignments. Empty list if the realm cannot be reached so callers
     * render an empty table rather than an error.
     */
    public List<RealmUser> listRealmUsers(String realmName, int max) {
        try {
            RealmResource realm = client().realm(realmName);
            List<UserRepresentation> users = realm.users().list(0, Math.max(1, Math.min(max, 500)));
            List<RealmUser> out = new ArrayList<>(users.size());
            for (UserRepresentation u : users) {
                List<String> roles = realm.users().get(u.getId()).roles().realmLevel().listAll()
                        .stream()
                        .map(RoleRepresentation::getName)
                        // Filter out Keycloak's built-in default roles so the
                        // UI only shows the application-meaningful ones.
                        .filter(r -> !r.startsWith("default-roles-") && !"offline_access".equals(r) && !"uma_authorization".equals(r))
                        .toList();
                out.add(new RealmUser(
                        u.getId(),
                        u.getUsername(),
                        u.getEmail(),
                        buildDisplayName(u),
                        Boolean.TRUE.equals(u.isEnabled()),
                        roles,
                        localeOf(u)));
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not list users in realm '{}': {}", realmName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Lists the application-meaningful realm role names in {@code realmName}
     * (excluding Keycloak's built-in {@code default-roles-*}, {@code offline_access},
     * and {@code uma_authorization}), sorted. Empty list if the realm is unreachable.
     */
    public List<String> listRealmRoleNames(String realmName) {
        try {
            return client().realm(realmName).roles().list().stream()
                    .map(RoleRepresentation::getName)
                    .filter(r -> !r.startsWith("default-roles-")
                            && !"offline_access".equals(r) && !"uma_authorization".equals(r))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("Could not list realm roles in '{}': {}", realmName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Replaces the realm-level roles of a user with exactly the supplied set.
     * Any existing realm roles not in {@code newRoles} are removed; missing
     * ones are added. Silently ignores roles that don't exist in the realm.
     */
    public void setUserRealmRoles(String realmName, String userId, List<String> newRoles) {
        RealmResource realm = client().realm(realmName);
        UserResource userRes = realm.users().get(userId);

        List<RoleRepresentation> current = userRes.roles().realmLevel().listAll();
        List<RoleRepresentation> available = realm.roles().list();

        // Resolve the target set against the realm's actual roles.
        List<RoleRepresentation> target = new ArrayList<>();
        for (String name : newRoles) {
            if (name == null || name.isBlank()) continue;
            available.stream()
                    .filter(r -> name.equals(r.getName()))
                    .findFirst()
                    .ifPresent(target::add);
        }

        // Remove anything currently assigned that isn't in the target set —
        // but leave Keycloak's built-in default-roles untouched.
        List<RoleRepresentation> toRemove = new ArrayList<>();
        for (RoleRepresentation r : current) {
            if (r.getName().startsWith("default-roles-")) continue;
            if (target.stream().noneMatch(t -> t.getName().equals(r.getName()))) {
                toRemove.add(r);
            }
        }
        if (!toRemove.isEmpty()) userRes.roles().realmLevel().remove(toRemove);

        // Add anything in the target set that isn't already assigned.
        List<RoleRepresentation> toAdd = new ArrayList<>();
        for (RoleRepresentation t : target) {
            if (current.stream().noneMatch(c -> c.getName().equals(t.getName()))) {
                toAdd.add(t);
            }
        }
        if (!toAdd.isEmpty()) userRes.roles().realmLevel().add(toAdd);

        log.info("Realm '{}' user {} roles updated: -{} +{}",
                realmName, userId,
                toRemove.stream().map(RoleRepresentation::getName).toList(),
                toAdd.stream().map(RoleRepresentation::getName).toList());
    }

    /** True if a realm role with this exact name already exists. */
    public boolean realmRoleExists(String realmName, String roleName) {
        try {
            return client().realm(realmName).roles().list().stream()
                    .anyMatch(r -> roleName.equals(r.getName()));
        } catch (Exception e) {
            log.warn("Could not check realm role '{}' in '{}': {}", roleName, realmName, e.getMessage());
            return false;
        }
    }

    /**
     * Creates any built-in role ({@link EipRoles#TENANT_BUILT_IN}) missing from an existing
     * realm — realms provisioned before a role joined the catalog (e.g. {@code coo},
     * {@code chro}) otherwise could never be assigned it. Idempotent; returns the roles created.
     */
    public List<String> ensureBuiltInRealmRoles(String realmName) {
        return withAdminRetry(() -> {
            RealmResource realm = client().realm(realmName);
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (RoleRepresentation r : realm.roles().list()) existing.add(r.getName());
            List<String> created = new ArrayList<>();
            for (String role : REALM_ROLES) {
                if (existing.contains(role)) continue;
                RoleRepresentation rep = new RoleRepresentation();
                rep.setName(role);
                rep.setDescription("Built-in tenant role: " + role);
                realm.roles().create(rep);
                created.add(role);
            }
            if (!created.isEmpty()) log.info("Realm '{}' built-in roles added: {}", realmName, created);
            return created;
        });
    }

    /**
     * Creates a new realm role. No-op if a role with the same name already
     * exists, so callers can treat it as idempotent.
     */
    public void createRealmRole(String realmName, String roleName, String description) {
        withAdminRetry(() -> {
            RealmResource realm = client().realm(realmName);
            boolean exists = realm.roles().list().stream().anyMatch(r -> roleName.equals(r.getName()));
            if (exists) {
                log.info("Realm '{}' role '{}' already exists — skipping create", realmName, roleName);
                return null;
            }
            RoleRepresentation rep = new RoleRepresentation();
            rep.setName(roleName);
            rep.setDescription(description == null || description.isBlank()
                    ? "Custom tenant role: " + roleName : description);
            realm.roles().create(rep);
            log.info("Realm '{}' role '{}' created", realmName, roleName);
            return null;
        });
        syncIdpRoleMappers(realmName);
    }

    /** Deletes a realm role by name, and any IdP mapper that granted it. No-op if it doesn't exist. */
    public void deleteRealmRole(String realmName, String roleName) {
        withAdminRetry(() -> {
            RealmResource realm = client().realm(realmName);
            boolean exists = realm.roles().list().stream().anyMatch(r -> roleName.equals(r.getName()));
            if (!exists) {
                log.info("Realm '{}' role '{}' not found — skipping delete", realmName, roleName);
                return null;
            }
            realm.roles().get(roleName).remove();
            log.info("Realm '{}' role '{}' deleted", realmName, roleName);
            return null;
        });
        syncIdpRoleMappers(realmName);
    }

    /**
     * How many users currently hold {@code roleName} in the realm. Used to block
     * deletion of a role that is still assigned. Returns 0 on error (fail-open on
     * count, but the delete path still verifies existence).
     */
    public int countUsersWithRealmRole(String realmName, String roleName) {
        try {
            return client().realm(realmName).roles().get(roleName)
                    .getUserMembers(0, Integer.MAX_VALUE).size();
        } catch (Exception e) {
            log.warn("Could not count members of role '{}' in '{}': {}", roleName, realmName, e.getMessage());
            return 0;
        }
    }

    /**
     * Creates a new user in {@code realmName} with the given identity, an initial
     * password, and (optionally) a single realm role. The user is created enabled
     * and email-verified so they can sign in immediately. When
     * {@code temporaryPassword} is true, Keycloak forces a password reset on first
     * login. Returns the created user's view.
     *
     * @throws IllegalStateException if Keycloak rejects the create — notably a 409
     *         when the username or email already exists in the realm.
     */
    public RealmUser createRealmUser(String realmName, String username, String email,
                                     String displayName, String role, String password,
                                     boolean temporaryPassword) {
        RealmResource realm = client().realm(realmName);

        UserRepresentation user = new UserRepresentation();
        user.setUsername(notBlank(username) ? username.trim() : email);
        if (notBlank(email)) {
            user.setEmail(email.trim());
            user.setEmailVerified(true);
        }
        applyDisplayName(user, displayName);
        user.setEnabled(true);

        if (notBlank(password)) {
            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(password);
            cred.setTemporary(temporaryPassword);
            user.setCredentials(List.of(cred));
        }

        String userId;
        try (jakarta.ws.rs.core.Response resp = realm.users().create(user)) {
            if (resp.getStatus() == 409) {
                throw new IllegalStateException("A user with that username or email already exists");
            }
            if (resp.getStatus() >= 300) {
                throw new IllegalStateException("Keycloak rejected the user create: HTTP " + resp.getStatus());
            }
            userId = extractIdFromLocation(resp);
        }
        log.info("Created user '{}' in realm '{}' (id={})", user.getUsername(), realmName, userId);

        if (notBlank(role)) {
            try {
                setUserRealmRoles(realmName, userId, List.of(role));
            } catch (Exception e) {
                // The account exists; a failed role grant shouldn't fail the whole
                // create — the admin can fix the role from the table.
                log.warn("User {} created but role '{}' assignment failed: {}", userId, role, e.getMessage());
            }
        }
        return readRealmUser(realmName, userId);
    }

    /**
     * Updates a user's display name, email, enabled state, and/or preferred locale
     * in {@code realmName}. {@code null} arguments leave the corresponding field
     * unchanged. Returns the refreshed view.
     */
    public RealmUser updateRealmUser(String realmName, String userId, String displayName,
                                     String email, Boolean enabled, String locale) {
        RealmResource realm = client().realm(realmName);
        UserResource userRes = realm.users().get(userId);
        UserRepresentation user = userRes.toRepresentation();

        if (displayName != null) applyDisplayName(user, displayName);
        if (email != null) {
            user.setEmail(email.isBlank() ? null : email.trim());
            if (!email.isBlank()) user.setEmailVerified(true);
        }
        if (enabled != null) user.setEnabled(enabled);
        if (locale != null) {
            Map<String, List<String>> attrs = user.getAttributes() != null
                    ? new HashMap<>(user.getAttributes()) : new HashMap<>();
            if (locale.isBlank()) attrs.remove("locale");
            else                  attrs.put("locale", List.of(locale.trim()));
            user.setAttributes(attrs);
        }
        userRes.update(user);
        log.info("Updated user {} in realm '{}' (displayName={}, email={}, enabled={}, locale={})",
                userId, realmName, displayName != null, email != null, enabled, locale != null);
        return readRealmUser(realmName, userId);
    }

    /** Permanently removes a user from {@code realmName}. No-op if already gone. */
    public void deleteRealmUser(String realmName, String userId) {
        try (jakarta.ws.rs.core.Response resp = client().realm(realmName).users().delete(userId)) {
            if (resp.getStatus() >= 300 && resp.getStatus() != 404) {
                throw new IllegalStateException("Keycloak rejected the user delete: HTTP " + resp.getStatus());
            }
        }
        log.info("Deleted user {} from realm '{}'", userId, realmName);
    }

    /**
     * Reads a single realm user by id into the JSON-friendly view, or {@code null}
     * if the user cannot be found / the realm is unreachable.
     */
    public RealmUser readRealmUser(String realmName, String userId) {
        try {
            RealmResource realm = client().realm(realmName);
            UserResource userRes = realm.users().get(userId);
            UserRepresentation u = userRes.toRepresentation();
            List<String> roles = userRes.roles().realmLevel().listAll().stream()
                    .map(RoleRepresentation::getName)
                    .filter(r -> !r.startsWith("default-roles-") && !"offline_access".equals(r) && !"uma_authorization".equals(r))
                    .toList();
            return new RealmUser(u.getId(), u.getUsername(), u.getEmail(), buildDisplayName(u),
                    Boolean.TRUE.equals(u.isEnabled()), roles, localeOf(u));
        } catch (Exception e) {
            log.warn("Could not read user {} in realm '{}': {}", userId, realmName, e.getMessage());
            return null;
        }
    }

    /**
     * Splits a display name into Keycloak's {@code firstName}/{@code lastName}
     * fields (first token → first name, remainder → last name) so it round-trips
     * through {@link #buildDisplayName}. A blank name clears both.
     */
    private static void applyDisplayName(UserRepresentation user, String displayName) {
        if (displayName == null) return;
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            user.setFirstName(null);
            user.setLastName(null);
            return;
        }
        int sp = trimmed.indexOf(' ');
        if (sp < 0) {
            user.setFirstName(trimmed);
            user.setLastName(null);
        } else {
            user.setFirstName(trimmed.substring(0, sp).trim());
            user.setLastName(trimmed.substring(sp + 1).trim());
        }
    }

    /** Reads the user's preferred locale from the standard {@code locale} attribute, or null. */
    private static String localeOf(UserRepresentation u) {
        if (u.getAttributes() == null) return null;
        List<String> v = u.getAttributes().get("locale");
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private static String buildDisplayName(UserRepresentation u) {
        String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
        String last  = u.getLastName()  == null ? "" : u.getLastName().trim();
        String full  = (first + " " + last).trim();
        if (!full.isEmpty()) return full;
        if (u.getEmail() != null && !u.getEmail().isBlank()) return u.getEmail();
        return u.getUsername();
    }

    /** Light, JSON-friendly view of a Keycloak user. */
    public record RealmUser(
            String id,
            String username,
            String email,
            String displayName,
            boolean enabled,
            List<String> realmRoles,
            String locale) {}

    // ── Read-only realm authentication / SSO settings ───────────────────────────

    /**
     * Reads the current authentication-related configuration of {@code realmName}
     * straight from Keycloak: the realm's login flags, its configured identity
     * providers (SSO), and any user-federation providers (e.g. LDAP). This is a
     * read-only projection used to surface Keycloak's live state in the admin UI;
     * it never mutates the realm. Client secrets are intentionally omitted.
     */
    public RealmAuthInfo getRealmAuthInfo(String realmName) {
        RealmResource realm = client().realm(realmName);
        RealmRepresentation rep = realm.toRepresentation();

        List<IdentityProviderInfo> idps = new ArrayList<>();
        try {
            for (IdentityProviderRepresentation idp : realm.identityProviders().findAll()) {
                Map<String, String> cfg = idp.getConfig() != null ? idp.getConfig() : Map.of();
                String issuer = cfg.get("issuer");
                if (issuer == null || issuer.isBlank()) issuer = cfg.get("issuerUri");
                idps.add(new IdentityProviderInfo(
                        idp.getAlias(),
                        idp.getDisplayName(),
                        idp.getProviderId(),
                        Boolean.TRUE.equals(idp.isEnabled()),
                        Boolean.TRUE.equals(idp.isTrustEmail()),
                        issuer,
                        cfg.get("clientId")));   // clientSecret deliberately not exposed
            }
        } catch (Exception e) {
            log.warn("Could not list identity providers for realm '{}': {}", realmName, e.getMessage());
        }

        List<UserFederationInfo> feds = new ArrayList<>();
        try {
            List<ComponentRepresentation> comps = realm.components()
                    .query(rep.getId(), "org.keycloak.storage.UserStorageProvider");
            for (ComponentRepresentation c : comps) {
                String enabled = c.getConfig() != null ? c.getConfig().getFirst("enabled") : null;
                feds.add(new UserFederationInfo(
                        c.getName(),
                        c.getProviderId(),
                        enabled == null || Boolean.parseBoolean(enabled)));
            }
        } catch (Exception e) {
            log.warn("Could not list user federation for realm '{}': {}", realmName, e.getMessage());
        }

        return new RealmAuthInfo(
                realmName,
                Boolean.TRUE.equals(rep.isRegistrationAllowed()),
                Boolean.TRUE.equals(rep.isResetPasswordAllowed()),
                Boolean.TRUE.equals(rep.isRememberMe()),
                Boolean.TRUE.equals(rep.isVerifyEmail()),
                Boolean.TRUE.equals(rep.isLoginWithEmailAllowed()),
                Boolean.TRUE.equals(rep.isRegistrationEmailAsUsername()),
                Boolean.TRUE.equals(rep.isEditUsernameAllowed()),
                Boolean.TRUE.equals(rep.isBruteForceProtected()),
                rep.getSslRequired(),
                rep.getPasswordPolicy(),
                rep.getAccessTokenLifespan(),
                idps,
                feds);
    }

    /** Read-only projection of a realm's authentication settings. */
    public record RealmAuthInfo(
            String realm,
            boolean registrationAllowed,
            boolean resetPasswordAllowed,
            boolean rememberMe,
            boolean verifyEmail,
            boolean loginWithEmailAllowed,
            boolean registrationEmailAsUsername,
            boolean editUsernameAllowed,
            boolean bruteForceProtected,
            String sslRequired,
            String passwordPolicy,
            Integer accessTokenLifespanSeconds,
            List<IdentityProviderInfo> identityProviders,
            List<UserFederationInfo> userFederation) {}

    /** Read-only view of a configured identity provider (SSO). Secrets omitted. */
    public record IdentityProviderInfo(
            String alias,
            String displayName,
            String providerId,
            boolean enabled,
            boolean trustEmail,
            String issuer,
            String clientId) {}

    /** Read-only view of a user-federation provider (e.g. LDAP). */
    public record UserFederationInfo(
            String name,
            String providerId,
            boolean enabled) {}

    /**
     * Counts users in the given realm. Used by the tenant-metrics endpoint.
     * Returns -1 if the realm cannot be reached so the caller can render
     * "unavailable" without conflating zero users with an outage.
     */
    public int countUsers(String realmName) {
        try {
            Integer n = client().realm(realmName).users().count();
            return n == null ? 0 : n;
        } catch (jakarta.ws.rs.NotFoundException nfe) {
            return -1;
        } catch (Exception e) {
            log.warn("Could not count users in realm '{}': {}", realmName, e.getMessage());
            return -1;
        }
    }

    /**
     * Lightweight realm reachability check used by the tenant-metrics endpoint.
     * Returns {@code true} if the realm exists and the admin API is responsive.
     */
    public boolean realmReachable(String realmName) {
        try {
            client().realm(realmName).toRepresentation();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Removes a realm. Used when a tenant is hard-deleted. Soft-delete
     * intentionally leaves the realm in place so admins can recover it later.
     */
    public synchronized void deleteRealm(String realmName) {
        try {
            client().realm(realmName).remove();
            log.info("Deleted Keycloak realm '{}'", realmName);
        } catch (jakarta.ws.rs.NotFoundException nfe) {
            log.warn("Realm '{}' did not exist at delete time — nothing to do", realmName);
        }
    }

    // ── Identity-provider federation ───────────────────────────────────────────
    //
    // A tenant realm is provisioned with local login only (see
    // buildRealmRepresentation). When an admin configures an OIDC provider in the
    // Auth-Methods UI, the config is persisted in admin-service's DB AND pushed
    // here so the "Sign in with …" button actually appears on the realm's login
    // page. Without this step the SSO config is stored but never federated, and
    // new tenants only ever see the username/password form.

    /**
     * Idempotently creates or updates an OIDC identity provider in {@code realmName}.
     *
     * <p>OIDC endpoints are resolved from the provider's {@code issuerUri} via its
     * {@code .well-known/openid-configuration} discovery document, so any compliant
     * provider (Microsoft Entra, Google, Okta, …) works without the operator having
     * to hand-enter authorization/token/JWKS URLs.</p>
     *
     * @param config the provider's runtime config with secrets already resolved —
     *               expects {@code issuerUri} and {@code clientId}, optionally
     *               {@code clientSecret}, {@code defaultScope} and {@code rolesClaim}
     *               (the token claim carrying the user's roles; defaults to
     *               {@code roles}, which is where Microsoft Entra puts app roles).
     */
    public synchronized void upsertOidcIdentityProvider(String realmName, String alias,
                                                        String displayName,
                                                        Map<String, Object> config, boolean enabled) {
        RealmResource realm = client().realm(realmName);
        IdentityProviderRepresentation idp = buildOidcIdp(alias, displayName, config, enabled);

        boolean exists;
        try {
            realm.identityProviders().get(alias).toRepresentation();
            exists = true;
        } catch (jakarta.ws.rs.NotFoundException nfe) {
            exists = false;
        }

        if (exists) {
            realm.identityProviders().get(alias).update(idp);
            log.info("Updated OIDC identity provider '{}' in realm '{}' (enabled={})",
                    alias, realmName, enabled);
        } else {
            try (jakarta.ws.rs.core.Response resp = realm.identityProviders().create(idp)) {
                if (resp.getStatus() >= 300) {
                    throw new RuntimeException("Failed to create identity provider '" + alias
                            + "' in realm '" + realmName + "': HTTP " + resp.getStatus());
                }
            }
            log.info("Created OIDC identity provider '{}' in realm '{}' (enabled={})",
                    alias, realmName, enabled);
        }
        ensureIdpAttributeMappers(realm, alias);
        ensureIdpRoleMappers(realm, alias, str(config.get("rolesClaim")));
    }

    /** Removes an identity provider from a realm. No-op if it is not present. */
    public synchronized void deleteIdentityProvider(String realmName, String alias) {
        try {
            client().realm(realmName).identityProviders().get(alias).remove();
            log.info("Removed identity provider '{}' from realm '{}'", alias, realmName);
        } catch (jakarta.ws.rs.NotFoundException nfe) {
            log.debug("Identity provider '{}' not present in realm '{}' — nothing to delete",
                    alias, realmName);
        } catch (Exception e) {
            log.warn("Could not remove identity provider '{}' from realm '{}': {}",
                    alias, realmName, e.getMessage());
        }
    }

    private IdentityProviderRepresentation buildOidcIdp(String alias, String displayName,
                                                        Map<String, Object> config, boolean enabled) {
        Map<String, Object> disco = discoverOidc(str(config.get("issuerUri")));

        IdentityProviderRepresentation idp = new IdentityProviderRepresentation();
        idp.setAlias(alias);
        idp.setDisplayName(displayName == null || displayName.isBlank()
                ? "Sign in with " + alias : displayName);
        idp.setProviderId("oidc");
        idp.setEnabled(enabled);
        idp.setTrustEmail(true);          // emails from the IdP are authoritative
        idp.setStoreToken(false);         // don't persist external tokens (security)
        idp.setLinkOnly(false);
        idp.setFirstBrokerLoginFlowAlias("first broker login");

        Map<String, String> c = new HashMap<>();
        c.put("clientId", str(config.get("clientId")));
        String secret = str(config.get("clientSecret"));
        if (!secret.isBlank() && !"***".equals(secret)) {
            c.put("clientSecret", secret);
        }
        c.put("clientAuthMethod", "client_secret_post");
        c.put("authorizationUrl", str(disco.get("authorization_endpoint")));
        c.put("tokenUrl", str(disco.get("token_endpoint")));
        String jwks = str(disco.get("jwks_uri"));
        if (!jwks.isBlank()) {
            c.put("jwksUrl", jwks);
            c.put("useJwksUrl", "true");
            c.put("validateSignature", "true");
        }
        String userInfo = str(disco.get("userinfo_endpoint"));
        if (!userInfo.isBlank()) c.put("userInfoUrl", userInfo);
        String issuer = str(disco.getOrDefault("issuer", config.get("issuerUri")));
        if (!issuer.isBlank()) c.put("issuer", issuer);
        String logout = str(disco.get("end_session_endpoint"));
        if (!logout.isBlank()) c.put("logoutUrl", logout);
        String scope = str(config.get("defaultScope"));
        c.put("defaultScope", scope.isBlank() ? "openid profile email" : scope);
        c.put("syncMode", "FORCE");
        c.put("backchannelSupported", "false");
        idp.setConfig(c);
        return idp;
    }

    /**
     * Adds email / first-name / last-name attribute mappers for the IdP so
     * federated users land with a populated profile. Idempotent — skips any
     * mapper that already exists.
     */
    private void ensureIdpAttributeMappers(RealmResource realm, String alias) {
        addIdpMapperIfMissing(realm, alias, alias + "-email", "email", "email");
        addIdpMapperIfMissing(realm, alias, alias + "-firstName", "given_name", "firstName");
        addIdpMapperIfMissing(realm, alias, alias + "-lastName", "family_name", "lastName");
    }

    private void addIdpMapperIfMissing(RealmResource realm, String alias, String name,
                                       String claim, String userAttr) {
        IdentityProviderResource res = realm.identityProviders().get(alias);
        boolean exists = res.getMappers().stream().anyMatch(m -> name.equals(m.getName()));
        if (exists) return;

        IdentityProviderMapperRepresentation m = new IdentityProviderMapperRepresentation();
        m.setName(name);
        m.setIdentityProviderAlias(alias);
        m.setIdentityProviderMapper("oidc-user-attribute-idp-mapper");
        Map<String, String> cfg = new HashMap<>();
        cfg.put("claim", claim);
        cfg.put("user.attribute", userAttr);
        cfg.put("syncMode", "INHERIT");
        m.setConfig(cfg);
        try (jakarta.ws.rs.core.Response resp = res.addMapper(m)) {
            if (resp.getStatus() >= 300) {
                log.warn("Could not add IdP mapper '{}' to '{}': HTTP {}", name, alias, resp.getStatus());
            }
        }
    }

    // ── IdP role mapping ───────────────────────────────────────────────────────
    //
    // One oidc-role-idp-mapper per application role: a federated user whose token
    // claim (Entra: "roles") contains the role name gets that realm role. syncMode
    // FORCE re-evaluates on every login, so removing the assignment in the IdP
    // revokes the role in Orphos — the IdP is the source of truth for mapped roles.
    //
    // Deliberately no hardcoded default role: the shared platform IdP is seeded into
    // every tenant realm, so a blanket "viewer for everyone" would let any account in
    // that directory read every tenant. Users without a mapped role land as guest and
    // wait for a tenant admin, like any other new account.

    private static final String DEFAULT_ROLES_CLAIM = "roles";
    private static final String ROLE_IDP_MAPPER = "oidc-role-idp-mapper";

    /**
     * Reconciles role mappers on one IdP against the realm's application roles:
     * adds a mapper for each role that has none, and drops mappers whose role no
     * longer exists (Keycloak fails the whole broker login on a dangling role).
     * Mappers an admin added by hand are left alone as long as their role exists.
     *
     * @param rolesClaim claim to match; blank keeps the claim already used by the
     *                   IdP's existing role mappers, else {@value #DEFAULT_ROLES_CLAIM}
     */
    private void ensureIdpRoleMappers(RealmResource realm, String alias, String rolesClaim) {
        IdentityProviderResource res = realm.identityProviders().get(alias);
        List<IdentityProviderMapperRepresentation> roleMappers = res.getMappers().stream()
                .filter(m -> ROLE_IDP_MAPPER.equals(m.getIdentityProviderMapper()))
                .toList();

        String claim = rolesClaim;
        if (claim == null || claim.isBlank()) {
            claim = roleMappers.stream()
                    .map(m -> m.getConfig() == null ? null : m.getConfig().get("claim"))
                    .filter(c -> c != null && !c.isBlank())
                    .findFirst()
                    .orElse(DEFAULT_ROLES_CLAIM);
        }

        java.util.Set<String> appRoles = new java.util.TreeSet<>();
        for (RoleRepresentation r : realm.roles().list()) {
            if (isFederatable(r.getName())) appRoles.add(r.getName());
        }

        java.util.Set<String> mapped = new java.util.HashSet<>();
        for (IdentityProviderMapperRepresentation m : roleMappers) {
            String role = m.getConfig() == null ? null : m.getConfig().get("role");
            if (role != null && appRoles.contains(role)) {
                mapped.add(role);
            } else {
                res.delete(m.getId());
                log.info("Removed IdP role mapper '{}' from '{}' — role '{}' no longer exists",
                        m.getName(), alias, role);
            }
        }

        List<String> added = new ArrayList<>();
        for (String role : appRoles) {
            if (mapped.contains(role)) continue;
            IdentityProviderMapperRepresentation m = new IdentityProviderMapperRepresentation();
            m.setName(alias + "-role-" + role);
            m.setIdentityProviderAlias(alias);
            m.setIdentityProviderMapper(ROLE_IDP_MAPPER);
            Map<String, String> cfg = new HashMap<>();
            cfg.put("claim", claim);
            cfg.put("claim.value", role);
            cfg.put("role", role);
            cfg.put("syncMode", "FORCE");
            m.setConfig(cfg);
            try (jakarta.ws.rs.core.Response resp = res.addMapper(m)) {
                if (resp.getStatus() >= 300) {
                    log.warn("Could not add IdP role mapper for '{}' to '{}': HTTP {}",
                            role, alias, resp.getStatus());
                } else {
                    added.add(role);
                }
            }
        }
        if (!added.isEmpty()) {
            log.info("IdP '{}' now maps claim '{}' to roles {}", alias, claim, added);
        }
    }

    /**
     * Re-runs {@link #ensureIdpRoleMappers} for every OIDC IdP in the realm, so a
     * role created after the IdP was configured becomes assignable from the IdP too.
     * Non-fatal: role management must not fail because SSO mapping hiccupped.
     */
    public void syncIdpRoleMappers(String realmName) {
        try {
            RealmResource realm = client().realm(realmName);
            for (IdentityProviderRepresentation idp : realm.identityProviders().findAll()) {
                if ("oidc".equals(idp.getProviderId())) {
                    ensureIdpRoleMappers(realm, idp.getAlias(), null);
                }
            }
        } catch (Exception e) {
            log.warn("Could not sync IdP role mappers in realm '{}': {}", realmName, e.getMessage());
        }
    }

    /** Roles an external IdP may grant: real application roles, never guest or platform-only roles. */
    private static boolean isFederatable(String role) {
        return EipRoles.confersMembership(role) && !EipRoles.RESERVED.contains(role);
    }

    /**
     * Fetches the OIDC discovery document for {@code issuerUri}. Throws with a
     * clear message on any failure so the admin sees why SSO didn't provision
     * rather than a silent half-configured state.
     */
    private Map<String, Object> discoverOidc(String issuerUri) {
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalArgumentException("OIDC config.issuerUri is required to provision SSO");
        }
        String base = issuerUri.trim();
        String url = base.endsWith("/.well-known/openid-configuration")
                ? base
                : base.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("OIDC discovery at " + url
                        + " returned HTTP " + resp.statusCode());
            }
            return MAPPER.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not fetch OIDC discovery document from "
                    + url + ": " + e.getMessage(), e);
        }
    }

    private synchronized HttpClient http() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return httpClient;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private RealmRepresentation buildRealmRepresentation(String realmName, String displayName, String tenantId) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(realmName);
        realm.setDisplayName(displayName != null ? displayName : realmName);
        realm.setEnabled(true);
        realm.setSslRequired("none");
        realm.setRegistrationAllowed(true);
        // registrationEmailAsUsername MUST be false: with it on, Keycloak
        // overwrites the username field with the email even for admin-created
        // users, breaking the bootstrap admin/admin login. Email login stays
        // available via loginWithEmailAllowed.
        realm.setRegistrationEmailAsUsername(false);
        realm.setLoginWithEmailAllowed(true);
        // Allow admins to rename users — needed by the realm-flag-flip rescue
        // path in ensureDefaultAdminUser. Keycloak default is false, which
        // would 400 on any username PUT.
        realm.setEditUsernameAllowed(true);
        realm.setResetPasswordAllowed(true);
        realm.setRememberMe(true);
        realm.setAccessTokenLifespan(300);

        RolesRepresentation roles = new RolesRepresentation();
        List<RoleRepresentation> realmRoles = new ArrayList<>();
        for (String r : REALM_ROLES) {
            RoleRepresentation role = new RoleRepresentation();
            role.setName(r);
            role.setDescription("Tenant role: " + r);
            realmRoles.add(role);
        }
        roles.setRealm(realmRoles);
        realm.setRoles(roles);
        realm.setDefaultRoles(List.of("guest"));

        realm.setClientScopes(List.of(rolesClientScope(), tenantClientScope(tenantId)));
        realm.setDefaultDefaultClientScopes(List.of("roles", "tenant"));

        realm.setClients(List.of(spaClient()));
        return realm;
    }

    /**
     * Recreates the {@code roles} client scope from the eip-realm.json template:
     * exposes {@code realm_access.roles} on every token. The Spring Security
     * JwtGrantedAuthoritiesConverter reads from this claim.
     */
    private static ClientScopeRepresentation rolesClientScope() {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName("roles");
        scope.setProtocol("openid-connect");
        scope.setAttributes(Map.of("include.in.token.scope", "true"));

        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("realm roles");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-usermodel-realm-role-mapper");
        Map<String, String> cfg = new HashMap<>();
        cfg.put("multivalued", "true");
        cfg.put("claim.name", "realm_access.roles");
        cfg.put("jsonType.label", "String");
        cfg.put("id.token.claim", "true");
        cfg.put("access.token.claim", "true");
        cfg.put("userinfo.token.claim", "true");
        mapper.setConfig(cfg);
        scope.setProtocolMappers(List.of(mapper));
        return scope;
    }

    /**
     * The {@code tenant} client scope — adds a hardcoded {@code tenant_id}
     * claim (the realm's canonical tenant UUID) to every issued token.
     *
     * <p>Hardcoded-claim is the right shape for realm-per-tenant: all users in
     * a realm belong to exactly one tenant, so the claim value is a realm-level
     * constant rather than a per-user attribute. This also means new users
     * (federated from Azure, self-registered, etc.) get the right claim
     * immediately, with no per-user attribute provisioning step.</p>
     */
    private static ClientScopeRepresentation tenantClientScope(String tenantId) {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName("tenant");
        scope.setDescription("Adds tenant_id (UUID) to every issued token. Required for multi-tenant request routing.");
        scope.setProtocol("openid-connect");
        Map<String, String> attrs = new HashMap<>();
        attrs.put("include.in.token.scope", "true");
        attrs.put("display.on.consent.screen", "false");
        scope.setAttributes(attrs);
        scope.setProtocolMappers(List.of(buildTenantHardcodedMapper(tenantId)));
        return scope;
    }

    private static ProtocolMapperRepresentation buildTenantHardcodedMapper(String tenantId) {
        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("tenant_id");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        Map<String, String> cfg = new HashMap<>();
        cfg.put("claim.name", "tenant_id");
        cfg.put("claim.value", tenantId);
        cfg.put("jsonType.label", "String");
        cfg.put("id.token.claim", "true");
        cfg.put("access.token.claim", "true");
        cfg.put("userinfo.token.claim", "true");
        cfg.put("access.tokenResponse.claim", "false");
        mapper.setConfig(cfg);
        return mapper;
    }

    private ClientRepresentation spaClient() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("orphos-spa");
        client.setName("Orphos Executive SPA");
        client.setEnabled(true);
        client.setPublicClient(true);
        client.setStandardFlowEnabled(true);
        client.setImplicitFlowEnabled(false);
        client.setDirectAccessGrantsEnabled(false);
        client.setServiceAccountsEnabled(false);
        client.setRedirectUris(spaRedirectUris);
        client.setWebOrigins(spaWebOrigins);
        client.setProtocol("openid-connect");
        client.setFullScopeAllowed(true);
        client.setDefaultClientScopes(List.of("openid", "profile", "email", "roles", "tenant"));
        client.setAttributes(Map.of("pkce.code.challenge.method", "S256"));
        return client;
    }

    private synchronized Keycloak client() {
        if (adminClient == null) {
            // Re-resolve on every (re)build so a secret seeded after startup is
            // picked up without a container restart.
            adminPassword = resolveAdminPassword();
            adminClient = KeycloakBuilder.builder()
                    .serverUrl(serverUrl)
                    .realm("master")
                    .username(adminUser)
                    .password(adminPassword)
                    .clientId(adminClientId)
                    .build();
        }
        return adminClient;
    }

    /**
     * Returns the current admin password, preferring a live read from Infisical
     * ({@code eip/keycloak-admin#password}) when the secrets backend is wired and
     * reachable. Falls back to the constructor-supplied value (the resolved
     * {@code @Value}) when Infisical is absent or the read yields nothing — so
     * behaviour is unchanged in tests and when the env var was populated at boot.
     */
    private String resolveAdminPassword() {
        if (secrets != null && secrets.isConfigured()) {
            String live = secrets.readField(ADMIN_SECRET_PATH, ADMIN_SECRET_FIELD).orElse(null);
            if (live != null && !live.isBlank()) {
                return live;
            }
        }
        return adminPassword;
    }

    /** Drop the cached admin client so the next {@link #client()} call rebuilds it with a fresh password/token. */
    private synchronized void invalidateClient() {
        if (adminClient != null) {
            try { adminClient.close(); } catch (Exception ignored) {}
            adminClient = null;
        }
    }

    /**
     * Runs {@code op}; on a Keycloak {@code 401 Unauthorized} it invalidates the
     * cached admin client (forcing a password re-resolve + token re-fetch) and
     * retries exactly once. Any other exception, or a second 401, propagates.
     */
    private <T> T withAdminRetry(Supplier<T> op) {
        try {
            return op.get();
        } catch (jakarta.ws.rs.NotAuthorizedException first) {
            log.warn("Keycloak admin call returned 401 — rebuilding admin client with a fresh password and retrying once");
            invalidateClient();
            return op.get();
        }
    }

    // ── Admin-auth health probe ────────────────────────────────────────────────

    /** Outcome of {@link #probeAdminAuth()} — drives {@code KeycloakAdminHealthIndicator}. */
    public enum AdminAuthState {
        /** A master admin token was obtained — tenant provisioning will work. */
        READY,
        /** Password not yet available, or Keycloak not reachable yet (transient boot window). */
        PENDING,
        /** Password IS present but Keycloak rejected it (401) — a restart won't fix this. */
        MISCONFIGURED
    }

    /** Immutable result of an admin-auth probe. */
    public record AdminAuthProbe(AdminAuthState state, String detail) {}

    /**
     * Lightweight check that the platform can authenticate to Keycloak as the
     * master admin. Used by the health indicator so a genuinely wrong/stale admin
     * password surfaces as DOWN, while the transient "secret not seeded yet" boot
     * window stays UP (PENDING) and never wedges the app layer.
     */
    public AdminAuthProbe probeAdminAuth() {
        String pw = resolveAdminPassword();
        if (pw == null || pw.isBlank()) {
            return new AdminAuthProbe(AdminAuthState.PENDING, "Keycloak admin password not yet available");
        }
        try {
            // getAccessToken() triggers a master-realm login on first use / after
            // expiry; a bad password throws NotAuthorizedException.
            client().tokenManager().getAccessToken();
            return new AdminAuthProbe(AdminAuthState.READY, "authenticated to Keycloak master realm");
        } catch (jakarta.ws.rs.NotAuthorizedException e) {
            invalidateClient();
            return new AdminAuthProbe(AdminAuthState.MISCONFIGURED,
                    "Keycloak rejected the admin credentials (401) — check KEYCLOAK_ADMIN_PASSWORD");
        } catch (Exception e) {
            // Network / not-up-yet: transient, don't flag DOWN.
            invalidateClient();
            return new AdminAuthProbe(AdminAuthState.PENDING,
                    "Keycloak admin API not reachable yet: " + e.getMessage());
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
