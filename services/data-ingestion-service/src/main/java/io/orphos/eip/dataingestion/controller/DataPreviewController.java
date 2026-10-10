package io.orphos.eip.dataingestion.controller;

import io.orphos.eip.dataingestion.service.DataPreviewService;
import io.orphos.eip.dataingestion.vo.PreviewDtos.PreviewResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Masked data preview API (requirement 8.5) — a small, safe peek at source
 * rows for mapping verification. Values in sensitive columns are masked
 * server-side; the unmasked data never leaves this service. A source
 * classified {@code RESTRICTED} is fully masked (req 8.6).
 */
@RestController
@RequestMapping("/api/data-ingestion/admin/datasources/{id}/preview")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class DataPreviewController {

    private final DataPreviewService preview;

    public DataPreviewController(DataPreviewService preview) {
        this.preview = preview;
    }

    @GetMapping
    public PreviewResponse preview(@PathVariable String id,
                                   @RequestParam(required = false) String schema,
                                   @RequestParam String table,
                                   @RequestParam(defaultValue = "10") int limit) {
        return preview.preview(id, schema, table, limit);
    }
}
