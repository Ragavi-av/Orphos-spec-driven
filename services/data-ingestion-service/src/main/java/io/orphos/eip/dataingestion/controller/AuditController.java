package io.orphos.eip.dataingestion.controller;

import io.orphos.eip.dataingestion.repository.AuditLogRepository;
import io.orphos.eip.dataingestion.vo.AuditDtos.AuditEntry;
import io.orphos.eip.dataingestion.vo.AuditDtos.ListResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read API for the user-action audit trail (requirement 8.4). Gated to the
 * same role set as the rest of the data-ingestion admin surface. Entries are
 * tenant-scoped and returned newest first.
 */
@RestController
@RequestMapping("/api/data-ingestion/admin/audit")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class AuditController {

    private final AuditLogRepository repo;

    public AuditController(AuditLogRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ListResponse list(@RequestParam(defaultValue = "100") int limit) {
        return new ListResponse(repo.listRecent(limit).stream().map(AuditController::toEntry).toList());
    }

    private static AuditEntry toEntry(Map<String, Object> row) {
        return new AuditEntry(
                str(row, "id"),
                str(row, "actor"),
                str(row, "action"),
                str(row, "detail"),
                str(row, "at"));
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }
}
