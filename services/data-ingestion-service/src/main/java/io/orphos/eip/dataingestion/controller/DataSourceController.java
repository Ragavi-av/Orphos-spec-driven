package io.orphos.eip.dataingestion.controller;

import io.orphos.eip.dataingestion.dao.ConnectionTester;
import io.orphos.eip.dataingestion.repository.DataSourceRepository;
import io.orphos.eip.dataingestion.service.DataSourceService;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.DataSourceView;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.ListResponse;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SaveDataSourceRequest;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.TestConnectionRequest;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.TestResult;
import io.orphos.eip.dataingestion.service.OpenApiSpecService;
import io.orphos.eip.dataingestion.util.ZohoPreset;
import io.orphos.eip.dataingestion.vo.ErpConfig;
import io.orphos.eip.dataingestion.vo.ErpDtos.OpenApiPreview;
import io.orphos.eip.dataingestion.vo.ErpDtos.ParseOpenApiRequest;
import io.orphos.eip.dataingestion.service.MetadataDiscoveryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin API for the tenant's enterprise data-source connections (requirement 1).
 * Gated to the same role set as the other data/AI admin surfaces
 * ({@code OkgConnectionController}, {@code AdminQueryHistoryController});
 * tenant admins and platform admins both qualify (requirement 10.1).
 *
 * <p>Raw credentials appear only in write requests; they are exchanged for
 * Infisical refs immediately and never persisted, logged, or echoed back.</p>
 */
@RestController
@RequestMapping("/api/data-ingestion/admin/datasources")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class DataSourceController {

    private final DataSourceService service;
    private final DataSourceRepository repo;
    private final ConnectionTester tester;
    private final MetadataDiscoveryService metadata;
    private final OpenApiSpecService openApi;

    public DataSourceController(DataSourceService service, DataSourceRepository repo,
                                ConnectionTester tester, MetadataDiscoveryService metadata,
                                OpenApiSpecService openApi) {
        this.service = service;
        this.repo = repo;
        this.tester = tester;
        this.metadata = metadata;
        this.openApi = openApi;
    }

    @GetMapping
    public ListResponse list() {
        return new ListResponse(repo.list().stream().map(DataSourceService::toView).toList());
    }

    @GetMapping("/{id}")
    public DataSourceView get(@PathVariable String id) {
        return DataSourceService.toView(service.require(id));
    }

    /** Create — validates connectivity before storing (422 when unreachable). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DataSourceView create(@Valid @RequestBody SaveDataSourceRequest req) {
        return service.create(req);
    }

    /** Update — blank {@code secret} keeps the stored credential. */
    @PutMapping("/{id}")
    public DataSourceView update(@PathVariable String id, @Valid @RequestBody SaveDataSourceRequest req) {
        DataSourceView view = service.update(id, req);
        metadata.invalidate(id); // definition changed — cached schema may be stale
        return view;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
        metadata.invalidate(id);
    }

    /** Re-test a stored connection; outcome is recorded on the data source row. */
    @PostMapping("/{id}/test")
    public TestResult testStored(@PathVariable String id) {
        return service.testStored(id);
    }

    /** Ad-hoc pre-save test — nothing is persisted. */
    @PostMapping("/test")
    public TestResult testAdHoc(@Valid @RequestBody TestConnectionRequest req) {
        return tester.test(
                DataSourceService.parseKind(req.kind()),
                req.jdbcUrl(), req.driverClass(), req.username(),
                req.baseUrl(), DataSourceService.parseAuth(req.authType()),
                req.authHeaderName(), req.testPath(), req.secret(), req.configJson());
    }

    /**
     * Parse an uploaded OpenAPI/Swagger document into selectable objects
     * (endpoints) + fields, for the "Enterprise System" source dialog. Nothing is
     * persisted — the UI stores the confirmed subset into {@code configJson} on
     * save. When {@code vendor=ZOHO} the Zoho preset (pagination, standard modules)
     * is merged so an admin without a spec still gets sensible objects.
     */
    @PostMapping("/parse-openapi")
    public OpenApiPreview parseOpenApi(@RequestBody ParseOpenApiRequest req) {
        boolean zoho = req.vendor() != null && req.vendor().equalsIgnoreCase("ZOHO");
        boolean noSpec = req.specJson() == null || req.specJson().isBlank();
        if (zoho && noSpec) {
            // No spec supplied for Zoho — hand back the preset module objects to confirm.
            var cfg = ZohoPreset.applyDefaults(io.orphos.eip.dataingestion.vo.ErpConfig.parse(null));
            return new OpenApiPreview("", cfg.objectsOrEmpty(),
                    java.util.List.of("Using the Zoho preset modules; fields are discovered by sampling each endpoint."));
        }
        return openApi.preview(req.specJson());
    }
}
