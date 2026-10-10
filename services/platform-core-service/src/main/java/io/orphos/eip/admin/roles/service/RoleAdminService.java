package io.orphos.eip.admin.roles.service;

import io.orphos.eip.admin.access.service.AccessControlService;
import io.orphos.eip.admin.roles.dto.RoleDtos.CreateRoleRequest;
import io.orphos.eip.admin.roles.dto.RoleDtos.ResetResult;
import io.orphos.eip.admin.roles.dto.RoleDtos.RoleView;
import io.orphos.eip.admin.roles.dto.RoleDtos.UpdateRoleRequest;
import io.orphos.eip.admin.roles.registry.RoleRegistry;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.common.security.EipRoles;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orchestrates per-tenant role management: lists roles (built-in + custom) with
 * their aliases, creates/copies/edits/deletes custom Keycloak realm roles, and
 * resets to the application defaults. Aliases and colours live in
 * {@link RoleRegistry}; the roles themselves are real Keycloak realm roles so
 * they are assignable and propagate into the JWT.
 */
@Service
public class RoleAdminService {

    private static final Logger log = LogManager.getLogger(RoleAdminService.class);

    /** Built-in roles seeded into every tenant realm — the shared catalog in {@link EipRoles}. */
    private static final Set<String> DEFAULT_ROLE_KEYS = Set.copyOf(EipRoles.TENANT_BUILT_IN);

    /** Default display labels for built-in roles (mirrors the SPA's useRole labels). */
    private static final Map<String, String> DEFAULT_LABELS = Map.ofEntries(
            Map.entry("admin", "Admin"),
            Map.entry("ceo", "CEO"),
            Map.entry("cto", "CTO"),
            Map.entry("cfo", "CFO"),
            Map.entry("coo", "COO"),
            Map.entry("chro", "CHRO"),
            Map.entry("executive", "Executive"),
            Map.entry("viewer", "Viewer"),
            Map.entry("guest", "Guest"),
            Map.entry("ingestion_admin", "Ingestion Admin"),
            Map.entry("data_ai_admin", "Data & AI Admin"),
            Map.entry("secops_admin", "SecOps Admin"));

    /** Default chip colours for built-in roles (mirrors the SPA's ROLE_COLORS). */
    private static final Map<String, String> DEFAULT_COLORS = Map.of(
            "admin", "#92400e",
            "ceo", "#1d4ed8",
            "executive", "#15803d",
            "viewer", "#475569",
            "guest", "#9d174d");
    private static final String FALLBACK_COLOR = "#2563eb";

    private static final Pattern KEY_RE = Pattern.compile("^[a-z][a-z0-9_]{1,35}$");

    private final KeycloakProvisioningClient keycloak;
    private final RoleRegistry registry;
    private final AccessControlService access;
    private final TenantRepository tenants;
    private final RoleAssignmentGuard guard;

    public RoleAdminService(KeycloakProvisioningClient keycloak, RoleRegistry registry,
                            AccessControlService access, TenantRepository tenants,
                            RoleAssignmentGuard guard) {
        this.keycloak = keycloak;
        this.registry = registry;
        this.access = access;
        this.tenants = tenants;
        this.guard = guard;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** All assignable roles for the tenant with their aliases/colours/flags. */
    public List<RoleView> listRoles() {
        String realm = requireRealm();
        Map<String, Map<String, Object>> meta = new LinkedHashMap<>();
        for (Map<String, Object> row : registry.list()) {
            meta.put(String.valueOf(row.get("roleKey")), row);
        }
        List<RoleView> out = new ArrayList<>();
        for (String key : keycloak.listRealmRoleNames(realm)) {
            if (AccessControlService.IMMUTABLE_ROLES.contains(key)) continue; // platform_admin etc.
            out.add(toView(realm, key, meta.get(key)));
        }
        // Stable order: built-ins first (canonical order), then custom alphabetically.
        out.sort((a, b) -> {
            if (a.isDefault() != b.isDefault()) return a.isDefault() ? -1 : 1;
            if (a.isDefault()) return Integer.compare(defaultRank(a.key()), defaultRank(b.key()));
            return a.key().compareTo(b.key());
        });
        return out;
    }

    /**
     * Role key → alias map for the whole tenant, drives the SPA's cross-system
     * display. Deliberately cheap (no per-role user-count calls) since this is
     * hit by every authenticated user on load — just the realm role list + the
     * metadata rows.
     */
    public Map<String, String> aliasMap() {
        String realm = requireRealm();
        Map<String, Map<String, Object>> meta = new LinkedHashMap<>();
        for (Map<String, Object> row : registry.list()) {
            meta.put(String.valueOf(row.get("roleKey")), row);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keycloak.listRealmRoleNames(realm)) {
            if (AccessControlService.IMMUTABLE_ROLES.contains(key)) continue;
            Map<String, Object> m = meta.get(key);
            String metaAlias = m == null ? "" : Objects.toString(m.get("alias"), "");
            out.put(key, !metaAlias.isBlank() ? metaAlias
                    : DEFAULT_LABELS.getOrDefault(key, humanize(key)));
        }
        return out;
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    public RoleView create(CreateRoleRequest req) {
        String realm = requireRealm();
        String key = req.key() == null ? "" : req.key().trim().toLowerCase();
        validateNewKey(realm, key);
        // A custom role inherits its base's privileges (EipJwtAuthoritiesConverter), so
        // building on a role is as privileged as granting it.
        if (req.basedOn() != null && !req.basedOn().isBlank()) {
            guard.requireCanManage(req.basedOn().trim());
        }
        String alias = blankToKeyLabel(req.alias(), key);
        String color = blankToDefaultColor(req.color());
        keycloak.createRealmRole(realm, key, alias);
        registry.upsert(key, alias, color, true, req.basedOn());
        if (req.basedOn() != null && !req.basedOn().isBlank()) {
            access.copyGrants(req.basedOn().trim(), key);
        }
        log.info("Role created: tenant={}, key={}, basedOn={}", tenantId(), key, req.basedOn());
        return toView(realm, key, registry.get(key).orElse(null));
    }

    /** Copy an existing role into a new custom role, cloning its access grants. */
    public RoleView copy(String sourceKey, String newKey, String alias, String color) {
        String src = sourceKey == null ? "" : sourceKey.trim();
        if (src.isBlank()) throw new IllegalArgumentException("Source role is required.");
        return create(new CreateRoleRequest(newKey, alias, color, src));
    }

    /** Edit a role's alias/colour (the role key itself is immutable). Works for built-in and custom roles. */
    public RoleView update(String key, UpdateRoleRequest req) {
        String realm = requireRealm();
        String k = key == null ? "" : key.trim();
        if (AccessControlService.IMMUTABLE_ROLES.contains(k)) {
            throw new IllegalArgumentException("Role '" + k + "' is immutable and cannot be edited.");
        }
        if (!keycloak.realmRoleExists(realm, k)) {
            throw new IllegalArgumentException("Unknown role '" + k + "'.");
        }
        Optional<Map<String, Object>> existing = registry.get(k);
        boolean custom = existing.map(m -> Boolean.TRUE.equals(m.get("custom")))
                .orElse(!DEFAULT_ROLE_KEYS.contains(k));
        String basedOn = existing.map(m -> Objects.toString(m.get("basedOn"), "")).orElse("");
        String alias = blankToKeyLabel(req.alias(), k);
        String color = blankToDefaultColor(req.color());
        registry.upsert(k, alias, color, custom, basedOn);
        log.info("Role updated: tenant={}, key={}, alias='{}'", tenantId(), k, alias);
        return toView(realm, k, registry.get(k).orElse(null));
    }

    /**
     * Delete a custom role. Refuses built-in/immutable roles and any role still
     * held by at least one user (the caller must reassign those users first).
     */
    public void delete(String key) {
        String realm = requireRealm();
        String k = key == null ? "" : key.trim();
        if (DEFAULT_ROLE_KEYS.contains(k) || AccessControlService.IMMUTABLE_ROLES.contains(k)) {
            throw new IllegalArgumentException("Role '" + k + "' is a built-in role and cannot be deleted.");
        }
        if (!keycloak.realmRoleExists(realm, k)) {
            throw new IllegalArgumentException("Unknown role '" + k + "'.");
        }
        guard.requireCanManage(k);
        int users = keycloak.countUsersWithRealmRole(realm, k);
        if (users > 0) {
            throw new RoleInUseException(k, users);
        }
        keycloak.deleteRealmRole(realm, k);
        access.clearRole(k);
        registry.delete(k);
        log.info("Role deleted: tenant={}, key={}", tenantId(), k);
    }

    /**
     * Reset to application defaults: delete every unused custom role (Keycloak +
     * grants), then clear all alias/colour overrides. Custom roles still held by
     * users are kept and reported (honouring the block-on-delete rule).
     */
    public ResetResult reset() {
        String realm = requireRealm();
        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String key : keycloak.listRealmRoleNames(realm)) {
            if (DEFAULT_ROLE_KEYS.contains(key) || AccessControlService.IMMUTABLE_ROLES.contains(key)) continue;
            if (keycloak.countUsersWithRealmRole(realm, key) > 0) {
                kept.add(key);
                continue;
            }
            keycloak.deleteRealmRole(realm, key);
            access.clearRole(key);
            removed.add(key);
        }
        registry.deleteAll();   // clears all aliases/colours (metadata) for the tenant
        log.info("Roles reset: tenant={}, removed={}, kept(in-use)={}", tenantId(), removed, kept);
        return new ResetResult(removed, kept);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private RoleView toView(String realm, String key, Map<String, Object> meta) {
        boolean isDefault = DEFAULT_ROLE_KEYS.contains(key);
        boolean custom = meta != null ? Boolean.TRUE.equals(meta.get("custom")) : !isDefault;
        String metaAlias = meta == null ? "" : Objects.toString(meta.get("alias"), "");
        String metaColor = meta == null ? "" : Objects.toString(meta.get("color"), "");
        String alias = !metaAlias.isBlank() ? metaAlias
                : DEFAULT_LABELS.getOrDefault(key, humanize(key));
        String color = !metaColor.isBlank() ? metaColor
                : DEFAULT_COLORS.getOrDefault(key, FALLBACK_COLOR);
        int users = keycloak.countUsersWithRealmRole(realm, key);
        boolean deletable = custom && !isDefault && users == 0;
        return new RoleView(key, alias, color, custom, isDefault, users, deletable);
    }

    private void validateNewKey(String realm, String key) {
        if (!KEY_RE.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Role key must be lowercase letters/digits/underscore, starting with a letter (2–36 chars).");
        }
        if (DEFAULT_ROLE_KEYS.contains(key) || EipRoles.RESERVED.contains(key)
                || key.startsWith("default-roles") || EipRoles.isKeycloakDefault(key)) {
            throw new IllegalArgumentException("Role key '" + key + "' is reserved.");
        }
        if (keycloak.realmRoleExists(realm, key)) {
            throw new IllegalArgumentException("A role named '" + key + "' already exists.");
        }
    }

    private static int defaultRank(String key) {
        List<String> order = List.of("admin", "ceo", "cto", "cfo", "coo", "chro", "executive", "viewer", "guest",
                "ingestion_admin", "data_ai_admin", "secops_admin");
        int i = order.indexOf(key);
        return i == -1 ? order.size() : i;
    }

    private static String blankToKeyLabel(String alias, String key) {
        return (alias == null || alias.isBlank()) ? DEFAULT_LABELS.getOrDefault(key, humanize(key)) : alias.trim();
    }

    private static String blankToDefaultColor(String color) {
        return (color == null || color.isBlank()) ? FALLBACK_COLOR : color.trim();
    }

    /** "operations_head" → "Operations Head", as a readable fallback label. */
    private static String humanize(String key) {
        String[] parts = key.replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }

    private String requireRealm() {
        UUID tenantId = TenantContextHolder.requireTenantId();
        return tenants.findById(tenantId).map(Tenant::keycloakRealm)
                .filter(r -> r != null && !r.isBlank())
                .orElseThrow(() -> new IllegalStateException("No Keycloak realm resolved for the current tenant."));
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    /** Thrown when a role can't be deleted because users still hold it → mapped to HTTP 409. */
    public static class RoleInUseException extends RuntimeException {
        public RoleInUseException(String role, int users) {
            super("Role '" + role + "' is still assigned to " + users
                    + " user(s). Reassign them before deleting this role.");
        }
    }
}
