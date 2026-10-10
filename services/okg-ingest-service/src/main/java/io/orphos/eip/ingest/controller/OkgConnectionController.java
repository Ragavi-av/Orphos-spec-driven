package io.orphos.eip.ingest.controller;

import io.orphos.eip.ingest.repository.OkgConnectionRepository;
import io.orphos.eip.ingest.vo.ConnectionDtos.ConnectionView;
import io.orphos.eip.ingest.vo.ConnectionDtos.SaveConnectionRequest;
import io.orphos.eip.ingest.component.SecretRefService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin API for configuring the tenant's external-source connections (Jira
 * today; the {@code source} discriminator leaves room for future connectors).
 * Gated to the same role set the rest of the platform uses for data/AI admin
 * surfaces (e.g. {@code AdminQueryHistoryController}).
 */
@RestController
@RequestMapping("/api/okg-ingest/admin/connections")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'data_ai_admin')")
public class OkgConnectionController {

    private final OkgConnectionRepository connections;
    private final SecretRefService secrets;

    public OkgConnectionController(OkgConnectionRepository connections, SecretRefService secrets) {
        this.connections = connections;
        this.secrets = secrets;
    }

    @GetMapping
    public List<ConnectionView> list() {
        return connections.listConnections().stream().map(this::toView).toList();
    }

    @GetMapping("/{source}")
    public ResponseEntity<ConnectionView> get(@PathVariable String source) {
        Map<String, Object> row = connections.findConnection(source.toUpperCase());
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(toView(row));
    }

    /**
     * Upsert a connection. {@code apiToken} is exchanged for a vault reference
     * immediately and never persisted or logged in raw form; omit it on update
     * to keep the existing stored credential.
     */
    @PutMapping("/{source}")
    public ConnectionView save(@PathVariable String source, @Valid @RequestBody SaveConnectionRequest req) {
        String normalizedSource = source.toUpperCase();
        String apiTokenRef = null;
        if (req.apiToken() != null && !req.apiToken().isBlank()) {
            apiTokenRef = secrets.store(normalizedSource.toLowerCase(), "api-token", req.apiToken());
        }
        connections.saveConnection(normalizedSource, req.baseUrl(), req.email(), apiTokenRef,
                req.projectKeys(), req.enabled());
        return toView(connections.findConnection(normalizedSource));
    }

    private ConnectionView toView(Map<String, Object> row) {
        String ref = String.valueOf(row.getOrDefault("apiTokenRef", ""));
        return new ConnectionView(
                String.valueOf(row.getOrDefault("source", "")),
                String.valueOf(row.getOrDefault("baseUrl", "")),
                String.valueOf(row.getOrDefault("email", "")),
                !ref.isBlank(),
                String.valueOf(row.getOrDefault("projectKeys", "")),
                Boolean.TRUE.equals(row.get("enabled")),
                String.valueOf(row.getOrDefault("lastSyncAt", "")),
                String.valueOf(row.getOrDefault("lastSyncStatus", "")),
                String.valueOf(row.getOrDefault("lastSyncError", "")));
    }
}
