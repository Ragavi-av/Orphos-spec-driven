package io.orphos.eip.admin.auth.controller;

import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Internal user directory for the event-communication framework.
 *
 * <p>admin-console-service's RecipientResolver calls this to turn a
 * notification rule's audience (PLATFORM_ADMIN / TENANT_ADMIN / ALL_USERS /
 * ROLE) into concrete email addresses from the relevant Keycloak realm.</p>
 *
 * <p>Not JWT-gated — guarded by the shared {@code X-Internal-Token} (same
 * contract as the tenant-discovery endpoints; fail-closed when the token is
 * unset). Responses contain email addresses by design: they are consumed
 * solely by the server-side mailer and never surface to browsers.</p>
 */
@RestController
@RequestMapping("/api/internal/users")
public class InternalUserDirectoryController {

    private static final Logger log = LogManager.getLogger(InternalUserDirectoryController.class);

    /** Realm roles that mark a tenant-realm user as an administrator. */
    private static final Set<String> TENANT_ADMIN_ROLES = Set.of("admin");
    /** Realm roles that mark a platform-realm user as a platform administrator. */
    private static final Set<String> PLATFORM_ADMIN_ROLES = Set.of("platform_admin", "admin");

    private static final int MAX_USERS = 500;

    private final KeycloakProvisioningClient keycloak;
    private final TenantRepository tenants;
    private final String internalToken;
    private final String platformRealm;

    public InternalUserDirectoryController(
            KeycloakProvisioningClient keycloak,
            TenantRepository tenants,
            @Value("${eip.security.internal-service-token:}") String internalToken,
            @Value("${eip.security.platform-issuer:}") String platformIssuer,
            @Value("${eip.security.platform-realm:}") String platformRealmOverride) {
        this.keycloak = keycloak;
        this.tenants = tenants;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.platformRealm = resolvePlatformRealm(platformIssuer, platformRealmOverride);
    }

    /**
     * @param audience PLATFORM_ADMIN | TENANT_ADMIN | ALL_USERS | ROLE
     * @param role     realm role name, required when audience == ROLE
     * @param tenantId tenant whose realm to read (required except PLATFORM_ADMIN)
     */
    @GetMapping("/emails")
    public ResponseEntity<?> emails(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam String audience,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String tenantId) {

        if (internalToken.isEmpty() || token == null || !internalToken.equals(token.trim())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid internal token"));
        }

        try {
            return switch (audience) {
                case "PLATFORM_ADMIN" -> ok(usersWithAnyRole(platformRealm, PLATFORM_ADMIN_ROLES));
                case "TENANT_ADMIN"   -> ok(usersWithAnyRole(realmOf(tenantId), TENANT_ADMIN_ROLES));
                case "ALL_USERS"      -> ok(usersWithAnyRole(realmOf(tenantId), null));
                case "ROLE" -> {
                    if (role == null || role.isBlank()) {
                        yield ResponseEntity.badRequest().body(Map.of("error", "role is required for audience=ROLE"));
                    }
                    yield ok(usersWithAnyRole(realmOf(tenantId), Set.of(role.trim())));
                }
                default -> ResponseEntity.badRequest().body(Map.of("error", "unknown audience: " + audience));
            };
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("User-directory lookup failed (audience={}, tenant={}): {}",
                    audience, tenantId, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "directory lookup failed"));
        }
    }

    /**
     * Lists enabled users of {@code realm} holding any of {@code roles}
     * ({@code null} roles = every enabled user), returning their unique,
     * non-blank email addresses.
     */
    private Set<String> usersWithAnyRole(String realm, Set<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (realm == null || realm.isBlank()) return out;
        List<KeycloakProvisioningClient.RealmUser> users = keycloak.listRealmUsers(realm, MAX_USERS);
        for (KeycloakProvisioningClient.RealmUser u : users) {
            if (!u.enabled()) continue;
            if (u.email() == null || u.email().isBlank()) continue;
            if (roles != null && u.realmRoles().stream().noneMatch(roles::contains)) continue;
            out.add(u.email());
        }
        return out;
    }

    private String realmOf(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required for this audience");
        }
        UUID id;
        try {
            id = UUID.fromString(tenantId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed tenantId");
        }
        return tenants.findById(id)
                .map(Tenant::keycloakRealm)
                .orElseThrow(() -> new IllegalArgumentException("unknown tenant"));
    }

    private static ResponseEntity<Map<String, Object>> ok(Set<String> emails) {
        return ResponseEntity.ok(Map.of("emails", emails, "count", emails.size()));
    }

    /**
     * The platform realm hosts platform_admin users. Precedence: explicit
     * override property, then the last path segment of the platform issuer
     * URL, then the conventional name.
     */
    private static String resolvePlatformRealm(String platformIssuer, String override) {
        if (override != null && !override.isBlank()) return override.trim();
        if (platformIssuer != null && !platformIssuer.isBlank()) {
            String s = platformIssuer.replaceAll("/+$", "");
            int slash = s.lastIndexOf('/');
            if (slash >= 0 && slash < s.length() - 1) return s.substring(slash + 1);
        }
        return "platform";
    }
}
