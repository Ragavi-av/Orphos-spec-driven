package io.orphos.eip.admin.auth.controller;

import io.orphos.eip.admin.roles.service.RoleAssignmentGuard;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.common.events.EipEventPublisher;
import io.orphos.eip.common.security.EipRoles;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal SCIM 2.0 surface for tenant user management.
 *
 * <p>This implementation covers the operations the User Management tab
 * calls — listing users, creating and deleting them, and patching role,
 * display name, email, active state, and preferred language. It does
 * <em>not</em> aim to be SCIM-spec complete; the wire format mirrors SCIM
 * enough that the SPA reads from {@code Resources[]} and POSTs the standard
 * PatchOp envelope without any backend-specific shape.</p>
 *
 * <p>Data source is the <b>current tenant's Keycloak realm</b> — resolved
 * via {@link TenantContextHolder} so the platform-admin
 * impersonation flow (X-Tenant-Id header) Just Works: pick a tenant in
 * the header bar and this endpoint reads that tenant's realm.</p>
 *
 * <p>Tenant-scoped admins can manage their own realm's users. Platform
 * admins acting without an impersonation header (no tenant context) get
 * an empty list — there's no cross-tenant aggregate view.</p>
 *
 * <p>Writes are subject to {@link RoleAssignmentGuard}: a caller can only
 * grant, revoke, or act on users holding roles they are allowed to manage
 * (a {@code secops_admin} manages users but cannot mint or touch admins), and
 * never changes their own roles.</p>
 */
@RestController
@RequestMapping("/api/admin/scim/v2")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'secops_admin')")
public class AdminScimController {

    private static final Logger log = LogManager.getLogger(AdminScimController.class);

    /**
     * Realm roles whose grant counts as "admin rights" for the
     * event-communication framework (USER_ADMIN_CREATED alerts).
     */
    private static final java.util.Set<String> ADMIN_RIGHT_ROLES = java.util.Set.of(
            EipRoles.PLATFORM_ADMIN, EipRoles.ADMIN, EipRoles.SECOPS_ADMIN,
            EipRoles.DATA_AI_ADMIN, EipRoles.INGESTION_ADMIN);

    private final KeycloakProvisioningClient keycloak;
    private final TenantRepository tenants;
    private final EipEventPublisher events;
    private final RoleAssignmentGuard roleGuard;

    public AdminScimController(KeycloakProvisioningClient keycloak, TenantRepository tenants,
                               EipEventPublisher events, RoleAssignmentGuard roleGuard) {
        this.keycloak = keycloak;
        this.tenants = tenants;
        this.events = events;
        this.roleGuard = roleGuard;
    }

    // ── List users ─────────────────────────────────────────────────────────

    @GetMapping("/Users")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(name = "count", defaultValue = "200") int count) {

        Map<String, Object> out = scimListEnvelope();

        String realm = currentRealmOrNull();
        if (realm == null) {
            // Platform admin with no impersonation, or tenant context missing.
            // Return an empty SCIM list — the SPA renders the "No users found"
            // state, which is correct: there's no realm in scope.
            out.put("totalResults", 0);
            out.put("Resources", List.of());
            return ResponseEntity.ok(out);
        }

        List<KeycloakProvisioningClient.RealmUser> users = keycloak.listRealmUsers(realm, count);
        List<Map<String, Object>> resources = new ArrayList<>(users.size());
        for (KeycloakProvisioningClient.RealmUser u : users) {
            resources.add(toScimUser(u));
        }
        out.put("totalResults", resources.size());
        out.put("Resources", resources);
        return ResponseEntity.ok(out);
    }

    // ── Create user ──────────────────────────────────────────────────────────

    @PostMapping("/Users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        String realm = currentRealmOrNull();
        if (realm == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No tenant in scope. Platform admins must pick a tenant first."));
        }

        String email       = firstEmail(body);
        String userName    = str(body.get("userName"));
        if (userName.isBlank()) userName = email;
        String displayName = str(body.get("displayName"));
        String password    = str(body.get("password"));
        String role        = firstRole(body);
        // Default to a must-change-at-first-login password unless the caller
        // explicitly opts out (temporaryPassword:false).
        boolean temporary  = !Boolean.FALSE.equals(body.get("temporaryPassword"));

        if (userName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A username or email is required to create a user."));
        }
        if (role != null && !role.isBlank()) {
            roleGuard.requireCanManage(role);
        }

        try {
            KeycloakProvisioningClient.RealmUser created =
                    keycloak.createRealmUser(realm, userName, email, displayName, role, password, temporary);
            // Report a newly-created admin the same way a role grant does. Pass a
            // synthetic "before" with no roles so the grant is detected and named.
            if (role != null && !role.isBlank()) {
                KeycloakProvisioningClient.RealmUser fresh = new KeycloakProvisioningClient.RealmUser(
                        created.id(), created.username(), created.email(), created.displayName(),
                        created.enabled(), List.of(), created.locale());
                publishAdminGrantEvent(fresh, List.of(role));
            }
            return ResponseEntity.status(201).body(toScimUser(created));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Failed to create user in realm {}: {}", realm, e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "error", "Keycloak rejected the create: " + e.getMessage()));
        }
    }

    // ── Delete user ────────────────────────────────────────────────────────────

    @DeleteMapping("/Users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        String realm = currentRealmOrNull();
        if (realm == null) return ResponseEntity.badRequest().build();
        KeycloakProvisioningClient.RealmUser target = keycloak.readRealmUser(realm, id);
        if (target == null) return ResponseEntity.notFound().build();
        roleGuard.requireCanActOnUser(id, target.realmRoles(), false);
        try {
            keycloak.deleteRealmUser(realm, id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.warn("Failed to delete user {} in realm {}: {}", id, realm, e.getMessage());
            return ResponseEntity.status(502).build();
        }
    }

    // ── Patch user (role, profile, status, language) ────────────────────────────

    @PatchMapping("/Users/{id}")
    public ResponseEntity<Map<String, Object>> patchUser(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {

        String realm = currentRealmOrNull();
        if (realm == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No tenant in scope. Platform admins must pick a tenant first."));
        }

        ScimPatch patch = parsePatch(body);
        if (patch.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Patch body must contain at least one supported Operation: "
                            + "roles, displayName, emails/email, active, or preferredLanguage."));
        }

        // Snapshot the pre-patch role set so the event framework can tell a
        // genuine admin-rights grant apart from an unrelated role reshuffle.
        KeycloakProvisioningClient.RealmUser before = keycloak.readRealmUser(realm, id);
        if (before == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found."));
        }
        // No-escalation: the caller must be able to manage every role this patch adds or
        // removes, may not change their own roles, and may not edit or disable a user who
        // holds roles above them. Your own profile (name, language) stays editable.
        if (patch.roles != null) {
            roleGuard.requireCanChangeRoles(id, before.realmRoles(), patch.roles);
        }
        if (patch.touchesProfile()) {
            roleGuard.requireCanActOnUser(id, before.realmRoles(), !Boolean.FALSE.equals(patch.active));
        }

        try {
            if (patch.touchesProfile()) {
                keycloak.updateRealmUser(realm, id, patch.displayName, patch.email, patch.active, patch.locale);
            }
            if (patch.roles != null) {
                keycloak.setUserRealmRoles(realm, id, patch.roles);
                publishAdminGrantEvent(before, patch.roles);
            }
        } catch (Exception e) {
            log.warn("Failed to patch user {} in realm {}: {}", id, realm, e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "error", "Keycloak rejected the update: " + e.getMessage()));
        }

        // Re-read so the SPA can refresh from the response if it wants to.
        KeycloakProvisioningClient.RealmUser updated = keycloak.readRealmUser(realm, id);
        if (updated == null) {
            return ResponseEntity.ok(Map.of("id", id));
        }
        return ResponseEntity.ok(toScimUser(updated));
    }

    /**
     * Emits USER_ADMIN_CREATED (event-communication framework) when the patch
     * granted an admin-rights role the user did not previously hold. Publishing
     * is fire-and-forget; a failure never affects the SCIM response. The user
     * identity is intentionally reported as username, not email.
     */
    private void publishAdminGrantEvent(KeycloakProvisioningClient.RealmUser before,
                                        List<String> targetRoles) {
        try {
            List<String> previousRoles = before == null ? List.of() : before.realmRoles();
            List<String> gainedAdminRoles = targetRoles.stream()
                    .filter(ADMIN_RIGHT_ROLES::contains)
                    .filter(r -> !previousRoles.contains(r))
                    .toList();
            if (gainedAdminRoles.isEmpty()) return;

            String username = before != null ? before.username() : "(new user)";
            String grantedBy = SecurityContextHolder.getContext().getAuthentication() != null
                    ? SecurityContextHolder.getContext().getAuthentication().getName()
                    : "unknown";
            String tenantId = TenantContextHolder.current()
                    .map(c -> c.tenantId().toString())
                    .orElse(null);
            events.publish(
                    EipEventPublisher.USER_ADMIN_CREATED,
                    EipEventPublisher.SEVERITY_WARNING,
                    "Admin access granted to user '" + username + "'",
                    "User '" + username + "' was granted administrator rights ("
                            + String.join(", ", gainedAdminRoles) + ") via the User Management tab.",
                    tenantId,
                    Map.of("username", username,
                            "roles", String.join(", ", gainedAdminRoles),
                            "grantedBy", grantedBy));
        } catch (Exception e) {
            log.warn("USER_ADMIN_CREATED event not published: {}", e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Mutable holder for the fields a PATCH may touch. Any field left null means
     * "not present in this patch — leave unchanged".
     */
    private static final class ScimPatch {
        List<String> roles;     // null = don't touch; empty = strip all app roles
        String displayName;
        String email;
        Boolean active;
        String locale;          // preferred language; "" clears it

        boolean touchesProfile() {
            return displayName != null || email != null || active != null || locale != null;
        }

        boolean isEmpty() {
            return roles == null && !touchesProfile();
        }
    }

    /**
     * Parses a SCIM PatchOp envelope into a {@link ScimPatch}. Understands the
     * paths the SPA sends: {@code roles}, {@code displayName}, {@code emails} (or
     * {@code email}), {@code active}, and {@code preferredLanguage}.
     */
    private static ScimPatch parsePatch(Map<String, Object> body) {
        ScimPatch patch = new ScimPatch();
        if (body == null) return patch;
        Object ops = body.get("Operations");
        if (!(ops instanceof List<?> opList)) return patch;

        for (Object op : opList) {
            if (!(op instanceof Map<?, ?> m)) continue;
            String path = String.valueOf(m.get("path"));
            Object value = m.get("value");
            switch (path) {
                case "roles" -> patch.roles = rolesFromValue(value);
                case "displayName" -> patch.displayName = value == null ? "" : String.valueOf(value);
                case "email", "emails" -> patch.email = emailFromValue(value);
                case "active" -> patch.active = boolFromValue(value);
                case "preferredLanguage", "locale" -> patch.locale = value == null ? "" : String.valueOf(value);
                default -> { /* ignore unsupported paths */ }
            }
        }
        return patch;
    }

    private static List<String> rolesFromValue(Object value) {
        if (!(value instanceof List<?> values)) return null;
        List<String> roles = new ArrayList<>();
        for (Object v : values) {
            if (v instanceof Map<?, ?> rm) {
                Object name = rm.get("value");
                if (name != null) roles.add(String.valueOf(name));
            } else if (v != null) {
                roles.add(String.valueOf(v));
            }
        }
        return roles;
    }

    /** Accepts a bare string or a SCIM {@code emails:[{value,...}]} array. */
    private static String emailFromValue(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> em && em.get("value") != null) {
                    return String.valueOf(em.get("value"));
                }
            }
            return "";
        }
        return String.valueOf(value);
    }

    private static Boolean boolFromValue(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return null;
        return Boolean.valueOf(String.valueOf(value));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /** Pulls the primary/first email from a create body ({@code emails[]} or {@code email}). */
    private static String firstEmail(Map<String, Object> body) {
        String e = emailFromValue(body.get("emails"));
        if (!e.isBlank()) return e;
        return str(body.get("email"));
    }

    /** Pulls the first role name from a create body ({@code roles[]} or {@code role}/{@code currentRole}). */
    private static String firstRole(Map<String, Object> body) {
        List<String> roles = rolesFromValue(body.get("roles"));
        if (roles != null && !roles.isEmpty()) return roles.get(0);
        String r = str(body.get("role"));
        if (!r.isBlank()) return r;
        return str(body.get("currentRole"));
    }

    private static Map<String, Object> toScimUser(KeycloakProvisioningClient.RealmUser u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        out.put("id", u.id());
        out.put("userName", u.username());
        out.put("displayName", u.displayName());
        if (u.email() != null) {
            out.put("emails", List.of(Map.of("value", u.email(), "primary", true)));
        }
        out.put("active", u.enabled());
        if (u.locale() != null && !u.locale().isBlank()) {
            out.put("preferredLanguage", u.locale());
        }
        // Convenience fields the SPA reads directly without parsing the SCIM roles array.
        out.put("currentRole", primaryOf(u.realmRoles()));
        List<Map<String, Object>> roles = new ArrayList<>(u.realmRoles().size());
        for (int i = 0; i < u.realmRoles().size(); i++) {
            roles.add(Map.of("value", u.realmRoles().get(i), "primary", i == 0));
        }
        out.put("roles", roles);
        return out;
    }

    private static String primaryOf(List<String> roles) {
        return (roles == null || roles.isEmpty()) ? "guest" : roles.get(0);
    }

    private static Map<String, Object> scimListEnvelope() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"));
        env.put("itemsPerPage", 200);
        env.put("startIndex", 1);
        return env;
    }

    /**
     * Returns the current tenant's Keycloak realm name, or null if there
     * is no tenant in scope (cross-tenant platform-admin request).
     */
    private String currentRealmOrNull() {
        UUID tenantId;
        try {
            tenantId = TenantContextHolder.requireTenantId();
        } catch (Exception e) {
            return null;
        }
        return tenants.findById(tenantId)
                .map(Tenant::keycloakRealm)
                .orElse(null);
    }
}
