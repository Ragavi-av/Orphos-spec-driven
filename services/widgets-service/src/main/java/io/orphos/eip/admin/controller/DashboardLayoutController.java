package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.util.CurrentUser;
import io.orphos.eip.admin.vo.DashboardLayoutDtos.CatalogItem;
import io.orphos.eip.admin.vo.DashboardLayoutDtos.LayoutRequest;
import io.orphos.eip.admin.vo.DashboardLayoutDtos.LayoutView;
import io.orphos.eip.admin.service.DashboardLayoutService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Per-user dashboard layout endpoints.
 *
 * <p>All endpoints are tenant-scoped via {@code TenantContextHolder} (populated
 * by the upstream {@code TenantResolutionFilter}). The user id is taken from
 * the JWT {@code sub} via {@link CurrentUser#key()}.</p>
 */
@RestController
@RequestMapping("/api/dashboard/layout")
public class DashboardLayoutController {

    private final DashboardLayoutService service;

    public DashboardLayoutController(DashboardLayoutService service) {
        this.service = service;
    }

    /** Catalog of widgets — used by the picker UI to render the canonical list. */
    @GetMapping("/catalog")
    public List<CatalogItem> catalog() {
        return service.catalog();
    }

    /** Resolve layout for the current user (user override → tenant default → system). */
    @GetMapping("/me")
    public LayoutView mine() {
        return service.resolveForUser(CurrentUser.key());
    }

    /** Upsert the current user's personal layout. */
    @PutMapping("/me")
    public LayoutView saveMine(@Valid @RequestBody LayoutRequest req) {
        return service.saveUserLayout(CurrentUser.key(), req);
    }

    /** Drop the current user's override and fall back to the tenant default. */
    @DeleteMapping("/me")
    public ResponseEntity<LayoutView> resetMine() {
        return ResponseEntity.ok(service.resetUserLayout(CurrentUser.key()));
    }
}
