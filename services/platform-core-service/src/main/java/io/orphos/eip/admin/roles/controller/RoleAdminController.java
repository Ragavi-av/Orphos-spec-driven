package io.orphos.eip.admin.roles.controller;

import io.orphos.eip.admin.roles.dto.RoleDtos.CopyRoleRequest;
import io.orphos.eip.admin.roles.dto.RoleDtos.CreateRoleRequest;
import io.orphos.eip.admin.roles.dto.RoleDtos.RoleView;
import io.orphos.eip.admin.roles.dto.RoleDtos.UpdateRoleRequest;
import io.orphos.eip.admin.roles.service.RoleAdminService;
import io.orphos.eip.admin.roles.service.RoleAdminService.RoleInUseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Manage per-tenant roles (built-in + custom) and their aliases from the
 * Authentication &amp; Authorization → User Management tab. Reads are open to any
 * admin-capable role (so the management table and dropdowns load); mutations are
 * restricted to the Authentication &amp; Authorization owners.
 */
@RestController
@RequestMapping("/api/admin/roles")
public class RoleAdminController {

    private static final Logger log = LogManager.getLogger(RoleAdminController.class);

    private final RoleAdminService service;

    public RoleAdminController(RoleAdminService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('admin','platform_admin','secops_admin','ingestion_admin','data_ai_admin')")
    public List<RoleView> list() {
        return service.listRoles();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin','platform_admin','secops_admin')")
    public ResponseEntity<?> create(@RequestBody CreateRoleRequest req) {
        return guard(() -> ResponseEntity.ok(service.create(req)));
    }

    @PostMapping("/{key}/copy")
    @PreAuthorize("hasAnyRole('admin','platform_admin','secops_admin')")
    public ResponseEntity<?> copy(@PathVariable String key, @RequestBody CopyRoleRequest req) {
        return guard(() -> ResponseEntity.ok(service.copy(key, req.key(), req.alias(), req.color())));
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasAnyRole('admin','platform_admin','secops_admin')")
    public ResponseEntity<?> update(@PathVariable String key, @RequestBody UpdateRoleRequest req) {
        return guard(() -> ResponseEntity.ok(service.update(key, req)));
    }

    @DeleteMapping("/{key}")
    @PreAuthorize("hasAnyRole('admin','platform_admin','secops_admin')")
    public ResponseEntity<?> delete(@PathVariable String key) {
        try {
            service.delete(key);
            return ResponseEntity.ok(Map.of("deleted", true, "key", key));
        } catch (RoleInUseException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Role delete failed for '{}': {}", key, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Could not delete the role: " + e.getMessage()));
        }
    }

    /** Wipes every unused custom role tenant-wide, so it is reserved to full admins. */
    @PostMapping("/reset")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<?> reset() {
        return guard(() -> ResponseEntity.ok(service.reset()));
    }

    /** Maps validation errors to 400 and unexpected failures to 502, uniformly. */
    private ResponseEntity<?> guard(java.util.function.Supplier<ResponseEntity<?>> op) {
        try {
            return op.get();
        } catch (RoleInUseException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Role operation failed: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Role operation failed: " + e.getMessage()));
        }
    }
}
