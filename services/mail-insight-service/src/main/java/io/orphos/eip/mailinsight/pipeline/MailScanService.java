package io.orphos.eip.mailinsight.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.schedule.ScheduleSpec;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.graph.GraphConnection;
import io.orphos.eip.mailinsight.graph.GraphConnector;
import io.orphos.eip.mailinsight.graph.GraphItem;
import io.orphos.eip.mailinsight.graph.ImapConnector;
import io.orphos.eip.mailinsight.graph.TeamsConnector;
import io.orphos.eip.mailinsight.inference.Classification;
import io.orphos.eip.mailinsight.inference.ClassifierChain;
import io.orphos.eip.mailinsight.model.SourceChannel;
import io.orphos.eip.mailinsight.repo.MailInsightRepository;
import io.orphos.eip.mailinsight.repo.MailboxConfigRepository;
import io.orphos.eip.secrets.SecretsClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the scan pipeline for the <em>current tenant</em> (tenant context must
 * already be bound — by the request thread or by TenantTaskExecutor):
 * fetch → normalize → dedupe → classify → upsert, with a MailScanRun audit row
 * per mailbox.
 */
@Service
public class MailScanService {

    private static final Logger log = LogManager.getLogger(MailScanService.class);

    private final MailboxConfigRepository configRepo;
    private final MailInsightRepository insightRepo;
    private final GraphConnector graph;
    private final TeamsConnector teams;
    private final ImapConnector imap;
    private final ClassifierChain classifier;
    private final SecretsClient secrets;
    private final CommsVectorIndexer commsIndexer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int defaultWindowDays;

    public MailScanService(MailboxConfigRepository configRepo,
                           MailInsightRepository insightRepo,
                           GraphConnector graph,
                           TeamsConnector teams,
                           ImapConnector imap,
                           ClassifierChain classifier,
                           SecretsClient secrets,
                           CommsVectorIndexer commsIndexer,
                           @Value("${mail-insight.default-scan-window-days:7}") int defaultWindowDays) {
        this.configRepo = configRepo;
        this.insightRepo = insightRepo;
        this.graph = graph;
        this.teams = teams;
        this.imap = imap;
        this.classifier = classifier;
        this.secrets = secrets;
        this.commsIndexer = commsIndexer;
        this.defaultWindowDays = defaultWindowDays;
    }

    /** Microsoft 365 uses the Graph API; everything else uses IMAP. */
    private static boolean isGraph(String provider) {
        return provider == null || provider.isBlank() || "MS_GRAPH".equalsIgnoreCase(provider);
    }

    /**
     * Fire-and-forget entry point for the manual "Live/Sample scan" trigger.
     * Classification calls the tenant's LLM per item (see {@code ClassifierChain}),
     * so a scan across several mailboxes can easily run for minutes — far past
     * any reverse-proxy read timeout if run on the request thread (this is what
     * produced the "Scan failed (HTTP 504: Gateway Time-out)" error). Runs on the
     * bounded {@code scanExecutor} instead; the caller must capture the request's
     * {@link TenantContext} and pass it in, since the ThreadLocal binding doesn't
     * cross onto the async thread on its own. Progress is visible via the existing
     * MailScanRun audit rows ({@code GET /admin/runs}), started/finished per mailbox
     * regardless of which thread runs the scan.
     */
    @Async("scanExecutor")
    public void scanCurrentTenantAsync(TenantContext ctx, boolean sampleMode) {
        TenantContextHolder.runWith(ctx, () -> {
            try {
                scanCurrentTenant(sampleMode);
            } catch (Exception e) {
                log.warn("Async scan failed for tenant {}: {}", ctx.tenantId(), e.getMessage(), e);
            }
        });
    }

    /** Fire-and-forget scan of one connection's enabled mailboxes (admin "Run scan" on a connection). */
    @Async("scanExecutor")
    public void scanConnectionAsync(TenantContext ctx, String connectionId) {
        TenantContextHolder.runWith(ctx, () -> {
            try {
                scanConnection(connectionId);
            } catch (Exception e) {
                log.warn("Async scan failed for tenant {} connection {}: {}",
                        ctx.tenantId(), connectionId, e.getMessage(), e);
            }
        });
    }

    /**
     * Scan one connection now — only its mailboxes with scanning enabled.
     * A manual trigger, so the connection's ScheduleSpec is ignored (and the
     * scheduled-scan stamp is left untouched: an ad-hoc run shouldn't swallow
     * the next scheduled occurrence).
     */
    public ScanResult scanConnection(String connectionId) {
        Map<String, Object> config = configRepo.getConfigById(connectionId);
        if (config == null || !Boolean.TRUE.equals(config.get("enabled"))) {
            return ScanResult.empty();
        }
        List<Map<String, Object>> mailboxes = configRepo.listMailboxesByConnection(connectionId).stream()
                .filter(mb -> !Boolean.FALSE.equals(mb.get("enabled"))).toList();
        if (mailboxes.isEmpty() && !hasTeams(config)) return ScanResult.empty();
        return scanOneConnection(config, mailboxes, false);
    }

    /** Same fire-and-forget treatment as {@link #scanCurrentTenantAsync}, for the self-service single-mailbox scan. */
    @Async("scanExecutor")
    public void scanSingleMailboxAsync(TenantContext ctx, String mailboxId) {
        TenantContextHolder.runWith(ctx, () -> {
            try {
                scanSingleMailbox(mailboxId);
            } catch (Exception e) {
                log.warn("Async scan failed for tenant {} mailbox {}: {}", ctx.tenantId(), mailboxId, e.getMessage(), e);
            }
        });
    }

    /**
     * Scan all enabled mailboxes for the current tenant, across every connection
     * the tenant has configured.
     *
     * @param sampleMode when true, feed canned fixtures through the real
     *                   classify/persist path instead of calling Graph — lets the
     *                   feature be demoed without a Microsoft 365 app registration.
     */
    public ScanResult scanCurrentTenant(boolean sampleMode) {
        List<Map<String, Object>> configs = configRepo.listConfigs();

        if (configRepo.listEnabledMailboxes().isEmpty() && sampleMode) {
            // Allow a sample scan with no configured mailbox — use a virtual one.
            return scanMailboxItems("sample", "sample-inbox", SampleFixtures.items(), "", SourceChannel.EMAIL);
        }

        List<Map<String, Object>> targetConfigs = sampleMode ? configs : configs.stream()
                .filter(c -> Boolean.TRUE.equals(c.get("enabled"))).toList();
        if (!sampleMode && targetConfigs.isEmpty()) {
            log.debug("Tenant {} has no enabled mail connection — skipping scan",
                    TenantContextHolder.requireTenantId());
            return ScanResult.empty();
        }

        ScanResult total = ScanResult.empty();
        for (Map<String, Object> config : targetConfigs) {
            String connectionId = str(config, "id");
            List<Map<String, Object>> mailboxes = configRepo.listMailboxesByConnection(connectionId).stream()
                    .filter(mb -> !Boolean.FALSE.equals(mb.get("enabled"))).toList();
            if (mailboxes.isEmpty() && !hasTeams(config)) continue;
            total = total.plus(scanOneConnection(config, mailboxes, sampleMode));
        }
        return total;
    }

    /**
     * Scheduler entry point: scan only the connections that are due right now.
     * A connection without a custom schedule keeps the legacy behavior — it
     * scans on every sweep. One with a {@link ScheduleSpec} scans only when an
     * occurrence has passed since its last scheduled scan, and the stamp is
     * updated afterwards so the next sweep doesn't re-fire the same slot.
     * Manual scans ({@link #scanCurrentTenant}) ignore schedules entirely.
     */
    public ScanResult scanDueConnections() {
        Instant now = Instant.now();
        ScanResult total = ScanResult.empty();
        for (Map<String, Object> config : configRepo.listConfigs()) {
            if (!Boolean.TRUE.equals(config.get("enabled"))) continue;

            ScheduleSpec spec;
            try {
                spec = parseSchedule(str(config, "scheduleJson"));
            } catch (Exception e) {
                log.warn("Connection {} has an unreadable schedule — skipping ({})",
                        config.get("id"), e.getMessage());
                continue;
            }
            if (spec != null) {
                Instant lastRun = parseInstant(str(config, "lastScheduledScanAt"));
                if (!spec.isDue(lastRun, now)) continue;
            }

            String connectionId = str(config, "id");
            List<Map<String, Object>> mailboxes = configRepo.listMailboxesByConnection(connectionId).stream()
                    .filter(mb -> !Boolean.FALSE.equals(mb.get("enabled"))).toList();
            if (!mailboxes.isEmpty() || hasTeams(config)) {
                total = total.plus(scanOneConnection(config, mailboxes, false));
            }
            // Stamp even when the connection has no mailboxes yet — otherwise a
            // due-but-empty scheduled connection would re-fire on every sweep
            // and then burst-scan the moment a mailbox is added.
            if (spec != null) {
                configRepo.updateScheduledScanStamp(connectionId);
            }
        }
        return total;
    }

    private ScheduleSpec parseSchedule(String json) throws Exception {
        if (json == null || json.isBlank()) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.readValue(json, Map.class);
        return ScheduleSpec.fromMap(m);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private ScanResult scanOneConnection(Map<String, Object> config, List<Map<String, Object>> mailboxes,
                                         boolean sampleMode) {
        int windowDefault = config.get("defaultScanWindowDays") instanceof Number n
                ? n.intValue() : defaultWindowDays;
        boolean graphMode = isGraph(str(config, "provider"));
        // Microsoft Graph uses one app-only connection for all its mailboxes; IMAP
        // resolves per-mailbox credentials inside scanOneMailbox. A connection-level
        // failure (e.g. a lost client secret) is recorded as a FAILED run per
        // mailbox so the admin UI surfaces the actionable message.
        GraphConnection conn = null;
        if (!sampleMode && graphMode) {
            try {
                conn = resolveConnection(config);
            } catch (RuntimeException e) {
                return recordConnectionFailure(mailboxes, e.getMessage());
            }
        }
        ScanResult total = ScanResult.empty();
        for (Map<String, Object> mb : mailboxes) {
            total = total.plus(scanOneMailbox(config, mb, sampleMode, graphMode, conn, windowDefault));
        }
        // Teams channel messages ride on the same Graph app-only connection.
        if (!sampleMode && graphMode && conn != null) {
            total = total.plus(scanTeamsChannels(config, conn, windowDefault));
        }
        return total;
    }

    /**
     * Scan every configured Teams channel on this Graph connection. Channels are
     * stored on the connection as {@code teamsChannelsJson}: a JSON array of
     * {@code {"teamId":..,"channelId":..,"label":..}}. Each channel is audited as
     * its own run (keyed by a synthetic {@code teams:<team>/<channel>} id) and its
     * messages flow through the same classify → persist path tagged
     * {@link SourceChannel#MS_TEAMS}.
     */
    private ScanResult scanTeamsChannels(Map<String, Object> config, GraphConnection conn, int windowDefault) {
        List<Map<String, Object>> channels = parseTeamsChannels(str(config, "teamsChannelsJson"));
        if (channels.isEmpty()) return ScanResult.empty();

        int window = config.get("defaultScanWindowDays") instanceof Number n ? n.intValue() : windowDefault;
        ScanResult total = ScanResult.empty();
        for (Map<String, Object> ch : channels) {
            String teamId = str(ch, "teamId");
            String channelId = str(ch, "channelId");
            if (teamId == null || teamId.isBlank() || channelId == null || channelId.isBlank()) continue;

            String syntheticId = "teams:" + teamId + "/" + channelId;
            String runId = configRepo.startRun(syntheticId);
            try {
                List<GraphItem> items = teams.listChannelMessages(conn, teamId, channelId, window);
                ScanResult r = scanMailboxItems(syntheticId, syntheticId, items, "", SourceChannel.MS_TEAMS);
                configRepo.finishRun(runId, "SUCCESS", r.itemsCreated(), r.itemsUpdated(), null);
                total = total.plus(r);
            } catch (Exception e) {
                log.warn("Teams scan failed for {}: {}", syntheticId, e.getMessage());
                configRepo.finishRun(runId, "FAILED", 0, 0, e.getMessage());
                total = total.plus(new ScanResult(0, 0, 0, 1));
            }
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTeamsChannels(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, List.class); }
        catch (Exception e) {
            log.warn("Unreadable teamsChannelsJson — ignoring Teams channels: {}", e.getMessage());
            return List.of();
        }
    }

    /** True when the connection has at least one Teams channel configured. */
    private boolean hasTeams(Map<String, Object> config) {
        return !parseTeamsChannels(str(config, "teamsChannelsJson")).isEmpty();
    }

    /**
     * Scan a single mailbox now (used by the self-service "scan my mailbox"
     * action). Tenant context must be bound. Returns empty if the mailbox's
     * connection is not enabled or the mailbox is not found.
     */
    public ScanResult scanSingleMailbox(String mailboxId) {
        Map<String, Object> mb = configRepo.findMailbox(mailboxId);
        if (mb == null) return ScanResult.empty();

        String connectionId = str(mb, "mailConnectionId");
        Map<String, Object> config = connectionId != null && !connectionId.isBlank()
                ? configRepo.getConfigById(connectionId) : configRepo.getPrimaryConfig();
        if (config == null || !Boolean.TRUE.equals(config.get("enabled"))) {
            return ScanResult.empty();
        }

        int windowDefault = config.get("defaultScanWindowDays") instanceof Number n
                ? n.intValue() : defaultWindowDays;
        boolean graphMode = isGraph(str(config, "provider"));
        GraphConnection conn = null;
        if (graphMode) {
            try {
                conn = resolveConnection(config);
            } catch (RuntimeException e) {
                return recordConnectionFailure(List.of(mb), e.getMessage());
            }
        }
        return scanOneMailbox(config, mb, false, graphMode, conn, windowDefault);
    }

    /**
     * Record a connection-level failure (e.g. a lost or expired client secret)
     * as a FAILED run and status for each affected mailbox, so the admin UI
     * surfaces the actionable message exactly as a per-mailbox failure would.
     */
    private ScanResult recordConnectionFailure(List<Map<String, Object>> mailboxes, String message) {
        int failures = 0;
        for (Map<String, Object> mb : mailboxes) {
            String mailboxId = str(mb, "id");
            String runId = configRepo.startRun(mailboxId);
            configRepo.finishRun(runId, "FAILED", 0, 0, message);
            configRepo.updateScanStatus(mailboxId, "FAILED", message);
            failures++;
        }
        return new ScanResult(0, 0, 0, failures);
    }

    private ScanResult scanOneMailbox(Map<String, Object> config, Map<String, Object> mb,
                                      boolean sampleMode, boolean graphMode,
                                      GraphConnection conn, int windowDefault) {
        String mailboxId = str(mb, "id");
        String address = str(mb, "address");
        String ownerSub = str(mb, "ownerSub");
        int window = mb.get("scanWindowDays") instanceof Number n && n.intValue() > 0
                ? n.intValue() : windowDefault;
        boolean scanMail = !Boolean.FALSE.equals(mb.get("scanMail"));
        boolean scanCal = !Boolean.FALSE.equals(mb.get("scanCalendar"));

        String imapHost = str(config, "imapHost");
        int imapPort = config.get("imapPort") instanceof Number n ? n.intValue() : 993;
        boolean imapSsl = !Boolean.FALSE.equals(config.get("imapSsl"));

        String runId = configRepo.startRun(mailboxId);
        try {
            List<GraphItem> items = new ArrayList<>();
            if (sampleMode) {
                items.addAll(SampleFixtures.items());
            } else if (graphMode) {
                if (scanMail) items.addAll(graph.listMessages(conn, address, window));
                if (scanCal)  items.addAll(graph.listEvents(conn, address, window));
            } else {
                // IMAP (Google / Zoho / other): mail only, per-mailbox app password.
                String password = readSecret(str(mb, "secretRef"));
                items.addAll(imap.fetch(imapHost, imapPort, imapSsl, address, password, window));
            }
            // MS_GRAPH/GOOGLE/ZOHO/IMAP are all mailbox-access protocols for the
            // same EMAIL channel — see SourceChannel.fromMailProvider.
            String channel = SourceChannel.fromMailProvider(str(config, "provider"));
            ScanResult r = scanMailboxItems(mailboxId, address, items, ownerSub, channel);
            configRepo.finishRun(runId, "SUCCESS", r.itemsCreated(), r.itemsUpdated(), null);
            configRepo.updateScanStatus(mailboxId, "SUCCESS", null);
            return r;
        } catch (Exception e) {
            log.warn("Scan failed for mailbox {}: {}", mailboxId, e.getMessage());
            configRepo.finishRun(runId, "FAILED", 0, 0, e.getMessage());
            configRepo.updateScanStatus(mailboxId, "FAILED", e.getMessage());
            return new ScanResult(0, 0, 0, 1);
        }
    }

    private ScanResult scanMailboxItems(String mailboxId, String address, List<GraphItem> items,
                                        String ownerSub, String channel) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        int created = 0, updated = 0;
        for (GraphItem item : items) {
            try {
                Classification c = classifier.classify(item);
                Map<String, Object> fields = toFields(mailboxId, tenantId, ownerSub, item, c, channel);
                boolean isNew = insightRepo.upsert(fields);
                if (isNew) {
                    created++;
                    // Index newly-created communications into the shared vector space so
                    // the copilot's federated retrieval can surface them. Only on create
                    // (dedupe-safe); best-effort — a vector failure never fails the scan.
                    commsIndexer.index(tenantId, fields, item.bodyPreview());
                } else {
                    updated++;
                }
            } catch (Exception e) {
                log.debug("Failed to persist insight for {}: {}", item.sourceRef(), e.getMessage());
            }
        }
        log.info("Scanned mailbox {} ({} items, {} new, {} updated)",
                mailboxId, items.size(), created, updated);
        return new ScanResult(1, created, updated, 0);
    }

    private Map<String, Object> toFields(String mailboxId, String tenantId, String ownerSub,
                                         GraphItem item, Classification c, String channel) {
        Map<String, Object> f = new HashMap<>();
        f.put("mailboxId", mailboxId);
        f.put("ownerSub", ownerSub == null ? "" : ownerSub);
        f.put("kind", c.kind());
        f.put("source", channel);
        f.put("sourceType", item.sourceType());
        f.put("sourceRef", item.sourceRef());
        f.put("dedupeKey", dedupeKey(tenantId, mailboxId, item.sourceRef()));
        f.put("title", nullSafe(item.subject()));
        f.put("summary", nullSafe(c.summary()));
        f.put("senderDisplay", nullSafe(item.senderDisplay()));
        f.put("priority", c.priority());
        f.put("criticality", c.criticality());
        f.put("score", c.score());
        f.put("scoreBreakdown", toJson(c.scoreBreakdown()));
        f.put("rationale", nullSafe(c.rationale()));
        f.put("dueDate", c.dueDate() == null ? "" : c.dueDate().toString());
        f.put("receivedAt", item.receivedAt() == null ? "" : item.receivedAt().toString());
        f.put("inferenceSource", c.inferenceSource());
        // Meeting fields (sourceType=MEETING/EVENT) for the mobile "Today's meeting"
        // card. Start/end/attendees/location/join come from the Graph calendarView;
        // brief points are derived from the AI summary (LLM enrichment can replace them).
        if (item.isCalendar()) {
            if (item.eventStart() != null) {
                f.put("meetingStartAt", item.eventStart().toString());
            }
            f.put("organizerDisplay", nullSafe(item.senderDisplay()));
            f.put("briefPointsJson", briefPoints(c.summary()));
            GraphItem.MeetingInfo mtg = item.meeting();
            if (mtg != null) {
                if (mtg.eventEnd() != null) f.put("meetingEndAt", mtg.eventEnd().toString());
                f.put("attendeesJson", nullSafe(mtg.attendeesJson()));
                f.put("attendeeCount", mtg.attendeeCount());
                f.put("locationText", nullSafe(mtg.locationText()));
                f.put("onlineJoinUrlRef", nullSafe(mtg.onlineJoinUrl()));
            }
        }
        return f;
    }

    /** Splits an AI summary into up to four brief bullet points as a JSON array. */
    private String briefPoints(String summary) {
        if (summary == null || summary.isBlank()) return "[]";
        var arr = mapper.createArrayNode();
        int n = 0;
        for (String s : summary.split("(?<=[.!?。！？])\\s+")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            arr.add(t);
            if (++n >= 4) break;
        }
        try {
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            return "[]";
        }
    }

    private GraphConnection resolveConnection(Map<String, Object> config) {
        String ref = str(config, "clientSecretRef");
        String secret = readSecret(ref);
        GraphConnection conn = new GraphConnection(
                str(config, "directoryId"),
                str(config, "clientId"),
                secret,
                str(config, "graphBaseUrl"));
        // Fail fast with an actionable message rather than letting an empty/blank
        // secret round-trip to Azure, where it surfaces as the cryptic
        // AADSTS7000216 ("client_secret is required"). The usual cause is a lost
        // Infisical value (dropped volume) or an Azure app secret never re-entered.
        if (!conn.isComplete()) {
            throw new IllegalStateException(
                    "Microsoft Graph connection is incomplete: the client secret could not be "
                    + "read from the secret store. Re-enter the Azure app client secret under "
                    + "\"Mail connection\" and Save.");
        }
        return conn;
    }

    /** clientSecretRef is stored as "path#field". */
    private String readSecret(String ref) {
        if (ref == null || ref.isBlank() || !ref.contains("#")) return "";
        String[] parts = ref.split("#", 2);
        return secrets.readField(parts[0], parts[1]).orElse("");
    }

    private String toJson(Map<String, Integer> breakdown) {
        try {
            return mapper.writeValueAsString(breakdown == null ? Map.of() : breakdown);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String dedupeKey(String tenantId, String mailboxId, String sourceRef) {
        String basis = tenantId + "|" + mailboxId + "|" + sourceRef;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(basis.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return basis;
        }
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : null;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
