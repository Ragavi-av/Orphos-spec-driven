package io.orphos.eip.mailinsight.controller;

import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.datasource.DataSourceReader;
import io.orphos.eip.mailinsight.model.MailInsightDto;
import io.orphos.eip.mailinsight.model.MailInsightSort;
import io.orphos.eip.mailinsight.pipeline.MailScanService;
import io.orphos.eip.mailinsight.repo.MailInsightRepository;
import io.orphos.eip.mailinsight.repo.MailboxConfigRepository;
import io.orphos.eip.mailinsight.repo.OverrideRepository;
import io.orphos.eip.mailinsight.security.CurrentUser;
import io.orphos.eip.secrets.SecretsClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read + action API backing the "Tasks for Action" and "Notifications" tabs.
 * All operations are tenant-scoped by the repository, then filtered to the
 * caller's persona ({@link PersonaDomainPolicy}): shared items outside the
 * persona's business domains are neither listed, counted nor actionable. Items
 * from the caller's own mailbox/calendar are always visible.
 */
@RestController
@RequestMapping("/api/mail-insight")
public class MailInsightController {

    private static final Set<String> PRIORITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static final Set<String> CRITICALITIES = Set.of("ALERT", "WARNING", "INFORMATION");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "DONE", "DISMISSED");

    private final MailInsightRepository repo;
    private final MailboxConfigRepository configRepo;
    private final MailScanService scanService;
    private final SecretsClient secrets;
    private final DataSourceReader dataSource;
    private final OverrideRepository overrideRepo;
    private final AccessGrantSource grants;

    public MailInsightController(MailInsightRepository repo,
                                 MailboxConfigRepository configRepo,
                                 MailScanService scanService,
                                 SecretsClient secrets,
                                 DataSourceReader dataSource,
                                 OverrideRepository overrideRepo,
                                 AccessGrantSource grants) {
        this.repo = repo;
        this.configRepo = configRepo;
        this.scanService = scanService;
        this.secrets = secrets;
        this.dataSource = dataSource;
        this.overrideRepo = overrideRepo;
        this.grants = grants;
    }

    // ── persona filtering ──────────────────────────────────────────────────────

    /** Items the caller's persona may see (see {@link PersonaDomainPolicy}). */
    private List<MailInsightDto> visible(List<MailInsightDto> items) {
        PersonaDomainPolicy.Scope scope = PersonaDomainPolicy.current(grants);
        if (scope.unrestricted()) return items;
        return items.stream().filter(d -> visible(d, scope)).toList();
    }

    private static boolean visible(MailInsightDto d, PersonaDomainPolicy.Scope scope) {
        return scope.allows(d.domain(), PersonaDomainPolicy.isCritical(d.priority(), d.criticality()), d.personal());
    }

    /** Data-source items have synthetic ids ("ds-…") and are acted on via the overlay. */
    private static boolean isDataItem(String id) {
        return id != null && id.startsWith("ds-");
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "mail-insight");
    }

    /**
     * Tasks for Action — merged from email/channels + the live data source.
     * sort = priority | criticality | dueDate. Optional {@code source} is a CSV
     * of {@link io.orphos.eip.mailinsight.model.SourceChannel} values (e.g.
     * {@code SLACK,MS_TEAMS}) — the comms MCP tools use it to read only
     * communication-channel items.
     */
    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(required = false) String sort,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String source,
                                     @RequestParam(required = false) String completedSince) {
        List<MailInsightDto> items;
        if (completedSince != null && !completedSince.isBlank()) {
            // Tasks completed (DONE) at or after this instant. Like /meetings' window, the
            // caller supplies it: only the client knows where the user's day starts.
            Instant since;
            try {
                since = Instant.parse(completedSince);
            } catch (java.time.format.DateTimeParseException e) {
                return Map.of("error", "completedSince must be an ISO-8601 instant", "result", List.of(), "count", 0);
            }
            items = mergedCompletedSince(since.toString());
        } else {
            items = mergedTasks(sort, status);
        }
        items = filterBySource(items, source);
        return Map.of("result", items, "count", items.size());
    }

    /** Email + data-source tasks completed at or after {@code sinceIso} (normalised UTC). */
    private List<MailInsightDto> mergedCompletedSince(String sinceIso) {
        List<MailInsightDto> merged = new ArrayList<>();
        try {
            merged.addAll(repo.listCompletedTasksSince(sinceIso));
        } catch (Exception e) { /* email items unavailable — keep data-source items */ }
        try {
            Map<String, Map<String, Object>> overrides = overrideRepo.findForUser();
            for (MailInsightDto d : withOverrideStatus(dataSource.tasks(), "DONE")) {
                Map<String, Object> o = overrides.get(d.id());
                String completedAt = o == null ? "" : str(o.get("completedAt"));
                if (!completedAt.isBlank() && completedAt.compareTo(sinceIso) >= 0) merged.add(d);
            }
        } catch (Exception e) { /* degrade */ }
        return visible(merged);
    }

    /**
     * Calendar items (MEETING / EVENT) starting inside a window, earliest first.
     *
     * <p>The window is supplied by the CALLER as ISO-8601 instants rather than
     * derived here from a "today" flag. The dashboard renders in the tenant's
     * timezone, so only the caller knows where that tenant's day starts and ends;
     * computing it server-side in UTC would put a 09:00 IST meeting on the wrong
     * day for half the world. Defaults to the next 24 hours when omitted.</p>
     *
     * <p>Rows are owner-scoped by the repository, so each executive sees their own
     * calendar and never a colleague's.</p>
     */
    @GetMapping("/meetings")
    public Map<String, Object> meetings(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        String fromIso = (from == null || from.isBlank()) ? Instant.now().toString() : from;
        String toIso = (to == null || to.isBlank())
                ? Instant.now().plus(24, ChronoUnit.HOURS).toString() : to;
        List<MailInsightDto> items = visible(repo.listMeetings(fromIso, toIso));
        return Map.of("result", items, "count", items.size(), "from", fromIso, "to", toIso);
    }

    /** Notifications — merged from email/channels + the live data source. level = ALERT | WARNING | INFORMATION. */
    @GetMapping("/notifications")
    public Map<String, Object> notifications(@RequestParam(required = false) String level,
                                             @RequestParam(required = false) String source) {
        List<MailInsightDto> items = filterBySource(mergedNotifications(level), source);
        return Map.of("result", items, "count", items.size());
    }

    /** Keep only items whose source is in the CSV allow-list (case-insensitive); blank → no filter. */
    private static List<MailInsightDto> filterBySource(List<MailInsightDto> items, String sourceCsv) {
        if (sourceCsv == null || sourceCsv.isBlank()) return items;
        Set<String> allow = new java.util.HashSet<>();
        for (String s : sourceCsv.split(",")) {
            String v = s.trim().toUpperCase();
            if (!v.isEmpty()) allow.add(v);
        }
        if (allow.isEmpty()) return items;
        return items.stream()
                .filter(d -> d.source() != null && allow.contains(d.source().toUpperCase()))
                .toList();
    }

    /** Counts for the tab/bell badges (email + data source). */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        List<MailInsightDto> tasks = mergedTasks(null, null);
        List<MailInsightDto> notifs = mergedNotifications(null);
        int alerts = 0, warnings = 0, info = 0;
        for (MailInsightDto n : notifs) {
            switch (n.criticality() == null ? "" : n.criticality().toUpperCase()) {
                case "ALERT" -> alerts++;
                case "WARNING" -> warnings++;
                default -> info++;
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("tasks", tasks.size());
        out.put("alerts", alerts);
        out.put("warnings", warnings);
        out.put("information", info);
        out.put("notifications", notifs.size());
        return out;
    }

    // ── merge helpers — each source is independently fault-tolerant so one
    //    failing query never blanks the whole tab ──────────────────────────────

    private List<MailInsightDto> mergedTasks(String sort, String status) {
        List<MailInsightDto> merged = new ArrayList<>();
        try {
            merged.addAll(repo.listTasks(sort, status));
        } catch (Exception e) { /* email items unavailable — keep data-source items */ }
        // Data-source items are live; include them unless the caller filters to a
        // non-open status. Per-user overrides (done/ignore/reprioritize) are applied.
        if (status == null || status.isBlank() || "OPEN".equalsIgnoreCase(status)) {
            try { merged.addAll(applyOverrides(dataSource.tasks())); } catch (Exception e) { /* degrade */ }
        } else {
            // A closed/other status: the data-source items the user gave that status, so
            // e.g. ?status=DONE counts completed data tasks alongside completed email ones.
            try { merged.addAll(withOverrideStatus(dataSource.tasks(), status)); } catch (Exception e) { /* degrade */ }
        }
        return MailInsightSort.tasks(visible(merged), sort);
    }

    private List<MailInsightDto> mergedNotifications(String level) {
        List<MailInsightDto> merged = new ArrayList<>();
        try {
            merged.addAll(repo.listNotifications(level));
        } catch (Exception e) { /* email items unavailable — keep data-source items */ }
        try {
            List<MailInsightDto> data = applyOverrides(dataSource.notifications());
            if (level != null && !level.isBlank()) {
                String lv = level.toUpperCase();
                // Filter on the EFFECTIVE criticality (after any deprioritize override).
                data = data.stream().filter(d -> lv.equals(d.criticality())).toList();
            }
            merged.addAll(data);
        } catch (Exception e) { /* degrade */ }
        return MailInsightSort.notifications(visible(merged));
    }

    /** Apply per-user action overrides to live data-source items; drop done/ignored
     *  and items snoozed by a future reminder. */
    private List<MailInsightDto> applyOverrides(List<MailInsightDto> items) {
        Map<String, Map<String, Object>> overrides = overrideRepo.findForUser();
        if (overrides.isEmpty()) return items;
        String now = java.time.Instant.now().toString();
        List<MailInsightDto> out = new ArrayList<>();
        for (MailInsightDto d : items) {
            Map<String, Object> o = overrides.get(d.id());
            if (o == null) { out.add(d); continue; }
            String st = str(o.get("status"));
            if ("DONE".equalsIgnoreCase(st) || "DISMISSED".equalsIgnoreCase(st)) continue; // closed → hidden
            String reminderAt = str(o.get("reminderAt"));
            if (!reminderAt.isBlank() && reminderAt.compareTo(now) > 0) continue;            // snoozed → hidden
            out.add(d.withOverrides(st, str(o.get("priorityOverride")),
                    str(o.get("criticalityOverride")), reminderAt));
        }
        return out;
    }

    /** Live data-source items whose per-user override status is {@code status}. */
    private List<MailInsightDto> withOverrideStatus(List<MailInsightDto> items, String status) {
        Map<String, Map<String, Object>> overrides = overrideRepo.findForUser();
        if (overrides.isEmpty()) return List.of();
        List<MailInsightDto> out = new ArrayList<>();
        for (MailInsightDto d : items) {
            Map<String, Object> o = overrides.get(d.id());
            if (o == null || !status.equalsIgnoreCase(str(o.get("status")))) continue;
            out.add(d.withOverrides(str(o.get("status")), str(o.get("priorityOverride")),
                    str(o.get("criticalityOverride")), str(o.get("reminderAt"))));
        }
        return out;
    }

    /** Reprioritize — sets a user override that wins over the inferred priority. */
    @PatchMapping("/items/{id}/priority")
    public ResponseEntity<?> reprioritize(@PathVariable String id, @RequestBody Map<String, String> body) {
        String priority = upper(body.get("priority"));
        if (!PRIORITIES.contains(priority)) {
            return ResponseEntity.badRequest().body(Map.of("error", "priority must be one of " + PRIORITIES));
        }
        if (isDataItem(id)) {
            overrideRepo.setField(id, "priorityOverride", priority);
            return ok();
        }
        if (!repo.updatePriority(id, priority)) return ResponseEntity.notFound().build();
        return ok();
    }

    /** Deprioritize a notification — sets a criticality override (e.g. ALERT → WARNING). */
    @PatchMapping("/items/{id}/criticality")
    public ResponseEntity<?> setCriticality(@PathVariable String id, @RequestBody Map<String, String> body) {
        String criticality = upper(body.get("criticality"));
        if (!CRITICALITIES.contains(criticality)) {
            return ResponseEntity.badRequest().body(Map.of("error", "criticality must be one of " + CRITICALITIES));
        }
        if (isDataItem(id)) {
            overrideRepo.setField(id, "criticalityOverride", criticality);
            return ok();
        }
        if (!repo.updateCriticality(id, criticality)) return ResponseEntity.notFound().build();
        return ok();
    }

    /** Mark done / in-progress / dismiss / reopen (Complete, Not Required, Ignore…). */
    @PatchMapping("/items/{id}/status")
    public ResponseEntity<?> setStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        String status = upper(body.get("status"));
        if (!STATUSES.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "status must be one of " + STATUSES));
        }
        if (isDataItem(id)) {
            overrideRepo.setStatus(id, status);
            return ok();
        }
        if (!repo.updateStatus(id, status)) return ResponseEntity.notFound().build();
        return ok();
    }

    /** Set or clear a reminder (snooze). Pass {"reminderAt": null} to clear. */
    @PatchMapping("/items/{id}/reminder")
    public ResponseEntity<?> setReminder(@PathVariable String id, @RequestBody Map<String, String> body) {
        String reminderAt = body.get("reminderAt");
        if (isDataItem(id)) {
            overrideRepo.setField(id, "reminderAt", reminderAt == null ? "" : reminderAt);
            return ok();
        }
        if (!repo.updateReminder(id, reminderAt)) return ResponseEntity.notFound().build();
        return ok();
    }

    private static ResponseEntity<?> ok() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Self-service: monitor the signed-in user's own SSO mailbox ───────────
    //
    // The user's mailbox address comes from their JWT (email/UPN). For Microsoft
    // 365 the tenant's app-only Graph connection reads it directly — no extra
    // login. Resulting items are private to the user (ownerSub = their sub).

    @GetMapping("/me")
    public Map<String, Object> myStatus() {
        Map<String, Object> out = new HashMap<>();
        String email = CurrentUser.email().orElse("");
        String sub = CurrentUser.sub().orElse("");
        Map<String, Object> config = configRepo.getPrimaryConfig();
        String provider = config != null ? String.valueOf(config.getOrDefault("provider", "MS_GRAPH")) : "MS_GRAPH";
        boolean imap = !(provider == null || provider.isBlank() || "MS_GRAPH".equalsIgnoreCase(provider));

        Map<String, Object> mine = findMyMailbox(sub);
        out.put("email", email);
        out.put("provider", provider);
        out.put("connectionEnabled", config != null && Boolean.TRUE.equals(config.get("enabled")));
        out.put("requiresAppPassword", imap);              // IMAP needs an app password (no SSO pass-through)
        out.put("supportsCalendar", !imap);                // calendar only on M365
        out.put("monitored", mine != null);
        if (mine != null) {
            out.put("mailboxId", mine.get("id"));
            out.put("address", mine.get("address"));
            out.put("lastScanStatus", mine.get("lastScanStatus"));
            out.put("lastScanAt", mine.get("lastScanAt"));
            String ref = mine.get("secretRef") == null ? "" : mine.get("secretRef").toString();
            out.put("appPasswordSet", !ref.isBlank());
        }
        return out;
    }

    /**
     * The mailbox addresses this user has configured for scanning — used by
     * other admin screens (e.g. a data-source BASIC-auth email picker) so the
     * user can reuse a known address or type a new one. Scoped to the caller's
     * own {@code ownerSub}; never exposes another user's or tenant-wide mailboxes.
     */
    @GetMapping("/me/scan-emails")
    public Map<String, Object> myScanEmails() {
        String sub = CurrentUser.sub().orElse("");
        return Map.of("emails", configRepo.listMailboxAddressesForOwner(sub));
    }

    /** Start monitoring the signed-in user's own mailbox. */
    @PostMapping("/me/enable")
    public ResponseEntity<?> enableMine(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String sub = CurrentUser.sub().orElse("");
        if (sub.isBlank()) return ResponseEntity.status(401).body(Map.of("error", "no authenticated user"));

        String address = str(b.get("address"));
        if (address.isBlank()) address = CurrentUser.email().orElse("");
        if (address.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Your account has no email/UPN claim; provide an address explicitly."));
        }

        Map<String, Object> config = configRepo.getPrimaryConfig();
        String provider = config != null ? String.valueOf(config.getOrDefault("provider", "MS_GRAPH")) : "MS_GRAPH";
        boolean imap = !("MS_GRAPH".equalsIgnoreCase(provider) || provider.isBlank());
        String appPassword = str(b.get("appPassword"));
        boolean scanCalendar = !imap && !Boolean.FALSE.equals(b.get("scanCalendar"));
        Integer window = b.get("scanWindowDays") instanceof Number n ? n.intValue() : null;
        String email = CurrentUser.email().orElse(address);

        // Reuse an existing mailbox for this address if present; else create a
        // personal one owned by this user.
        Map<String, Object> existing = configRepo.findByAddress(address);
        String mailboxId;
        if (existing != null) {
            mailboxId = String.valueOf(existing.get("id"));
        } else {
            mailboxId = configRepo.addMailbox(address, email, window, true, scanCalendar,
                    email, sub, email);
        }

        // IMAP needs a per-mailbox app password (no SSO pass-through).
        if (imap) {
            if (appPassword.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "This provider uses IMAP — an app password is required to read your mailbox.",
                        "requiresAppPassword", true, "mailboxId", mailboxId));
            }
            if (!secrets.isConfigured()) {
                return ResponseEntity.status(503).body(Map.of(
                        "error", "Secret store (Infisical) is not configured; cannot store the app password."));
            }
            String tenantId = TenantContextHolder.requireTenantId().toString();
            String path = "eip/mail-imap/" + tenantId + "/" + mailboxId;
            secrets.writeField(path, "password", appPassword);
            configRepo.setMailboxSecretRef(mailboxId, path + "#password");
        }
        return ResponseEntity.ok(myStatus());
    }

    /**
     * Scan the signed-in user's own mailbox now. Runs in the background —
     * classification calls the tenant's LLM per item, which can take well past
     * any reverse-proxy timeout if run on the request thread. Poll {@code GET
     * /me} afterwards for the updated {@code lastScanStatus}.
     */
    @PostMapping("/me/scan")
    public ResponseEntity<?> scanMine() {
        Map<String, Object> mine = findMyMailbox(CurrentUser.sub().orElse(""));
        if (mine == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Your mailbox is not being monitored yet."));
        }
        TenantContext ctx = TenantContextHolder.current()
                .orElseThrow(() -> new IllegalStateException("No tenant context bound to this request"));
        scanService.scanSingleMailboxAsync(ctx, String.valueOf(mine.get("id")));
        return ResponseEntity.accepted().body(Map.of("started", true));
    }

    /** Stop monitoring the signed-in user's own mailbox. */
    @DeleteMapping("/me")
    public ResponseEntity<?> disableMine() {
        Map<String, Object> mine = findMyMailbox(CurrentUser.sub().orElse(""));
        if (mine != null) configRepo.deleteMailbox(String.valueOf(mine.get("id")));
        return ResponseEntity.noContent().build();
    }

    /** Finds the personal mailbox owned by the given user sub, if any. */
    private Map<String, Object> findMyMailbox(String sub) {
        if (sub == null || sub.isBlank()) return null;
        for (Map<String, Object> mb : configRepo.listMailboxes()) {
            if (sub.equals(String.valueOf(mb.get("ownerSub")))) return mb;
        }
        return null;
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }
}
