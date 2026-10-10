package io.orphos.eip.admin.auth.repo;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.auth.model.AuthEventType;
import io.orphos.eip.admin.auth.model.AuthMethodType;
import io.orphos.eip.admin.common.Ids;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

/**
 * All ArcadeDB access for the auth subsystem.
 *
 * <p>Centralised so other services use the same field names. Every write uses
 * parameterised SQL - no string concatenation of caller input.</p>
 *
 * <p>Every row carries a {@code tenantId} stamp and every read/update/delete
 * filters by it (pulled from {@link TenantContextHolder}). Cross-tenant lookups
 * are not supported through this repository — platform-admin flows that need
 * to operate cross-tenant should target the {@link ArcadeDbTemplate} directly
 * after explicitly binding the destination tenant via
 * {@code TenantContextHolder.runWith}.</p>
 */
@Repository
public class AuthRepository {

    private final ArcadeDbTemplate arcade;

    public AuthRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    // ----- LocalUser -----
    public Optional<Map<String, Object>> findLocalUserByEmail(String email) {
        var rows = arcade.query(
                "SELECT FROM LocalUser WHERE email = :email AND tenantId = :tenantId LIMIT 1",
                Map.of("email", email, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Map<String, Object> insertLocalUser(String email, String hashedPassword, String displayName) {
        String id = Ids.newId();
        Instant now = Instant.now();
        arcade.commandNoResult(
                "INSERT INTO LocalUser SET id = :id, tenantId = :tenantId, " +
                        "email = :email, passwordHash = :hash, " +
                        "displayName = :dn, status = 'ACTIVE', failedAttempts = 0, " +
                        "createdAt = :now, updatedAt = :now",
                Map.of("id", id, "tenantId", currentTenantId(), "email", email,
                        "hash", hashedPassword,
                        "dn", displayName == null ? "" : displayName, "now", now.toString()));
        return Map.of("id", id, "email", email, "displayName", displayName);
    }

    public void updateLocalUserPassword(String userId, String hashedPassword) {
        arcade.commandNoResult(
                "UPDATE LocalUser SET passwordHash = :hash, failedAttempts = 0, " +
                        "lockedUntil = null, updatedAt = :now " +
                        "WHERE id = :id AND tenantId = :tenantId",
                Map.of("hash", hashedPassword, "now", Instant.now().toString(),
                        "id", userId, "tenantId", currentTenantId()));
    }

    public void recordFailedAttempt(String userId, int newCount, Instant lockedUntil) {
        Map<String, Object> p = new HashMap<>();
        p.put("c", newCount);
        p.put("lu", lockedUntil == null ? null : lockedUntil.toString());
        p.put("now", Instant.now().toString());
        p.put("id", userId);
        p.put("tenantId", currentTenantId());
        arcade.commandNoResult(
                "UPDATE LocalUser SET failedAttempts = :c, lockedUntil = :lu, updatedAt = :now " +
                        "WHERE id = :id AND tenantId = :tenantId",
                p);
    }

    public void resetFailedAttempts(String userId) {
        arcade.commandNoResult(
                "UPDATE LocalUser SET failedAttempts = 0, lockedUntil = null, updatedAt = :now " +
                        "WHERE id = :id AND tenantId = :tenantId",
                Map.of("now", Instant.now().toString(),
                        "id", userId, "tenantId", currentTenantId()));
    }

    // ----- IdentityProvider -----
    public List<Map<String, Object>> listProviders() {
        return arcade.query(
                "SELECT FROM IdentityProvider WHERE tenantId = :tenantId ORDER BY name",
                Map.of("tenantId", currentTenantId()));
    }

    public Optional<Map<String, Object>> findProviderByName(String name) {
        var rows = arcade.query(
                "SELECT FROM IdentityProvider WHERE name = :n AND tenantId = :tenantId LIMIT 1",
                Map.of("n", name, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public String upsertProvider(String name, AuthMethodType type, boolean enabled,
                                  boolean allowSelfRegistration, List<String> allowedDomains,
                                  Map<String, Object> config) {
        var existing = findProviderByName(name);
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", currentTenantId());
        p.put("n", name);
        p.put("t", type.name());
        p.put("e", enabled);
        p.put("a", allowSelfRegistration);
        p.put("d", allowedDomains == null ? List.of() : allowedDomains);
        p.put("c", config == null ? Map.of() : config);
        p.put("now", Instant.now().toString());
        if (existing.isPresent()) {
            arcade.commandNoResult(
                    "UPDATE IdentityProvider SET type = :t, enabled = :e, " +
                            "allowSelfRegistration = :a, allowedEmailDomains = :d, " +
                            "config = :c, updatedAt = :now " +
                            "WHERE name = :n AND tenantId = :tenantId",
                    p);
            return Objects.toString(existing.get().get("id"));
        }
        String id = Ids.newId();
        p.put("id", id);
        arcade.commandNoResult(
                "INSERT INTO IdentityProvider SET id = :id, tenantId = :tenantId, name = :n, type = :t, " +
                        "enabled = :e, allowSelfRegistration = :a, allowedEmailDomains = :d, " +
                        "config = :c, createdAt = :now, updatedAt = :now",
                p);
        return id;
    }

    public void deleteProvider(String name) {
        arcade.commandNoResult(
                "DELETE FROM IdentityProvider WHERE name = :n AND tenantId = :tenantId",
                Map.of("n", name, "tenantId", currentTenantId()));
    }

    // ----- AuthMethod (per-tenant toggles) -----
    public Map<String, Object> getOrInitAuthMethodConfig() {
        String tenantId = currentTenantId();
        var rows = arcade.query(
                "SELECT FROM AuthMethod WHERE tenantId = :tenantId LIMIT 1",
                Map.of("tenantId", tenantId));
        if (!rows.isEmpty()) return rows.get(0);

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("id", Ids.newId());
        defaults.put("tenantId", tenantId);
        defaults.put("localEnabled", true);
        defaults.put("oidcEnabled", false);
        defaults.put("samlEnabled", false);
        defaults.put("ldapEnabled", false);
        defaults.put("defaultMethod", AuthMethodType.LOCAL.name());
        defaults.put("createdAt", Instant.now().toString());
        defaults.put("updatedAt", Instant.now().toString());
        arcade.commandNoResult(
                "INSERT INTO AuthMethod SET id = :id, tenantId = :tenantId, localEnabled = :l, " +
                        "oidcEnabled = :o, samlEnabled = :s, ldapEnabled = :ld, defaultMethod = :d, " +
                        "createdAt = :now, updatedAt = :now",
                Map.of("id", defaults.get("id"), "tenantId", tenantId,
                        "l", true, "o", false, "s", false, "ld", false,
                        "d", AuthMethodType.LOCAL.name(), "now", Instant.now().toString()));
        return defaults;
    }

    public void updateAuthMethodConfig(boolean local, boolean oidc, boolean saml, boolean ldap,
                                        AuthMethodType defaultMethod) {
        // Insert the per-tenant defaults row if missing — the UPDATE below
        // is otherwise a no-op for newly-provisioned tenants.
        getOrInitAuthMethodConfig();
        // Scoping the UPDATE by tenantId is critical: previously this command
        // had NO WHERE clause and would rewrite every tenant's AuthMethod row.
        arcade.commandNoResult(
                "UPDATE AuthMethod SET localEnabled = :l, oidcEnabled = :o, " +
                        "samlEnabled = :s, ldapEnabled = :ld, defaultMethod = :d, updatedAt = :now " +
                        "WHERE tenantId = :tenantId",
                Map.of("l", local, "o", oidc, "s", saml, "ld", ldap,
                        "d", defaultMethod.name(), "now", Instant.now().toString(),
                        "tenantId", currentTenantId()));
    }

    // ----- UserAuthBinding -----
    public Optional<Map<String, Object>> findBinding(String userKey) {
        var rows = arcade.query(
                "SELECT FROM UserAuthBinding WHERE userKey = :u AND tenantId = :tenantId LIMIT 1",
                Map.of("u", userKey, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void upsertBinding(String userKey, AuthMethodType method, String providerName) {
        String tenantId = currentTenantId();
        if (findBinding(userKey).isPresent()) {
            arcade.commandNoResult(
                    "UPDATE UserAuthBinding SET method = :m, providerName = :p, updatedAt = :now " +
                            "WHERE userKey = :u AND tenantId = :tenantId",
                    Map.of("m", method.name(), "p", providerName == null ? "" : providerName,
                            "now", Instant.now().toString(), "u", userKey, "tenantId", tenantId));
            return;
        }
        arcade.commandNoResult(
                "INSERT INTO UserAuthBinding SET id = :id, tenantId = :tenantId, userKey = :u, " +
                        "method = :m, providerName = :p, createdAt = :now, updatedAt = :now",
                Map.of("id", Ids.newId(), "tenantId", tenantId, "u", userKey, "m", method.name(),
                        "p", providerName == null ? "" : providerName,
                        "now", Instant.now().toString()));
    }

    // ----- AuthEvent (analytics/monitoring) -----
    public void recordEvent(String userKey, AuthEventType type, AuthMethodType method,
                             boolean success, String reason, String remoteIp) {
        arcade.commandNoResult(
                "INSERT INTO AuthEvent SET id = :id, tenantId = :tenantId, userKey = :u, " +
                        "type = :t, method = :m, success = :s, reason = :r, " +
                        "remoteIpHash = :ip, createdAt = :now",
                Map.of("id", Ids.newId(),
                        "tenantId", currentTenantId(),
                        "u", userKey == null ? "anonymous" : userKey,
                        "t", type.name(),
                        "m", method == null ? "" : method.name(),
                        "s", success,
                        "r", reason == null ? "" : reason,
                        // We hash the IP rather than store it directly - keeps audit value
                        // without storing raw network identifiers as PII.
                        "ip", remoteIp == null ? "" : Integer.toHexString(remoteIp.hashCode()),
                        "now", Instant.now().toString()));
    }

    public List<Map<String, Object>> recentEvents(Instant since) {
        return arcade.query(
                "SELECT FROM AuthEvent WHERE createdAt >= :s AND tenantId = :tenantId " +
                        "ORDER BY createdAt DESC LIMIT 5000",
                Map.of("s", since.toString(), "tenantId", currentTenantId()));
    }

    public List<Map<String, Object>> failuresFor(String userKey, Instant since) {
        return arcade.query(
                "SELECT FROM AuthEvent WHERE userKey = :u AND tenantId = :tenantId " +
                        "AND type = 'LOGIN_FAILURE' AND createdAt >= :s ORDER BY createdAt DESC",
                Map.of("u", userKey, "s", since.toString(), "tenantId", currentTenantId()));
    }

    // ----- AuthAuditLog -----
    public void appendAudit(String actorKey, String action, String target,
                             Map<String, Object> detail) {
        arcade.commandNoResult(
                "INSERT INTO AuthAuditLog SET id = :id, tenantId = :tenantId, actorKey = :a, " +
                        "action = :ac, target = :t, detail = :d, createdAt = :now",
                Map.of("id", Ids.newId(), "tenantId", currentTenantId(),
                        "a", actorKey == null ? "system" : actorKey,
                        "ac", action, "t", target == null ? "" : target,
                        "d", detail == null ? Map.of() : detail,
                        "now", Instant.now().toString()));
    }

    public List<Map<String, Object>> listAudit(int limit) {
        return arcade.query(
                "SELECT FROM AuthAuditLog WHERE tenantId = :tenantId " +
                        "ORDER BY createdAt DESC LIMIT :l",
                Map.of("l", limit, "tenantId", currentTenantId()));
    }

    // ----- Helpers -----
    private static String currentTenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }
}
