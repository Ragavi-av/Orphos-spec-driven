package io.orphos.eip.mailinsight.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.schedule.ScheduleSpec;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.graph.GraphItem;
import io.orphos.eip.mailinsight.inference.Classification;
import io.orphos.eip.mailinsight.pipeline.CommsVectorIndexer;
import io.orphos.eip.mailinsight.pipeline.ScanResult;
import io.orphos.eip.mailinsight.repo.MailInsightRepository;
import io.orphos.eip.secrets.SecretsClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the communication-channel scan pipeline for the current tenant (tenant
 * context must already be bound): fetch → classify → upsert, with a MailScanRun
 * audit row per account. Classified messages are written into the same
 * {@code MailInsight} feed as email — tagged with their channel — and indexed
 * into the shared comms vector space. Mirrors {@code MailScanService}.
 */
@Service
public class ChannelScanService {

    private static final Logger log = LogManager.getLogger(ChannelScanService.class);

    private final ChannelConfigRepository configRepo;
    private final MailInsightRepository insightRepo;
    private final CommChannelConnectorRegistry registry;
    private final CommsClassifierChain classifier;
    private final SecretsClient secrets;
    private final CommsVectorIndexer commsIndexer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int defaultWindowDays;

    public ChannelScanService(ChannelConfigRepository configRepo,
                              MailInsightRepository insightRepo,
                              CommChannelConnectorRegistry registry,
                              CommsClassifierChain classifier,
                              SecretsClient secrets,
                              CommsVectorIndexer commsIndexer,
                              @Value("${mail-insight.default-scan-window-days:7}") int defaultWindowDays) {
        this.configRepo = configRepo;
        this.insightRepo = insightRepo;
        this.registry = registry;
        this.classifier = classifier;
        this.secrets = secrets;
        this.commsIndexer = commsIndexer;
        this.defaultWindowDays = defaultWindowDays;
    }

    // ── Async fire-and-forget triggers (avoid reverse-proxy timeouts) ─────────

    @Async("scanExecutor")
    public void scanConnectionAsync(TenantContext ctx, String connectionId) {
        TenantContextHolder.runWith(ctx, () -> {
            try {
                scanConnection(connectionId);
            } catch (Exception e) {
                log.warn("Async channel scan failed for tenant {} connection {}: {}",
                        ctx.tenantId(), connectionId, e.getMessage(), e);
            }
        });
    }

    @Async("scanExecutor")
    public void scanAccountAsync(TenantContext ctx, String accountId) {
        TenantContextHolder.runWith(ctx, () -> {
            try {
                scanAccount(accountId);
            } catch (Exception e) {
                log.warn("Async channel scan failed for tenant {} account {}: {}",
                        ctx.tenantId(), accountId, e.getMessage(), e);
            }
        });
    }

    // ── Manual triggers ───────────────────────────────────────────────────────

    /** Scan one connection now — only its enabled accounts. Ignores the schedule. */
    public ScanResult scanConnection(String connectionId) {
        Map<String, Object> config = configRepo.getConnectionById(connectionId);
        if (config == null || !Boolean.TRUE.equals(config.get("enabled"))) return ScanResult.empty();
        List<Map<String, Object>> accounts = configRepo.listAccountsByConnection(connectionId).stream()
                .filter(a -> !Boolean.FALSE.equals(a.get("enabled"))).toList();
        if (accounts.isEmpty()) return ScanResult.empty();
        return scanOneConnection(config, accounts);
    }

    /** Scan one account now (explicit admin action — runs even if its opt-in is off). */
    public ScanResult scanAccount(String accountId) {
        Map<String, Object> account = configRepo.findAccount(accountId);
        if (account == null) return ScanResult.empty();
        Map<String, Object> config = configRepo.getConnectionById(str(account, "channelConnectionId"));
        if (config == null || !Boolean.TRUE.equals(config.get("enabled"))) return ScanResult.empty();
        return scanOneConnection(config, List.of(account));
    }

    // ── Scheduler entry point ───────────────────────────────────────────────

    /**
     * Scan only the connections that are due right now. A connection without a
     * custom schedule scans on every sweep; one with a {@link ScheduleSpec} scans
     * only when an occurrence has passed since its last scheduled scan.
     */
    public ScanResult scanDueConnections() {
        Instant now = Instant.now();
        ScanResult total = ScanResult.empty();
        for (Map<String, Object> config : configRepo.listConnections()) {
            if (!Boolean.TRUE.equals(config.get("enabled"))) continue;

            ScheduleSpec spec;
            try {
                spec = parseSchedule(str(config, "scheduleJson"));
            } catch (Exception e) {
                log.warn("Channel connection {} has an unreadable schedule — skipping ({})",
                        config.get("id"), e.getMessage());
                continue;
            }
            if (spec != null) {
                Instant lastRun = parseInstant(str(config, "lastScheduledScanAt"));
                if (!spec.isDue(lastRun, now)) continue;
            }

            String connectionId = str(config, "id");
            List<Map<String, Object>> accounts = configRepo.listAccountsByConnection(connectionId).stream()
                    .filter(a -> !Boolean.FALSE.equals(a.get("enabled"))).toList();
            if (!accounts.isEmpty()) {
                total = total.plus(scanOneConnection(config, accounts));
            }
            if (spec != null) {
                configRepo.updateScheduledScanStamp(connectionId);
            }
        }
        return total;
    }

    // ── Core ──────────────────────────────────────────────────────────────────

    private ScanResult scanOneConnection(Map<String, Object> config, List<Map<String, Object>> accounts) {
        CommChannelKind kind = CommChannelKind.parse(str(config, "kind"));
        if (kind == null || !registry.supports(kind)) {
            return recordConnectionFailure(accounts,
                    "Channel connector for " + str(config, "kind") + " is not available");
        }
        CommChannelConnector connector = registry.require(kind);
        CommChannelConnector.Descriptor desc;
        try {
            desc = resolveDescriptor(config, kind);
        } catch (RuntimeException e) {
            return recordConnectionFailure(accounts, e.getMessage());
        }
        int windowDefault = config.get("defaultScanWindowDays") instanceof Number n ? n.intValue() : defaultWindowDays;

        ScanResult total = ScanResult.empty();
        for (Map<String, Object> account : accounts) {
            total = total.plus(scanOneAccount(connector, desc, kind, account, windowDefault));
        }
        return total;
    }

    private ScanResult scanOneAccount(CommChannelConnector connector, CommChannelConnector.Descriptor desc,
                                      CommChannelKind kind, Map<String, Object> account, int windowDefault) {
        String accountId = str(account, "id");
        String ownerSub = str(account, "ownerSub");
        int window = account.get("scanWindowDays") instanceof Number n && n.intValue() > 0
                ? n.intValue() : windowDefault;

        String runId = configRepo.startRun(accountId);
        try {
            List<GraphItem> items = connector.listMessages(desc, str(account, "externalId"), window);
            ScanResult r = scanItems(accountId, items, ownerSub, kind.name());
            configRepo.finishRun(runId, "SUCCESS", r.itemsCreated(), r.itemsUpdated(), null);
            configRepo.updateScanStatus(accountId, "SUCCESS", null);
            return r;
        } catch (Exception e) {
            log.warn("Channel scan failed for account {}: {}", accountId, e.getMessage());
            configRepo.finishRun(runId, "FAILED", 0, 0, e.getMessage());
            configRepo.updateScanStatus(accountId, "FAILED", e.getMessage());
            return new ScanResult(0, 0, 0, 1);
        }
    }

    private ScanResult scanItems(String accountId, List<GraphItem> items, String ownerSub, String channel) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        int created = 0, updated = 0;
        for (GraphItem item : items) {
            try {
                Classification c = classifier.classify(item);
                Map<String, Object> fields = toFields(accountId, tenantId, ownerSub, item, c, channel);
                boolean isNew = insightRepo.upsert(fields);
                if (isNew) {
                    created++;
                    commsIndexer.index(tenantId, fields, item.bodyPreview());
                } else {
                    updated++;
                }
            } catch (Exception e) {
                log.debug("Failed to persist channel insight for {}: {}", item.sourceRef(), e.getMessage());
            }
        }
        log.info("Scanned channel account {} ({} items, {} new, {} updated)",
                accountId, items.size(), created, updated);
        return new ScanResult(1, created, updated, 0);
    }

    private Map<String, Object> toFields(String accountId, String tenantId, String ownerSub,
                                         GraphItem item, Classification c, String channel) {
        Map<String, Object> f = new HashMap<>();
        f.put("mailboxId", accountId);   // the account id — lets the agent route a reply back
        f.put("ownerSub", ownerSub == null ? "" : ownerSub);
        f.put("kind", c.kind());
        f.put("source", channel);        // SLACK | MS_TEAMS | DISCORD | GOOGLE_CHAT
        f.put("sourceType", item.sourceType());
        f.put("sourceRef", item.sourceRef());
        f.put("dedupeKey", dedupeKey(tenantId, accountId, item.sourceRef()));
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
        return f;
    }

    private ScanResult recordConnectionFailure(List<Map<String, Object>> accounts, String message) {
        int failures = 0;
        for (Map<String, Object> a : accounts) {
            String accountId = str(a, "id");
            String runId = configRepo.startRun(accountId);
            configRepo.finishRun(runId, "FAILED", 0, 0, message);
            configRepo.updateScanStatus(accountId, "FAILED", message);
            failures++;
        }
        return new ScanResult(0, 0, 0, failures);
    }

    /**
     * Resolve a connection row into a connector descriptor: parse {@code configJson}
     * and resolve the credential JSON from Infisical ({@code credentialRef} is a
     * "path#field").
     */
    CommChannelConnector.Descriptor resolveDescriptor(Map<String, Object> config, CommChannelKind kind) {
        Map<String, Object> cfg = parseConfig(str(config, "configJson"));
        String credentialJson = readSecret(str(config, "credentialRef"));
        return new CommChannelConnector.Descriptor(kind, cfg, credentialJson);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Unreadable channel configJson — treating as empty: {}", e.getMessage());
            return Map.of();
        }
    }

    /** credentialRef is stored as "path#field"; the field holds the credential JSON payload. */
    private String readSecret(String ref) {
        if (ref == null || ref.isBlank() || !ref.contains("#")) return "";
        String[] parts = ref.split("#", 2);
        return secrets.readField(parts[0], parts[1]).orElse("");
    }

    private ScheduleSpec parseSchedule(String json) throws Exception {
        if (json == null || json.isBlank()) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.readValue(json, Map.class);
        return ScheduleSpec.fromMap(m);
    }

    private String toJson(Map<String, Integer> breakdown) {
        try {
            return mapper.writeValueAsString(breakdown == null ? Map.of() : breakdown);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private static String dedupeKey(String tenantId, String accountId, String sourceRef) {
        String basis = tenantId + "|" + accountId + "|" + sourceRef;
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
