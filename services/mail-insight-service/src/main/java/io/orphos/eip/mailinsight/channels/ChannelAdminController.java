package io.orphos.eip.mailinsight.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.schedule.ScheduleSpec;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.security.CurrentUser;
import io.orphos.eip.secrets.SecretsClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin configuration surface for communication-channel connections (Slack /
 * Microsoft Teams / …): CRUD, monitored accounts (channels/conversations),
 * connectivity test, manual scan, and per-connection scheduling. Mirrors
 * {@code MailMonitorAdminController}.
 *
 * <p>Under {@code /api/mail-insight/admin/**}, so the existing SecurityConfig
 * role gate (admin / platform_admin / C-suite) applies. Bot tokens / client
 * secrets are written to Infisical; ArcadeDB stores only the reference and this
 * API never returns them.</p>
 */
@RestController
@RequestMapping("/api/mail-insight/admin/channels")
public class ChannelAdminController {

    private final ChannelConfigRepository configRepo;
    private final ChannelScanService scanService;
    private final CommChannelConnectorRegistry registry;
    private final SecretsClient secrets;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChannelAdminController(ChannelConfigRepository configRepo,
                                  ChannelScanService scanService,
                                  CommChannelConnectorRegistry registry,
                                  SecretsClient secrets) {
        this.configRepo = configRepo;
        this.scanService = scanService;
        this.registry = registry;
        this.secrets = secrets;
    }

    // ── Available kinds (drives the UI type picker + "coming soon" flags) ─────

    @GetMapping("/kinds")
    public Map<String, Object> kinds() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CommChannelKind k : CommChannelKind.values()) {
            Map<String, Object> m = new HashMap<>();
            m.put("kind", k.name());
            m.put("supported", registry.supports(k));
            rows.add(m);
        }
        return Map.of("result", rows, "count", rows.size());
    }

    // ── Connections ───────────────────────────────────────────────────────────

    @GetMapping("/connections")
    public Map<String, Object> listConnections() {
        List<Map<String, Object>> rows = configRepo.listConnections().stream()
                .map(this::curateConnection).toList();
        return Map.of("result", rows, "count", rows.size());
    }

    @GetMapping("/connections/{id}")
    public ResponseEntity<?> getConnection(@PathVariable String id) {
        Map<String, Object> raw = configRepo.getConnectionById(id);
        if (raw == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = curateConnection(raw);
        out.put("accounts", configRepo.listAccountsByConnection(id).stream()
                .map(ChannelAdminController::curateAccount).toList());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/connections")
    public ResponseEntity<?> createConnection(@RequestBody Map<String, Object> body) {
        return upsertConnection(null, body);
    }

    @PutMapping("/connections/{id}")
    public ResponseEntity<?> updateConnection(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (configRepo.getConnectionById(id) == null) return ResponseEntity.notFound().build();
        return upsertConnection(id, body);
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<?> deleteConnection(@PathVariable String id) {
        if (configRepo.getConnectionById(id) == null) return ResponseEntity.notFound().build();
        configRepo.deleteConnection(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> upsertConnection(String id, Map<String, Object> body) {
        String name = str(body.get("name"));
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "name is required"));

        CommChannelKind kind = CommChannelKind.parse(str(body.get("kind")));
        if (kind == null) return ResponseEntity.badRequest().body(Map.of("error", "unknown or missing kind"));
        if (!registry.supports(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", kind + " connector is not implemented yet"));
        }

        int windowDays = body.get("defaultScanWindowDays") instanceof Number n ? n.intValue() : 7;
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String configJson = toJson(asMap(body.get("config")));

        // Custom scan schedule (shared ScheduleSpec). Absent → continuous scanning.
        String scheduleJson = "";
        Object rawSchedule = body.get("schedule");
        if (rawSchedule instanceof Map<?, ?> scheduleMap && !scheduleMap.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                ScheduleSpec spec = ScheduleSpec.fromMap((Map<String, Object>) scheduleMap);
                scheduleJson = mapper.writeValueAsString(spec.toMap());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid schedule: " + e.getMessage()));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid schedule"));
            }
        }

        // Credential payload → Infisical, keyed by connection id so multiple
        // connections don't collide. Only written when a new value is supplied;
        // saveConnection preserves the existing reference otherwise.
        String credentialRef = null;
        Map<String, Object> credential = asMap(body.get("credential"));
        if (!credential.isEmpty()) {
            if (!secrets.isConfigured()) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "Secret store (Infisical) is not configured; cannot store channel credentials."));
            }
            String tenantId = TenantContextHolder.requireTenantId().toString();
            String path = "eip/comm-channel/" + tenantId + "/" + (id == null || id.isBlank() ? "new" : id);
            secrets.writeField(path, "credential", toJson(credential));
            credentialRef = path + "#credential";
        }

        try {
            String connectionId = configRepo.saveConnection(id, name, kind.name(), configJson,
                    credentialRef, windowDays, enabled, scheduleJson);
            return getConnection(connectionId);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** Validate connectivity before saving (no persistence) — mirrors doc-ingest's sources/test. */
    @PostMapping("/connections/test")
    public ResponseEntity<?> test(@RequestBody Map<String, Object> body) {
        CommChannelKind kind = CommChannelKind.parse(str(body.get("kind")));
        if (kind == null) return ResponseEntity.badRequest().body(Map.of("error", "unknown or missing kind"));
        if (!registry.supports(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", kind + " connector is not implemented yet"));
        }
        CommChannelConnector.Descriptor desc = new CommChannelConnector.Descriptor(
                kind, asMap(body.get("config")), toJson(asMap(body.get("credential"))));
        CommChannelConnector.ProbeResult probe = registry.require(kind).test(desc);
        return ResponseEntity.ok(Map.of(
                "ok", probe.ok(), "message", probe.message(), "sampleCount", probe.sampleCount()));
    }

    @PostMapping("/connections/{id}/scan")
    public ResponseEntity<?> scanConnection(@PathVariable String id) {
        Map<String, Object> config = configRepo.getConnectionById(id);
        if (config == null) return ResponseEntity.notFound().build();
        if (!Boolean.TRUE.equals(config.get("enabled"))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "This connection is disabled — enable it and save before running a scan."));
        }
        TenantContext ctx = TenantContextHolder.current()
                .orElseThrow(() -> new IllegalStateException("No tenant context bound to this request"));
        scanService.scanConnectionAsync(ctx, id);
        return ResponseEntity.accepted().body(Map.of("started", true,
                "message", "Scan of \"" + config.getOrDefault("name", id)
                        + "\" started in the background — check the run history for progress."));
    }

    // ── Accounts (channels/conversations within a connection) ─────────────────

    @GetMapping("/accounts")
    public Map<String, Object> listAccounts(@RequestParam(required = false) String connectionId) {
        List<Map<String, Object>> raw = (connectionId != null && !connectionId.isBlank())
                ? configRepo.listAccountsByConnection(connectionId)
                : configRepo.listAccounts();
        List<Map<String, Object>> rows = raw.stream().map(ChannelAdminController::curateAccount).toList();
        return Map.of("result", rows, "count", rows.size());
    }

    @PostMapping("/accounts")
    public ResponseEntity<?> addAccount(@RequestBody Map<String, Object> body) {
        String connectionId = str(body.get("channelConnectionId"));
        if (connectionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "channelConnectionId is required"));
        }
        if (configRepo.getConnectionById(connectionId) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "channelConnectionId does not match a known connection"));
        }
        String externalId = str(body.get("externalId"));
        if (externalId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "externalId (channel/conversation id — for Teams use \"teamId/channelId\") is required"));
        }
        Integer window = body.get("scanWindowDays") instanceof Number n ? n.intValue() : null;
        String ownerSub = str(body.get("ownerSub"));
        String ownerEmail = str(body.get("ownerEmail"));
        String createdBy = CurrentUser.displayName().orElse("");
        String id = configRepo.addAccount(connectionId, externalId, str(body.get("displayName")),
                window, ownerSub, ownerEmail, createdBy);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PatchMapping("/accounts/{id}")
    public ResponseEntity<?> patchAccount(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (configRepo.findAccount(id) == null) return ResponseEntity.notFound().build();
        if (body.containsKey("enabled")) {
            configRepo.setAccountEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
        }
        return ResponseEntity.ok(curateAccount(configRepo.findAccount(id)));
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<?> deleteAccount(@PathVariable String id) {
        configRepo.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/accounts/{id}/scan")
    public ResponseEntity<?> scanAccount(@PathVariable String id) {
        Map<String, Object> account = configRepo.findAccount(id);
        if (account == null) return ResponseEntity.notFound().build();
        TenantContext ctx = TenantContextHolder.current()
                .orElseThrow(() -> new IllegalStateException("No tenant context bound to this request"));
        scanService.scanAccountAsync(ctx, id);
        return ResponseEntity.accepted().body(Map.of("started", true,
                "message", "Scan of " + account.getOrDefault("displayName", id)
                        + " started in the background — check the run history for progress."));
    }

    // ── curation / redaction ────────────────────────────────────────────────

    private Map<String, Object> curateConnection(Map<String, Object> raw) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", raw.get("id"));
        out.put("name", raw.get("name"));
        out.put("kind", raw.get("kind"));
        out.put("config", parseJson(raw.get("configJson")));
        out.put("defaultScanWindowDays", raw.get("defaultScanWindowDays"));
        out.put("enabled", raw.get("enabled"));
        out.put("schedule", parseJson(raw.get("scheduleJson")));
        out.put("lastScheduledScanAt", raw.get("lastScheduledScanAt"));
        out.put("createdAt", raw.get("createdAt"));
        String ref = raw.get("credentialRef") == null ? "" : raw.get("credentialRef").toString();
        out.put("credentialConfigured", secretPresent(ref));
        CommChannelKind kind = CommChannelKind.parse(String.valueOf(raw.get("kind")));
        out.put("supported", kind != null && registry.supports(kind));
        out.put("accountCount", configRepo.listAccountsByConnection(String.valueOf(raw.get("id"))).size());
        return out;
    }

    private static Map<String, Object> curateAccount(Map<String, Object> r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.get("id"));
        m.put("channelConnectionId", r.get("channelConnectionId"));
        m.put("externalId", r.get("externalId"));
        m.put("displayName", r.get("displayName"));
        m.put("scanWindowDays", r.get("scanWindowDays"));
        m.put("ownerSub", r.get("ownerSub"));
        m.put("ownerEmail", r.get("ownerEmail"));
        m.put("enabled", r.get("enabled"));
        m.put("lastScanAt", r.get("lastScanAt"));
        m.put("lastScanStatus", r.get("lastScanStatus"));
        m.put("lastError", r.get("lastError"));
        return m;
    }

    private boolean secretPresent(String ref) {
        if (ref == null || ref.isBlank() || !ref.contains("#")) return false;
        if (!secrets.isConfigured()) return false;
        String[] parts = ref.split("#", 2);
        return secrets.readField(parts[0], parts[1]).isPresent();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    private String toJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m == null ? Map.of() : m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseJson(Object raw) {
        String json = raw == null ? "" : raw.toString();
        if (json.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(json, Map.class);
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }
}
