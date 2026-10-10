package io.orphos.eip.mailinsight.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.schedule.ScheduleSpec;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.pipeline.MailScanService;
import io.orphos.eip.mailinsight.repo.MailboxConfigRepository;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin/CXO configuration surface: mail connections (a tenant may have several —
 * Microsoft Graph and/or IMAP), the monitored mailboxes assigned to each, manual
 * scan trigger, and scan-run history.
 *
 * The Graph client secret is written to Infisical; ArcadeDB stores only the
 * reference path. Secrets are never returned to the client or logged.
 */
@RestController
@RequestMapping("/api/mail-insight/admin")
public class MailMonitorAdminController {

    private final MailboxConfigRepository configRepo;
    private final MailScanService scanService;
    private final SecretsClient secrets;
    private final ObjectMapper mapper = new ObjectMapper();

    public MailMonitorAdminController(MailboxConfigRepository configRepo,
                                      MailScanService scanService,
                                      SecretsClient secrets) {
        this.configRepo = configRepo;
        this.scanService = scanService;
        this.secrets = secrets;
    }

    // ── Connections ───────────────────────────────────────────────────────────

    @GetMapping("/connections")
    public Map<String, Object> listConnections() {
        List<Map<String, Object>> rows = configRepo.listConfigs().stream()
                .map(this::curateConnection).toList();
        return Map.of("result", rows, "count", rows.size());
    }

    /** Connection detail plus its monitored mailboxes — the connection↔mailbox relationship, made explicit. */
    @GetMapping("/connections/{id}")
    public ResponseEntity<?> getConnection(@PathVariable String id) {
        Map<String, Object> raw = configRepo.getConfigById(id);
        if (raw == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = curateConnection(raw);
        out.put("mailboxes", configRepo.listMailboxesByConnection(id).stream()
                .map(MailMonitorAdminController::curateMailbox).toList());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/connections")
    public ResponseEntity<?> createConnection(@RequestBody Map<String, Object> body) {
        return upsertConnection(null, body);
    }

    @PutMapping("/connections/{id}")
    public ResponseEntity<?> updateConnection(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (configRepo.getConfigById(id) == null) return ResponseEntity.notFound().build();
        return upsertConnection(id, body);
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<?> deleteConnection(@PathVariable String id) {
        if (configRepo.getConfigById(id) == null) return ResponseEntity.notFound().build();
        try {
            configRepo.deleteConfig(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> upsertConnection(String id, Map<String, Object> body) {
        String provider = str(body.get("provider"));
        if (provider.isBlank()) provider = "MS_GRAPH";
        boolean graph = "MS_GRAPH".equalsIgnoreCase(provider);

        String name = str(body.get("name"));
        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        String directoryId = str(body.get("directoryId"));
        String clientId = str(body.get("clientId"));
        String clientSecret = str(body.get("clientSecret"));
        String graphBaseUrl = str(body.get("graphBaseUrl"));
        String imapHost = str(body.get("imapHost"));
        int imapPort = body.get("imapPort") instanceof Number n ? n.intValue() : 993;
        boolean imapSsl = body.get("imapSsl") == null || Boolean.TRUE.equals(body.get("imapSsl"));
        int windowDays = body.get("defaultScanWindowDays") instanceof Number n ? n.intValue() : 7;
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));

        // Custom scan schedule (shared ScheduleSpec shape). Absent/null = legacy
        // continuous scanning (every scheduler sweep). Validate before storing so
        // a malformed spec is rejected here instead of silently never firing.
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

        // Provider-specific validation.
        if (graph) {
            if (directoryId.isBlank() || clientId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "directoryId and clientId are required for Microsoft 365"));
            }
        } else if (imapHost.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "imapHost is required for IMAP providers (Google / Zoho / other)"));
        }

        // Graph app secret → Infisical, keyed by connection id so multiple Graph
        // connections don't collide. Only written when a new value was supplied;
        // saveConfig preserves the existing reference otherwise. IMAP credentials
        // are per-mailbox app passwords, handled in addMailbox.
        String secretRef = null;
        if (graph && !clientSecret.isBlank()) {
            if (!secrets.isConfigured()) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "Secret store (Infisical) is not configured; cannot store the client secret."));
            }
            String tenantId = TenantContextHolder.requireTenantId().toString();
            String path = "eip/mail-graph/" + tenantId + "/" + (id == null || id.isBlank() ? "new" : id);
            secrets.writeField(path, "clientSecret", clientSecret);
            secretRef = path + "#clientSecret";
        }

        try {
            String connectionId = configRepo.saveConfig(id, name, provider, directoryId, clientId, secretRef,
                    graphBaseUrl, imapHost, imapPort, imapSsl, windowDays, enabled, scheduleJson);
            return getConnection(connectionId);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> curateConnection(Map<String, Object> raw) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", raw.get("id"));
        out.put("name", raw.get("name"));
        out.put("provider", raw.getOrDefault("provider", "MS_GRAPH"));
        out.put("directoryId", raw.get("directoryId"));
        out.put("clientId", raw.get("clientId"));
        out.put("graphBaseUrl", raw.get("graphBaseUrl"));
        out.put("imapHost", raw.get("imapHost"));
        out.put("imapPort", raw.getOrDefault("imapPort", 993));
        out.put("imapSsl", raw.get("imapSsl") == null ? true : raw.get("imapSsl"));
        out.put("defaultScanWindowDays", raw.get("defaultScanWindowDays"));
        out.put("enabled", raw.get("enabled"));
        out.put("schedule", parseScheduleJson(raw.get("scheduleJson")));
        out.put("lastScheduledScanAt", raw.get("lastScheduledScanAt"));
        out.put("createdAt", raw.get("createdAt"));
        String ref = raw.get("clientSecretRef") == null ? "" : raw.get("clientSecretRef").toString();
        // "Secret stored" must mean the value still lives in Infisical, not merely
        // that ArcadeDB holds a reference: a dropped Infisical volume (or an Azure
        // secret never re-entered) leaves the reference dangling while the value
        // is gone, which previously showed a green badge yet failed every scan.
        out.put("secretConfigured", secretPresent(ref));
        out.put("mailboxCount", configRepo.listMailboxesByConnection(String.valueOf(raw.get("id"))).size());
        return out;
    }

    /** scheduleJson round-trips as a JSON object; blank/broken → null (continuous scanning). */
    private Map<String, Object> parseScheduleJson(Object raw) {
        String json = raw == null ? "" : raw.toString();
        if (json.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(json, Map.class);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** clientSecretRef is stored as "path#field"; verify the value exists in Infisical. */
    private boolean secretPresent(String ref) {
        if (ref == null || ref.isBlank() || !ref.contains("#")) return false;
        if (!secrets.isConfigured()) return false;
        String[] parts = ref.split("#", 2);
        return secrets.readField(parts[0], parts[1]).isPresent();
    }

    // ── Monitored mailboxes ─────────────────────────────────────────────────────

    /**
     * Optionally scoped to one connection via {@code ?connectionId=}, or to mailboxes
     * that don't cleanly belong to any existing connection via {@code ?unassigned=true}
     * (blank {@code mailConnectionId}, or one pointing at a deleted connection).
     * Unfiltered lists every mailbox in the tenant.
     */
    @GetMapping("/mailboxes")
    public Map<String, Object> listMailboxes(@RequestParam(required = false) String connectionId,
                                             @RequestParam(required = false, defaultValue = "false") boolean unassigned) {
        List<Map<String, Object>> raw;
        if (unassigned) {
            raw = configRepo.listUnassignedMailboxes();
        } else if (connectionId != null && !connectionId.isBlank()) {
            raw = configRepo.listMailboxesByConnection(connectionId);
        } else {
            raw = configRepo.listMailboxes();
        }
        List<Map<String, Object>> boxes = raw.stream().map(MailMonitorAdminController::curateMailbox).toList();
        return Map.of("result", boxes, "count", boxes.size());
    }

    /**
     * Partial update: move a mailbox to a different connection
     * ({@code mailConnectionId}, {@code ""} to unassign) and/or toggle its
     * per-mailbox scan opt-in ({@code enabled}). Only the keys present apply.
     */
    @PatchMapping("/mailboxes/{id}")
    public ResponseEntity<?> patchMailbox(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (configRepo.findMailbox(id) == null) return ResponseEntity.notFound().build();
        if (body.containsKey("mailConnectionId")) {
            String mailConnectionId = str(body.get("mailConnectionId"));
            if (!mailConnectionId.isBlank() && configRepo.getConfigById(mailConnectionId) == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "mailConnectionId does not match a known connection"));
            }
            configRepo.reassignMailboxConnection(id, mailConnectionId);
        }
        if (body.containsKey("enabled")) {
            configRepo.setMailboxEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
        }
        return ResponseEntity.ok(curateMailbox(configRepo.findMailbox(id)));
    }

    @PostMapping("/mailboxes")
    public ResponseEntity<?> addMailbox(@RequestBody Map<String, Object> body) {
        String address = str(body.get("address"));
        if (address.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "address is required"));
        }
        String mailConnectionId = str(body.get("mailConnectionId"));
        if (mailConnectionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "mailConnectionId is required"));
        }
        if (configRepo.getConfigById(mailConnectionId) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "mailConnectionId does not match a known connection"));
        }
        Integer window = body.get("scanWindowDays") instanceof Number n ? n.intValue() : null;
        boolean scanMail = !Boolean.FALSE.equals(body.get("scanMail"));
        boolean scanCalendar = !Boolean.FALSE.equals(body.get("scanCalendar"));
        String createdBy = CurrentUser.displayName().orElse("");
        String appPassword = str(body.get("appPassword"));   // IMAP providers only
        // Owner = the tenant user this mailbox belongs to. Blank → tenant-wide
        // (inferred items visible to all users); set → only that user sees the
        // inferred Tasks/Notifications (MailInsight reads filter on ownerSub).
        String ownerSub = str(body.get("ownerSub"));
        String ownerEmail = str(body.get("ownerEmail"));

        String id = configRepo.addMailbox(address, str(body.get("displayName")),
                window, scanMail, scanCalendar, createdBy, ownerSub, ownerEmail, mailConnectionId);

        // For IMAP providers each mailbox carries its own app password → Infisical;
        // ArcadeDB stores only the reference.
        if (!appPassword.isBlank()) {
            if (!secrets.isConfigured()) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "Secret store (Infisical) is not configured; cannot store the app password."));
            }
            String tenantId = TenantContextHolder.requireTenantId().toString();
            String path = "eip/mail-imap/" + tenantId + "/" + id;
            secrets.writeField(path, "password", appPassword);
            configRepo.setMailboxSecretRef(id, path + "#password");
        }
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/mailboxes/{id}")
    public ResponseEntity<?> deleteMailbox(@PathVariable String id) {
        configRepo.deleteMailbox(id);
        return ResponseEntity.noContent().build();
    }

    // ── Manual scan + history ───────────────────────────────────────────────────

    /**
     * mode=sample feeds canned fixtures (no Graph creds needed); default is live.
     * Runs in the background — classification calls the tenant's LLM per item, so
     * a scan across several mailboxes/connections can take minutes, well past any
     * reverse-proxy timeout if run on the request thread. Progress is visible via
     * {@code GET /admin/runs} and each mailbox's {@code lastScanStatus}.
     */
    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestParam(required = false, defaultValue = "live") String mode) {
        boolean sample = "sample".equalsIgnoreCase(mode);
        TenantContext ctx = TenantContextHolder.current()
                .orElseThrow(() -> new IllegalStateException("No tenant context bound to this request"));
        scanService.scanCurrentTenantAsync(ctx, sample);
        return ResponseEntity.accepted().body(Map.of(
                "mode", sample ? "sample" : "live",
                "started", true,
                "message", "Scan started in the background — refresh the run history below to track progress."));
    }

    /**
     * Scan one connection now — only its mailboxes with scanning enabled.
     * Ignores the connection's schedule (manual trigger) and runs in the
     * background like {@link #scan}.
     */
    @PostMapping("/connections/{id}/scan")
    public ResponseEntity<?> scanConnection(@PathVariable String id) {
        Map<String, Object> config = configRepo.getConfigById(id);
        if (config == null) return ResponseEntity.notFound().build();
        if (!Boolean.TRUE.equals(config.get("enabled"))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "This connection is disabled — enable \"scheduled scanning\" and save before running a scan."));
        }
        TenantContext ctx = TenantContextHolder.current()
                .orElseThrow(() -> new IllegalStateException("No tenant context bound to this request"));
        scanService.scanConnectionAsync(ctx, id);
        return ResponseEntity.accepted().body(Map.of(
                "started", true,
                "message", "Scan of \"" + config.getOrDefault("name", id)
                        + "\" started in the background — check the run history for progress."));
    }

    /** Scan one mailbox now (explicit admin action — runs even if its scan opt-in is off). */
    @PostMapping("/mailboxes/{id}/scan")
    public ResponseEntity<?> scanMailbox(@PathVariable String id) {
        Map<String, Object> mb = configRepo.findMailbox(id);
        if (mb == null) return ResponseEntity.notFound().build();
        TenantContext ctx = TenantContextHolder.current()
                .orElseThrow(() -> new IllegalStateException("No tenant context bound to this request"));
        scanService.scanSingleMailboxAsync(ctx, id);
        return ResponseEntity.accepted().body(Map.of(
                "started", true,
                "message", "Scan of " + mb.getOrDefault("address", id)
                        + " started in the background — check the run history for progress."));
    }

    /**
     * Run history, enriched with the mailbox address and connection name each
     * run belonged to — the raw audit row only stores the mailboxId.
     */
    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestParam(required = false, defaultValue = "20") int limit) {
        List<Map<String, Object>> rows = configRepo.listRuns(Math.min(Math.max(limit, 1), 100));

        Map<String, Map<String, Object>> mailboxById = new HashMap<>();
        for (Map<String, Object> mb : configRepo.listMailboxes()) {
            mailboxById.put(str(mb.get("id")), mb);
        }
        Map<String, String> connectionNameById = new HashMap<>();
        for (Map<String, Object> c : configRepo.listConfigs()) {
            connectionNameById.put(str(c.get("id")), str(c.get("name")));
        }

        List<Map<String, Object>> enriched = rows.stream().map(run -> {
            Map<String, Object> out = new HashMap<>(run);
            String mailboxId = str(run.get("mailboxId"));
            if ("sample".equals(mailboxId)) {
                out.put("mailboxAddress", "Sample data");
                out.put("connectionName", "—");
            } else {
                Map<String, Object> mb = mailboxById.get(mailboxId);
                if (mb != null) {
                    out.put("mailboxAddress", str(mb.get("address")));
                    out.put("mailboxDisplayName", str(mb.get("displayName")));
                    out.put("connectionName",
                            connectionNameById.getOrDefault(str(mb.get("mailConnectionId")), ""));
                } else {
                    // Mailbox deleted since the run — keep the id so the row stays traceable.
                    out.put("mailboxAddress", mailboxId);
                    out.put("connectionName", "");
                }
            }
            return out;
        }).toList();
        return Map.of("result", enriched, "count", enriched.size());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Map<String, Object> curateMailbox(Map<String, Object> r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.get("id"));
        m.put("mailConnectionId", r.get("mailConnectionId"));
        m.put("address", r.get("address"));
        m.put("displayName", r.get("displayName"));
        m.put("scanWindowDays", r.get("scanWindowDays"));
        m.put("scanMail", r.get("scanMail"));
        m.put("scanCalendar", r.get("scanCalendar"));
        m.put("ownerSub", r.get("ownerSub"));
        m.put("ownerEmail", r.get("ownerEmail"));
        String ref = r.get("secretRef") == null ? "" : r.get("secretRef").toString();
        m.put("secretConfigured", !ref.isBlank());
        m.put("enabled", r.get("enabled"));
        m.put("lastScanAt", r.get("lastScanAt"));
        m.put("lastScanStatus", r.get("lastScanStatus"));
        m.put("lastError", r.get("lastError"));
        return m;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }
}
