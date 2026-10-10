package io.orphos.eip.admin.roles.controller;

import io.orphos.eip.admin.roles.registry.RegistryCustomRoleBaseSource;
import io.orphos.eip.common.security.InternalTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service endpoint returning a tenant's {@code customRole → basedOn} map, so
 * every service's JWT converter ({@code HttpCustomRoleBaseSource}) can let a custom role
 * inherit its base role's privileges.
 *
 * <p>Guarded like {@link io.orphos.eip.admin.access.controller.InternalAccessGrantsController}:
 * the shared internal token in-controller, on a path the {@code /api/internal/**} filter also
 * gates and nginx never proxies. The tenant is named by {@code X-Tenant-Id}.</p>
 */
@RestController
@RequestMapping("/api/internal/role-bases")
public class InternalRoleBasesController {

    private final RegistryCustomRoleBaseSource bases;

    @Value("${eip.security.internal-service-token:}")
    private String internalToken;

    public InternalRoleBasesController(RegistryCustomRoleBaseSource bases) {
        this.bases = bases;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> roleBases(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {

        if (!InternalTokens.matches(internalToken, token)) {
            return ResponseEntity.status(401).build();
        }
        if (tenantId == null || tenantId.isBlank()) return ResponseEntity.badRequest().build();
        UUID tid;
        try { tid = UUID.fromString(tenantId.trim()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }
        return ResponseEntity.ok(bases.read(tid));
    }
}
