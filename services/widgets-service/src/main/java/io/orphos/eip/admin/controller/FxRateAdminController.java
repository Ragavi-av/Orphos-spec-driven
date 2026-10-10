package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.service.FxRateAdminService;
import io.orphos.eip.admin.service.FxRateAdminService.RateRow;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The tenant's exchange-rate table.
 *
 * <p>Rates decide what a reader sees when they ask for a board in a currency the
 * data is not reported in, so maintaining them is an admin act, not a user
 * preference — hence {@code hasRole('admin')} on the whole controller. Reading is
 * admin-only too: the table is small, but a rate and its source together say
 * something about how the tenant books its money.</p>
 *
 * <p>There is deliberately no update verb. A rate is superseded by adding a later
 * one, which is what keeps a closed period's converted figures stable — see
 * {@link FxRateAdminService}.</p>
 */
@RestController
@RequestMapping("/api/admin/fx-rates")
@PreAuthorize("hasRole('admin')")
public class FxRateAdminController {

    private final FxRateAdminService service;

    public FxRateAdminController(FxRateAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<RateRow> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody RateRow req) {
        try {
            return ResponseEntity.ok(service.create(req));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            // A rejected rate is almost always a typo in the form — a 400 with the
            // reason lets the admin tab show it beside the field rather than a
            // generic failure toast.
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
