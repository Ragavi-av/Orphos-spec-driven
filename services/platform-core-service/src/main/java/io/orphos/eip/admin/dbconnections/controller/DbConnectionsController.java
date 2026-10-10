package io.orphos.eip.admin.dbconnections.controller;

import io.orphos.eip.admin.dbconnections.dto.DbConnectionDtos.*;
import io.orphos.eip.admin.dbconnections.service.DbConnectionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Platform-admin-only management of external database connections and their
 * assignment to services.
 *
 * <p>RBAC is enforced twice: the URL rule in {@code SecurityConfig}
 * ({@code /api/admin/db-connections/**} → {@code platform_admin}) and the
 * class-level {@link PreAuthorize} here.</p>
 */
@RestController
@RequestMapping("/api/admin/db-connections")
@PreAuthorize("hasRole('platform_admin')")
public class DbConnectionsController {

    private final DbConnectionService service;

    public DbConnectionsController(DbConnectionService service) {
        this.service = service;
    }

    // ── connections ────────────────────────────────────────────────────────
    @GetMapping
    public ConnectionListResponse list() {
        return new ConnectionListResponse(service.list());
    }

    @PostMapping
    public ConnectionView create(@Valid @RequestBody SaveConnectionRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public ConnectionView update(@PathVariable String id, @Valid @RequestBody SaveConnectionRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── validation ───────────────────────────────────────────────────────────
    @PostMapping("/validate")
    public TestResult validateAdHoc(@Valid @RequestBody TestConnectionRequest req) {
        return service.validateAdHoc(req);
    }

    @PostMapping("/{id}/validate")
    public TestResult validate(@PathVariable String id) {
        return service.validate(id);
    }

    // ── assignments ───────────────────────────────────────────────────────────
    @GetMapping("/assignments")
    public AssignmentMatrixView assignments() {
        return service.matrix();
    }

    @PutMapping("/assignments")
    public ApplyResult saveAssignments(@Valid @RequestBody SaveAssignmentsRequest req) {
        return service.saveAssignments(req);
    }

    @PostMapping("/apply")
    public ApplyResult apply() {
        return service.apply();
    }
}
