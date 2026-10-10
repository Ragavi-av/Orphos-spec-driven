package io.orphos.eip.dataingestion.controller;

import io.orphos.eip.dataingestion.service.DataSourceService;
import io.orphos.eip.dataingestion.repository.MappingRepository;
import io.orphos.eip.dataingestion.component.MappingValidator;
import io.orphos.eip.dataingestion.vo.MappingDtos.ListResponse;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import io.orphos.eip.dataingestion.vo.MappingDtos.SaveMappingRequest;
import io.orphos.eip.dataingestion.vo.MappingDtos.ValidationReport;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Data-mapping configuration API (requirement 3). Mappings are validated
 * against the source's discovered metadata on save — structural problems
 * (unknown columns/transforms, unmapped key) reject with 422, questionable
 * type conversions are stored as warnings on the mapping (req 3.5). The
 * {@code /validate} endpoint runs the same checks without persisting, which
 * is what the mapping UI calls as the user edits.
 */
@RestController
@RequestMapping("/api/data-ingestion/admin/mappings")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class MappingController {

    private final MappingRepository repo;
    private final MappingValidator validator;
    private final DataSourceService dataSources;

    public MappingController(MappingRepository repo, MappingValidator validator,
                             DataSourceService dataSources) {
        this.repo = repo;
        this.validator = validator;
        this.dataSources = dataSources;
    }

    @GetMapping
    public ListResponse list(@RequestParam(required = false) String dataSourceId) {
        return new ListResponse(repo.list(dataSourceId).stream().map(repo::toView).toList());
    }

    @GetMapping("/{id}")
    public MappingView get(@PathVariable String id) {
        return repo.toView(require(id));
    }

    /** Dry-run validation — same checks as save, nothing persisted. */
    @PostMapping("/validate")
    public ValidationReport validate(@Valid @RequestBody SaveMappingRequest req) {
        dataSources.require(req.dataSourceId());
        return validator.validate(req);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MappingView create(@Valid @RequestBody SaveMappingRequest req) {
        dataSources.require(req.dataSourceId());
        if (repo.findByName(req.name()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A mapping named '" + req.name() + "' already exists");
        }
        ValidationReport report = validator.validate(req);
        if (!report.valid()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Mapping validation failed: " + String.join("; ", report.errors()));
        }
        String id = repo.insert(req, report.warnings());
        return repo.toView(repo.findById(id));
    }

    @PutMapping("/{id}")
    public MappingView update(@PathVariable String id, @Valid @RequestBody SaveMappingRequest req) {
        require(id);
        dataSources.require(req.dataSourceId());
        Map<String, Object> byName = repo.findByName(req.name());
        if (byName != null && !id.equals(String.valueOf(byName.get("id")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A mapping named '" + req.name() + "' already exists");
        }
        ValidationReport report = validator.validate(req);
        if (!report.valid()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Mapping validation failed: " + String.join("; ", report.errors()));
        }
        repo.update(id, req, report.warnings());
        return repo.toView(repo.findById(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        require(id);
        repo.delete(id);
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> row = repo.findById(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown mapping: " + id);
        }
        return row;
    }
}
