package io.orphos.eip.admin.access.controller;

import io.orphos.eip.admin.access.service.AccessControlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only access map for the CURRENT tenant, readable by any authenticated
 * tenant user (not just admins). The user dashboard uses this to decide which
 * sections a role may see — dashboard users can't call the admin-only
 * {@code /api/admin/access-control}, so the visibility policy is exposed here
 * (it is not sensitive: it only says which roles see which sections).
 */
@RestController
@RequestMapping("/api/access")
public class AccessGrantsPublicController {

    private final AccessControlService service;

    public AccessGrantsPublicController(AccessControlService service) {
        this.service = service;
    }

    @GetMapping("/grants")
    public Map<String, Object> grants() {
        List<Map<String, Object>> grants;
        try {
            grants = service.listGrants();
        } catch (Exception e) {
            // No tenant in scope (e.g. platform admin without impersonation) — no policy.
            grants = List.of();
        }
        return Map.of(
                "grants", grants,
                "immutableRoles", AccessControlService.IMMUTABLE_ROLES,
                "lockedResources", AccessControlService.LOCKED_RESOURCES);
    }
}
