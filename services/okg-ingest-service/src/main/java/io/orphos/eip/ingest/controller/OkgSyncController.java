package io.orphos.eip.ingest.controller;

import io.orphos.eip.ingest.repository.OkgConnectionRepository;
import io.orphos.eip.ingest.vo.ConnectionDtos.SyncTriggerResponse;
import io.orphos.eip.ingest.service.JiraIngestionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-triggered on-demand ingestion — useful for testing a newly-configured connection. */
@RestController
@RequestMapping("/api/okg-ingest/admin/sync")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin') or hasAuthority('ROLE_service_internal')")
public class OkgSyncController {

    private static final Logger log = LogManager.getLogger(OkgSyncController.class);

    private final JiraIngestionService ingestion;
    private final OkgConnectionRepository connections;

    public OkgSyncController(JiraIngestionService ingestion, OkgConnectionRepository connections) {
        this.ingestion = ingestion;
        this.connections = connections;
    }

    @PostMapping
    public ResponseEntity<SyncTriggerResponse> triggerSync() {
        try {
            JiraIngestionService.SyncResult result = ingestion.syncCurrentTenant();
            return ResponseEntity.ok(new SyncTriggerResponse(
                    "SUCCESS", result.projectsSynced(), result.issuesSynced(), result.linksSynced(), null));
        } catch (Exception e) {
            log.warn("Manual Jira sync failed: {}", e.getMessage(), e);
            connections.recordSyncResult(JiraIngestionService.SOURCE, false, e.getMessage());
            return ResponseEntity.status(502).body(
                    new SyncTriggerResponse("FAILED", 0, 0, 0, e.getMessage()));
        }
    }
}
