package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.util.CurrentUser;
import io.orphos.eip.admin.vo.DashboardLayoutDtos.LayoutRequest;
import io.orphos.eip.admin.vo.DashboardLayoutDtos.LayoutView;
import io.orphos.eip.admin.service.DashboardLayoutService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-only endpoints for managing the tenant's default dashboard layout.
 *
 * <p>The tenant default is what fresh users see on first sign-in (and what
 * existing users see after they reset their personal override). Edited by
 * tenant admins from the AdminPage "Dashboard Defaults" tab.</p>
 */
@RestController
@RequestMapping("/api/admin/dashboard/layout")
@PreAuthorize("hasRole('admin')")
public class AdminDashboardLayoutController {

    private final DashboardLayoutService service;

    public AdminDashboardLayoutController(DashboardLayoutService service) {
        this.service = service;
    }

    @GetMapping("/default")
    public LayoutView getDefault() {
        return service.getTenantDefault();
    }

    @PutMapping("/default")
    public LayoutView saveDefault(@Valid @RequestBody LayoutRequest req) {
        return service.saveTenantDefault(req, CurrentUser.key());
    }

    @DeleteMapping("/default")
    public ResponseEntity<Void> deleteDefault() {
        service.deleteTenantDefault();
        return ResponseEntity.noContent().build();
    }
}
