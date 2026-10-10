package io.orphos.eip.dataingestion.controller;

import io.orphos.eip.dataingestion.service.MetadataDiscoveryService;
import io.orphos.eip.dataingestion.vo.MetadataDtos.SchemaSnapshot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Source metadata discovery API (requirement 2). Snapshots come from a 24 h
 * cache; {@code ?refresh=true} forces a full re-discovery, and the per-table
 * endpoint refreshes one table incrementally on large sources.
 */
@RestController
@RequestMapping("/api/data-ingestion/admin/datasources/{id}/metadata")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class MetadataController {

    private final MetadataDiscoveryService metadata;

    public MetadataController(MetadataDiscoveryService metadata) {
        this.metadata = metadata;
    }

    /** Full schema snapshot — tables, columns, keys, indexes, row counts. */
    @GetMapping
    public SchemaSnapshot snapshot(@PathVariable String id,
                                   @RequestParam(defaultValue = "false") boolean refresh) {
        return metadata.getSnapshot(id, refresh);
    }

    /** Incremental refresh of one table's metadata (requirement 2.7). */
    @PostMapping("/tables/{table}/refresh")
    public SchemaSnapshot refreshTable(@PathVariable String id, @PathVariable String table) {
        return metadata.refreshTable(id, table);
    }
}
