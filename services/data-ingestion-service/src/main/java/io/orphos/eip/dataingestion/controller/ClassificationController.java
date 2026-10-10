package io.orphos.eip.dataingestion.controller;

import io.orphos.eip.dataingestion.component.AuditTrailInterceptor;
import io.orphos.eip.dataingestion.repository.DataClassificationRepository;
import io.orphos.eip.dataingestion.service.DataSourceService;
import io.orphos.eip.dataingestion.vo.PreviewDtos.ClassificationLevel;
import io.orphos.eip.dataingestion.vo.PreviewDtos.ClassificationRequest;
import io.orphos.eip.dataingestion.vo.PreviewDtos.ClassificationView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Data-classification tagging API (requirement 8.6). Classification drives the
 * preview endpoint's enforcement — a {@code RESTRICTED} source is previewed
 * fully masked — and is surfaced in the preview response and the audit trail.
 */
@RestController
@RequestMapping("/api/data-ingestion/admin/datasources/{id}/classification")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class ClassificationController {

    private final DataSourceService dataSources;
    private final DataClassificationRepository repo;

    public ClassificationController(DataSourceService dataSources, DataClassificationRepository repo) {
        this.dataSources = dataSources;
        this.repo = repo;
    }

    @PutMapping
    public ClassificationView set(@PathVariable String id,
                                  @RequestBody ClassificationRequest req,
                                  HttpServletRequest request) {
        dataSources.require(id); // 404 on unknown/foreign data source
        ClassificationLevel level = parseLevel(req.level());
        List<String> tags = req.tags() == null ? List.of()
                : req.tags().stream().map(String::trim).filter(t -> !t.isEmpty()).distinct().toList();
        repo.updateClassification(id, level.name(), tags);
        // Enrich the audit entry (req 8.4/8.6) — the level and tags are
        // admin-chosen labels, never source data or credentials.
        request.setAttribute(AuditTrailInterceptor.DETAIL_ATTRIBUTE,
                "classification=" + level + " tags=" + tags);
        return new ClassificationView(id, level.name(), tags);
    }

    private static ClassificationLevel parseLevel(String level) {
        try {
            return ClassificationLevel.valueOf(level == null ? "" : level.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown classification level '" + level
                    + "' — expected PUBLIC, INTERNAL, CONFIDENTIAL or RESTRICTED");
        }
    }
}
