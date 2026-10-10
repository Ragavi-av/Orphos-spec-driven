package io.orphos.eip.admin.tenant.controller;

import io.orphos.eip.admin.migration.MigrationRunner;
import io.orphos.eip.admin.tenant.dto.TenantDtos;
import io.orphos.eip.admin.tenant.dto.TenantDtos.BulkResultItem;
import io.orphos.eip.admin.tenant.dto.TenantDtos.BulkResultView;
import io.orphos.eip.admin.tenant.dto.TenantDtos.BulkStatusRequest;
import io.orphos.eip.admin.tenant.dto.TenantDtos.CreateTenantRequest;
import io.orphos.eip.admin.tenant.dto.TenantDtos.TenantMetricsView;
import io.orphos.eip.admin.tenant.dto.TenantDtos.TenantView;
import io.orphos.eip.admin.tenant.dto.TenantDtos.UpdateTenantRequest;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.model.TenantStatus;
import io.orphos.eip.admin.tenant.service.TenantMetricsService;
import io.orphos.eip.admin.tenant.service.TenantService;
import jakarta.validation.Valid;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tenant CRUD reserved for platform superadmins. Tenant-scoped admins (with
 * {@code ROLE_admin} inside their own realm) cannot reach these endpoints —
 * URL-pattern matchers in {@code SecurityConfig} require
 * {@code ROLE_platform_admin}, and {@code @PreAuthorize} below provides
 * defense-in-depth.
 */
@RestController
@RequestMapping("/api/admin/tenants")
@PreAuthorize("hasRole('platform_admin')")
public class TenantManagementController {

    private final TenantService tenants;
    private final TenantMetricsService metrics;
    private final MigrationRunner migrationRunner;

    public TenantManagementController(TenantService tenants, TenantMetricsService metrics,
                                      MigrationRunner migrationRunner) {
        this.tenants = tenants;
        this.metrics = metrics;
        this.migrationRunner = migrationRunner;
    }

    @PostMapping
    public ResponseEntity<TenantView> create(@Valid @RequestBody CreateTenantRequest req) {
        TenantView view = TenantView.from(tenants.create(req));
        return ResponseEntity.created(URI.create("/api/admin/tenants/" + view.id())).body(view);
    }

    @GetMapping
    public List<TenantView> list() {
        return tenants.list().stream().map(TenantView::from).toList();
    }

    @GetMapping("/{id}")
    public TenantView get(@PathVariable UUID id) {
        return TenantView.from(tenants.get(id));
    }

    /**
     * Operational metrics for a single tenant — user count, per-doctype row
     * counts, last-activity timestamp, and external-resource reachability.
     * Drives the tenant-detail drawer in the admin dashboard.
     */
    @GetMapping("/{id}/metrics")
    public TenantMetricsView metrics(@PathVariable UUID id) {
        return metrics.compute(id);
    }

    /**
     * Migration history for a tenant — every applied migration in the tenant's
     * database, plus a {@code PENDING} entry for each registered migration not
     * yet applied. Powers the "Migrations" section of the tenant-detail drawer.
     */
    @GetMapping("/{id}/migrations")
    public java.util.List<MigrationRunner.MigrationStatus> migrationsList(@PathVariable UUID id) {
        Tenant t = tenants.get(id);
        return migrationRunner.statusOf(t.arcadeConnectionId(), t.arcadeDatabase());
    }

    /**
     * Run every pending migration against the tenant's database. Idempotent —
     * a tenant whose schema is already up-to-date sees a no-op response with
     * {@code attempted = 0}.
     */
    @PostMapping("/{id}/migrate")
    public MigrationRunner.MigrationRunResult migrate(@PathVariable UUID id) {
        Tenant t = tenants.get(id);
        return migrationRunner.runPending(t.arcadeConnectionId(), t.arcadeDatabase());
    }

    /**
     * Bulk status update — used by the admin dashboard's multi-select action
     * bar to suspend / reactivate several tenants in a single round trip.
     * Each id is processed independently; per-id failures are reported in
     * the response rather than aborting the whole batch.
     */
    @PostMapping("/bulk-status")
    public BulkResultView bulkStatus(@Valid @RequestBody BulkStatusRequest req) {
        List<BulkResultItem> items = new ArrayList<>(req.ids().size());
        int ok = 0;
        int fail = 0;
        TenantStatus target = req.status();
        for (UUID id : req.ids()) {
            try {
                if (target == TenantStatus.DELETED) {
                    tenants.softDelete(id);
                } else {
                    tenants.update(id, new UpdateTenantRequest(null, null, target));
                }
                items.add(new BulkResultItem(id, true, null));
                ok++;
            } catch (Exception e) {
                items.add(new BulkResultItem(id, false, e.getMessage()));
                fail++;
            }
        }
        return new BulkResultView(req.ids().size(), ok, fail, items);
    }

    @PatchMapping("/{id}")
    public TenantView update(@PathVariable UUID id, @Valid @RequestBody UpdateTenantRequest req) {
        return TenantView.from(tenants.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        tenants.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard-delete: irreversibly releases all external resources owned by the
     * tenant (Keycloak realm + DEDICATED ArcadeDB database + DEDICATED Fuseki
     * dataset) and drops the Tenant row. There is no undo.
     *
     * <p>For SHARED tenants, set {@code ?cascade=true} to also wipe row-level
     * data ({@code DELETE FROM doctype WHERE tenantId = :id}) and the named
     * graph in Fuseki ({@code DROP GRAPH <urn:eip:tenant:{id}>}). Without
     * {@code cascade}, the shared-data rows are orphaned — auditable but
     * recoverable.</p>
     *
     * <p>The demo tenant is hard-coded to reject this operation; soft-delete
     * is the only way to take the demo offline.</p>
     */
    @DeleteMapping("/{id}/destroy")
    public ResponseEntity<Void> destroy(
            @PathVariable UUID id,
            @RequestParam(name = "cascade", defaultValue = "false") boolean cascadeSharedData) {
        tenants.hardDelete(id, cascadeSharedData);
        return ResponseEntity.noContent().build();
    }
}
