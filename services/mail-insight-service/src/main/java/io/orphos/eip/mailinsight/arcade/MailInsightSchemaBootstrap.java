package io.orphos.eip.mailinsight.arcade;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Creates the mail-insight document types in ArcadeDB on startup. Idempotent.
 *
 * ArcadeDB DDL notes (same as doc-ingest):
 *   - CREATE DOCUMENT TYPE supports IF NOT EXISTS.
 *   - CREATE PROPERTY does NOT support IF NOT EXISTS — tolerate duplicate failures.
 *   - CREATE INDEX supports IF NOT EXISTS and requires the properties to exist first.
 *
 * Types:
 *   MailMonitorConfig  — one or more per tenant: named mail connections (Graph or IMAP).
 *   MonitoredMailbox   — many per tenant, each tied to one MailMonitorConfig via mailConnectionId.
 *   MailInsight        — inferred items (TASK / NOTIFICATION), the two tabs read this.
 *   MailScanRun        — audit of each scan.
 */
@Component
@Order(1)
public class MailInsightSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(MailInsightSchemaBootstrap.class);

    private final MailArcadeClient arcade;

    /** Carries the connection binder's schema mode ({@code eip.connections.schema-mode}). */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    public MailInsightSchemaBootstrap(MailArcadeClient arcade) {
        this.arcade = arcade;
    }

    @Override
    public void run(ApplicationArguments args) {
        // EXISTING schema mode → the assigned database is operator-managed; do not run DDL.
        if (io.orphos.eip.core.dbregistry.SchemaModeGuard.isExisting(environment)) {
            log.info("Mail-insight schema bootstrap skipped: assigned connection uses schemaMode=EXISTING "
                    + "(operator-managed schema) — no DDL applied.");
            return;
        }
        log.info("Applying mail-insight schema to ArcadeDB...");
        try {
            createMonitorConfig();
            createMonitoredMailbox();
            createChannelConnection();
            createMonitoredChannelAccount();
            createMailInsight();
            createScanRun();
            createItemOverride();
            log.info("Mail-insight schema ready.");
        } catch (Exception e) {
            log.error("Schema bootstrap failed — mail-insight data will not persist: {}", e.getMessage(), e);
        }
    }

    private void createMonitorConfig() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE MailMonitorConfig IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY MailMonitorConfig.id                    STRING",
            "CREATE PROPERTY MailMonitorConfig.tenantId              STRING",
            "CREATE PROPERTY MailMonitorConfig.name                  STRING",   // user-facing label; distinguishes multiple connections
            "CREATE PROPERTY MailMonitorConfig.provider              STRING",   // MS_GRAPH | GOOGLE | ZOHO | IMAP
            // Microsoft 365 / Graph (app-only) fields
            "CREATE PROPERTY MailMonitorConfig.directoryId           STRING",
            "CREATE PROPERTY MailMonitorConfig.clientId              STRING",
            "CREATE PROPERTY MailMonitorConfig.clientSecretRef       STRING",
            "CREATE PROPERTY MailMonitorConfig.graphBaseUrl          STRING",
            // IMAP fields (Google / Zoho / other)
            "CREATE PROPERTY MailMonitorConfig.imapHost              STRING",
            "CREATE PROPERTY MailMonitorConfig.imapPort              INTEGER",
            "CREATE PROPERTY MailMonitorConfig.imapSsl               BOOLEAN",
            "CREATE PROPERTY MailMonitorConfig.defaultScanWindowDays INTEGER",
            "CREATE PROPERTY MailMonitorConfig.enabled               BOOLEAN",
            // Custom scan cadence (shared ScheduleSpec JSON); "" = continuous.
            "CREATE PROPERTY MailMonitorConfig.scheduleJson          STRING",
            "CREATE PROPERTY MailMonitorConfig.lastScheduledScanAt   STRING",
            "CREATE PROPERTY MailMonitorConfig.createdAt             STRING",
            "CREATE PROPERTY MailMonitorConfig.updatedAt             STRING"
        )) {
            tryExec(prop);
        }
        // A tenant may now have several connections (e.g. M365 + Gmail, or two
        // M365 app registrations) — drop the old one-per-tenant unique index
        // before creating the replacement, since CREATE INDEX IF NOT EXISTS is a
        // no-op (silently keeping the old UNIQUE) when an index already exists on
        // the same property combination. ArcadeDB's DROP INDEX has no IF EXISTS
        // clause, so this goes through tryExec (idempotent: fails harmlessly once
        // the index is gone, exactly like the CREATE PROPERTY calls below).
        tryExec("DROP INDEX `MailMonitorConfig[tenantId]`");
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MailMonitorConfig (tenantId) NOTUNIQUE", Map.of());
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MailMonitorConfig (id) UNIQUE", Map.of());
    }

    private void createMonitoredMailbox() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE MonitoredMailbox IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY MonitoredMailbox.id               STRING",
            "CREATE PROPERTY MonitoredMailbox.tenantId         STRING",
            "CREATE PROPERTY MonitoredMailbox.mailConnectionId STRING",   // FK -> MailMonitorConfig.id
            "CREATE PROPERTY MonitoredMailbox.address          STRING",
            "CREATE PROPERTY MonitoredMailbox.displayName      STRING",
            "CREATE PROPERTY MonitoredMailbox.scanWindowDays   INTEGER",
            "CREATE PROPERTY MonitoredMailbox.scanMail         BOOLEAN",
            "CREATE PROPERTY MonitoredMailbox.scanCalendar     BOOLEAN",
            "CREATE PROPERTY MonitoredMailbox.secretRef        STRING",   // Infisical ref for IMAP app password (blank for M365)
            "CREATE PROPERTY MonitoredMailbox.ownerSub         STRING",   // JWT sub of the owner for self-service mailboxes ('' = tenant-wide/admin)
            "CREATE PROPERTY MonitoredMailbox.ownerEmail       STRING",
            "CREATE PROPERTY MonitoredMailbox.enabled          BOOLEAN",
            "CREATE PROPERTY MonitoredMailbox.lastScanAt       STRING",
            "CREATE PROPERTY MonitoredMailbox.lastScanStatus   STRING",
            "CREATE PROPERTY MonitoredMailbox.lastError        STRING",
            "CREATE PROPERTY MonitoredMailbox.createdBy        STRING",
            "CREATE PROPERTY MonitoredMailbox.createdAt        STRING"
        )) {
            tryExec(prop);
        }
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MonitoredMailbox (tenantId, address) UNIQUE", Map.of());
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MonitoredMailbox (tenantId) NOTUNIQUE", Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON MonitoredMailbox (tenantId, mailConnectionId) NOTUNIQUE", Map.of());
    }

    /**
     * Communication-channel connections (Slack / Microsoft Teams / …): one or
     * more per tenant. The workspace/team-level credential lives in Infisical;
     * ArcadeDB stores only {@code credentialRef}. {@code configJson} holds
     * non-secret settings (Teams app registration, base urls); {@code scheduleJson}
     * a shared ScheduleSpec ("" = continuous).
     */
    private void createChannelConnection() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE CommChannelConnection IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY CommChannelConnection.id                    STRING",
            "CREATE PROPERTY CommChannelConnection.tenantId              STRING",
            "CREATE PROPERTY CommChannelConnection.name                  STRING",   // user-facing label
            "CREATE PROPERTY CommChannelConnection.kind                  STRING",   // SLACK | MS_TEAMS | DISCORD | GOOGLE_CHAT
            "CREATE PROPERTY CommChannelConnection.configJson            STRING",   // non-secret settings JSON
            "CREATE PROPERTY CommChannelConnection.credentialRef         STRING",   // Infisical "path#field"
            "CREATE PROPERTY CommChannelConnection.defaultScanWindowDays INTEGER",
            "CREATE PROPERTY CommChannelConnection.enabled               BOOLEAN",
            "CREATE PROPERTY CommChannelConnection.scheduleJson          STRING",   // "" = continuous
            "CREATE PROPERTY CommChannelConnection.lastScheduledScanAt   STRING",
            "CREATE PROPERTY CommChannelConnection.createdAt             STRING",
            "CREATE PROPERTY CommChannelConnection.updatedAt             STRING"
        )) {
            tryExec(prop);
        }
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON CommChannelConnection (id) UNIQUE", Map.of());
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON CommChannelConnection (tenantId) NOTUNIQUE", Map.of());
    }

    /**
     * Monitored accounts within a channel connection — a channel/conversation to
     * ingest ({@code externalId}: a Slack channel id, or "teamId/channelId" for
     * Teams). Many per tenant, each tied to one CommChannelConnection.
     */
    private void createMonitoredChannelAccount() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE MonitoredChannelAccount IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY MonitoredChannelAccount.id                  STRING",
            "CREATE PROPERTY MonitoredChannelAccount.tenantId            STRING",
            "CREATE PROPERTY MonitoredChannelAccount.channelConnectionId STRING",   // FK -> CommChannelConnection.id
            "CREATE PROPERTY MonitoredChannelAccount.externalId          STRING",   // channel/conversation id
            "CREATE PROPERTY MonitoredChannelAccount.displayName         STRING",
            "CREATE PROPERTY MonitoredChannelAccount.scanWindowDays      INTEGER",
            "CREATE PROPERTY MonitoredChannelAccount.ownerSub            STRING",   // '' = tenant-wide
            "CREATE PROPERTY MonitoredChannelAccount.ownerEmail          STRING",
            "CREATE PROPERTY MonitoredChannelAccount.enabled             BOOLEAN",
            "CREATE PROPERTY MonitoredChannelAccount.lastScanAt          STRING",
            "CREATE PROPERTY MonitoredChannelAccount.lastScanStatus      STRING",
            "CREATE PROPERTY MonitoredChannelAccount.lastError           STRING",
            "CREATE PROPERTY MonitoredChannelAccount.createdBy           STRING",
            "CREATE PROPERTY MonitoredChannelAccount.createdAt           STRING"
        )) {
            tryExec(prop);
        }
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MonitoredChannelAccount (id) UNIQUE", Map.of());
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MonitoredChannelAccount (tenantId) NOTUNIQUE", Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON MonitoredChannelAccount (tenantId, channelConnectionId) NOTUNIQUE", Map.of());
    }

    private void createMailInsight() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE MailInsight IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY MailInsight.id                  STRING",
            "CREATE PROPERTY MailInsight.tenantId            STRING",
            "CREATE PROPERTY MailInsight.mailboxId           STRING",
            "CREATE PROPERTY MailInsight.ownerSub            STRING",   // '' = tenant-wide; else visible only to this user
            "CREATE PROPERTY MailInsight.kind                STRING",   // TASK | NOTIFICATION
            "CREATE PROPERTY MailInsight.source              STRING",   // EMAIL | MS_TEAMS | SLACK — communication channel this item was scanned from (see SourceChannel)
            "CREATE PROPERTY MailInsight.sourceType          STRING",   // EMAIL | EVENT | MEETING — item type WITHIN that channel
            "CREATE PROPERTY MailInsight.sourceRef           STRING",
            "CREATE PROPERTY MailInsight.dedupeKey           STRING",
            "CREATE PROPERTY MailInsight.title               STRING",
            "CREATE PROPERTY MailInsight.summary             STRING",
            "CREATE PROPERTY MailInsight.senderDisplay       STRING",
            "CREATE PROPERTY MailInsight.priority            STRING",   // CRITICAL|HIGH|MEDIUM|LOW
            "CREATE PROPERTY MailInsight.criticality         STRING",   // ALERT|WARNING|INFORMATION
            "CREATE PROPERTY MailInsight.score               INTEGER",
            "CREATE PROPERTY MailInsight.scoreBreakdown      STRING",   // JSON
            "CREATE PROPERTY MailInsight.rationale           STRING",
            "CREATE PROPERTY MailInsight.dueDate             STRING",
            "CREATE PROPERTY MailInsight.receivedAt          STRING",
            "CREATE PROPERTY MailInsight.status              STRING",   // OPEN|IN_PROGRESS|DONE|DISMISSED
            "CREATE PROPERTY MailInsight.completedAt         STRING",   // ISO-8601 when set DONE; '' otherwise
            "CREATE PROPERTY MailInsight.userPriorityOverride STRING",
            "CREATE PROPERTY MailInsight.userCriticalityOverride STRING",
            "CREATE PROPERTY MailInsight.reminderAt          STRING",
            "CREATE PROPERTY MailInsight.inferenceSource     STRING",   // RULES | LLM
            // Calendar/meeting fields (sourceType=MEETING) — back the mobile "Today's
            // meeting" card. Display-name only in attendeesJson; no email addresses.
            "CREATE PROPERTY MailInsight.meetingStartAt      STRING",   // ISO-8601
            "CREATE PROPERTY MailInsight.meetingEndAt        STRING",   // ISO-8601
            "CREATE PROPERTY MailInsight.attendeesJson       STRING",   // [{displayName,response}]
            "CREATE PROPERTY MailInsight.attendeeCount       INTEGER",
            "CREATE PROPERTY MailInsight.briefPointsJson     STRING",   // ["…","…"] AI brief bullets
            "CREATE PROPERTY MailInsight.locationText        STRING",
            "CREATE PROPERTY MailInsight.onlineJoinUrlRef    STRING",
            "CREATE PROPERTY MailInsight.organizerDisplay    STRING",
            "CREATE PROPERTY MailInsight.createdAt           STRING",
            "CREATE PROPERTY MailInsight.updatedAt           STRING"
        )) {
            tryExec(prop);
        }
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MailInsight (tenantId, dedupeKey) UNIQUE", Map.of());
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MailInsight (tenantId, kind, status) NOTUNIQUE", Map.of());
        // NOTE: deliberately NO index on (tenantId, reminderAt). The snooze filter
        // "(reminderAt IS NULL OR reminderAt = '' OR reminderAt <= :now)" combined
        // with this composite index triggers an ArcadeDB engine bug
        // (BufferUnderflowException / NegativeArraySizeException) that makes the
        // whole listTasks/listNotifications query throw — which the controller
        // swallows, leaving the Tasks/Notifications tabs silently empty even when
        // items exist. reminderAt is low-cardinality (null for most rows) so a
        // full scan over a tenant's items is cheap; no index is needed here.
        // Drop it on existing DBs with: DROP INDEX `MailInsight[tenantId,reminderAt]`
    }

    private void createScanRun() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE MailScanRun IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY MailScanRun.id           STRING",
            "CREATE PROPERTY MailScanRun.tenantId     STRING",
            "CREATE PROPERTY MailScanRun.mailboxId    STRING",
            "CREATE PROPERTY MailScanRun.startedAt    STRING",
            "CREATE PROPERTY MailScanRun.finishedAt   STRING",
            "CREATE PROPERTY MailScanRun.status       STRING",
            "CREATE PROPERTY MailScanRun.itemsCreated INTEGER",
            "CREATE PROPERTY MailScanRun.itemsUpdated INTEGER",
            "CREATE PROPERTY MailScanRun.error        STRING"
        )) {
            tryExec(prop);
        }
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MailScanRun (id) UNIQUE", Map.of());
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON MailScanRun (tenantId, startedAt) NOTUNIQUE", Map.of());
    }

    /**
     * Per-user action overlay for data-source items (incidents/alerts/etc.) which
     * live in their source systems and aren't MailInsight rows. Lets a user mark
     * them done / ignore / deprioritize in their own view without mutating the
     * source. itemRef = the synthetic data-source id (e.g. "ds-alert-123").
     */
    private void createItemOverride() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE MailItemOverride IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY MailItemOverride.id                   STRING",
            "CREATE PROPERTY MailItemOverride.tenantId             STRING",
            "CREATE PROPERTY MailItemOverride.ownerSub             STRING",
            "CREATE PROPERTY MailItemOverride.itemRef              STRING",
            "CREATE PROPERTY MailItemOverride.status               STRING",
            "CREATE PROPERTY MailItemOverride.completedAt          STRING",   // ISO-8601 when set DONE
            "CREATE PROPERTY MailItemOverride.priorityOverride     STRING",
            "CREATE PROPERTY MailItemOverride.criticalityOverride  STRING",
            "CREATE PROPERTY MailItemOverride.reminderAt           STRING",
            "CREATE PROPERTY MailItemOverride.updatedAt            STRING"
        )) {
            tryExec(prop);
        }
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON MailItemOverride (tenantId, ownerSub, itemRef) UNIQUE", Map.of());
    }

    private void tryExec(String sql) {
        try {
            arcade.commandNoResult(sql, Map.of());
        } catch (Exception e) {
            log.debug("DDL skipped (likely already exists): {} — {}", sql.trim(), e.getMessage());
        }
    }
}
