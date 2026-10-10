package io.orphos.eip.admin.roles.controller;

import io.orphos.eip.admin.roles.service.RoleAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Tenant-scoped role aliases for the whole SPA. Unlike the admin endpoints this
 * is readable by ANY authenticated user in the tenant, so role display names
 * (e.g. "President" for {@code ceo}) render consistently in the header, role
 * picker, and dashboards — not just on admin screens.
 *
 * <p>Authorization is configured in {@code SecurityConfig}: authenticated, not
 * admin-gated.</p>
 */
@RestController
@RequestMapping("/api/roles")
public class RolePublicController {

    private final RoleAdminService service;

    public RolePublicController(RoleAdminService service) {
        this.service = service;
    }

    /** {@code { roleKey: alias }} for the current tenant. Empty map if unresolved. */
    @GetMapping("/aliases")
    public Map<String, String> aliases() {
        try {
            return service.aliasMap();
        } catch (Exception e) {
            return Map.of();
        }
    }
}
