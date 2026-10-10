package io.orphos.eip.admin.access.controller;

import io.orphos.eip.admin.access.service.AccessControlService;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service endpoint that returns a tenant's RBAC grants so OTHER
 * services (via {@code HttpAccessGrantSource}) can enforce the access map.
 *
 * <p>Guarded in-controller by the shared internal token ({@code X-Internal-Token},
 * same secret as the {@code /api/internal/**} {@code X-Service-Token} filter that
 * also gates this path). The target tenant is named by {@code X-Tenant-Id} and
 * bound explicitly for the lookup — this endpoint is not user-facing.</p>
 */
@RestController
@RequestMapping("/api/internal/access-grants")
public class InternalAccessGrantsController {

    private final AccessControlService service;

    @Value("${eip.security.internal-service-token:}")
    private String internalToken;

    public InternalAccessGrantsController(AccessControlService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> grants(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {

        if (internalToken == null || internalToken.isBlank() || !constantTimeEquals(internalToken, token)) {
            return ResponseEntity.status(401).build();
        }
        if (tenantId == null || tenantId.isBlank()) return ResponseEntity.badRequest().build();
        UUID tid;
        try { tid = UUID.fromString(tenantId.trim()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }

        List<Map<String, Object>> grants = TenantContextHolder.callWith(
                new TenantContext(tid, null, false), service::listGrants);
        return ResponseEntity.ok(grants);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
