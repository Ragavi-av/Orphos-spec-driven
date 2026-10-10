package io.orphos.eip.mobilebff.component;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Creates the mobile-workspace document types in ArcadeDB on startup. Idempotent.
 *
 * These are personal app state (Scribbles captures + threads, push devices, priority
 * contacts, trips, notification preferences) — owner-scoped, NOT gov-classified. Each
 * row carries scoping columns (tenantId + ownerSub + createdAt) plus a {@code payload}
 * JSON string holding the full object, so the app's API contract is preserved verbatim
 * while the data becomes durable. See ontology/eip-mobile.owl for the thin OWL view.
 *
 * ArcadeDB DDL notes (same as mail-insight / doc-ingest):
 *   - CREATE DOCUMENT TYPE supports IF NOT EXISTS.
 *   - CREATE PROPERTY does NOT support IF NOT EXISTS — tolerate duplicate failures.
 *   - CREATE INDEX supports IF NOT EXISTS and requires the properties to exist first.
 *   - Timestamps are LONG epoch millis, never DATETIME (ISO 'Z' strings store as null).
 */
@Component
@Order(1)
@ConditionalOnProperty(value = "mobile.schema.bootstrap-enabled", havingValue = "true", matchIfMissing = true)
public class MobileSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(MobileSchemaBootstrap.class);

    private final MobileArcadeClient arcade;

    public MobileSchemaBootstrap(MobileArcadeClient arcade) {
        this.arcade = arcade;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Applying mobile-workspace schema to ArcadeDB...");
        try {
            // Backing the live endpoints today:
            createEnvelopeType("Capture", "captureType");   // Scribbles note
            createEnvelopeType("CaptureMessage", null);      // thread reply (scoped by captureId)
            createEnvelopeType("PushDevice", null);          // push registration
            // Forward-looking types from the approved data model (no endpoints yet):
            createEnvelopeType("CaptureShare", null);
            createEnvelopeType("CaptureFile", null);
            createEnvelopeType("PriorityContact", null);
            createEnvelopeType("Trip", null);
            createEnvelopeType("NotificationPreference", null);
            // Auto-inferred, persistent executive workspace (decision is the only
            // user-entered part). sourceId links a row back to the mail/decision it
            // was inferred from, so re-inference is idempotent.
            createEnvelopeType("Approval", "sourceId");
            createEnvelopeType("Travel", "sourceId");
            // Per-tenant AI-Admin configuration (status-dot thresholds + inference
            // rules). Tenant-scoped singleton: stored with ownerSub = CONFIG_SCOPE.
            createEnvelopeType("MobileConfig", null);
            // CaptureMessage is scoped by its parent capture, so add that index.
            arcade.commandNoResult(
                    "CREATE INDEX IF NOT EXISTS ON CaptureMessage (captureId) NOTUNIQUE", Map.of());
            log.info("Mobile-workspace schema ready.");
        } catch (Exception e) {
            log.error("Schema bootstrap failed — mobile workspace data will not persist: {}", e.getMessage(), e);
        }
    }

    /**
     * A durable "envelope" type: scoping columns + a JSON payload. {@code extraCol},
     * when non-null, adds one indexed discriminator column (e.g. Capture.captureType).
     */
    private void createEnvelopeType(String type, String extraCol) {
        arcade.commandNoResult("CREATE DOCUMENT TYPE " + type + " IF NOT EXISTS", Map.of());
        for (String col : List.of("id STRING", "tenantId STRING", "ownerSub STRING",
                "captureId STRING", "createdAt LONG", "payload STRING")) {
            tryExec("CREATE PROPERTY " + type + "." + col);
        }
        if (extraCol != null) {
            tryExec("CREATE PROPERTY " + type + "." + extraCol + " STRING");
        }
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON " + type + " (id) UNIQUE", Map.of());
        arcade.commandNoResult(
                "CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId, ownerSub) NOTUNIQUE", Map.of());
    }

    private void tryExec(String sql) {
        try {
            arcade.commandNoResult(sql, Map.of());
        } catch (Exception e) {
            log.debug("DDL skipped (likely already exists): {} — {}", sql.trim(), e.getMessage());
        }
    }
}
