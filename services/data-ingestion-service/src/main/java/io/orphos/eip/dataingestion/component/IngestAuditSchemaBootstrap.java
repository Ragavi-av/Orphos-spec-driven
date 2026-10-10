package io.orphos.eip.dataingestion.component;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Creates the security &amp; access-control document types in ArcadeDB on
 * startup (requirement 8). Idempotent; runs AFTER
 * {@code DataIngestionSchemaBootstrap} (order 1) because it also extends the
 * {@code IngestDataSource} type with classification properties (req 8.6).
 *
 * ArcadeDB DDL notes (same as the order-1 bootstrap):
 *   - CREATE DOCUMENT TYPE supports IF NOT EXISTS.
 *   - CREATE PROPERTY does NOT support IF NOT EXISTS — tolerate duplicate failures.
 *   - CREATE INDEX supports IF NOT EXISTS and requires the properties to exist first.
 *
 * Types:
 *   IngestAuditLog — one row per mutating admin action (req 8.4): who did what
 *                    when. {@code detail} is a short method+path summary and
 *                    never contains credentials or row data.
 */
@Component
@Order(2)
public class IngestAuditSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(IngestAuditSchemaBootstrap.class);

    private final ArcadeDbTemplate arcade;

    /** Carries the connection binder's schema mode ({@code eip.connections.schema-mode}). */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    public IngestAuditSchemaBootstrap(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    @Override
    public void run(ApplicationArguments args) {
        // EXISTING schema mode → the assigned database is operator-managed; do not run DDL.
        if (io.orphos.eip.core.dbregistry.SchemaModeGuard.isExisting(environment)) {
            log.info("Data-ingestion audit schema bootstrap skipped: assigned connection uses schemaMode=EXISTING "
                    + "(operator-managed schema) — no DDL applied.");
            return;
        }
        log.info("Applying data-ingestion audit/classification schema to ArcadeDB...");
        try {
            createAuditLog();
            extendDataSourceWithClassification();
            log.info("Data-ingestion audit/classification schema ready.");
        } catch (Exception e) {
            log.error("Audit schema bootstrap failed — audit trail will not persist: {}", e.getMessage(), e);
        }
    }

    private void createAuditLog() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE IngestAuditLog IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY IngestAuditLog.id       STRING",
            "CREATE PROPERTY IngestAuditLog.tenantId STRING",
            "CREATE PROPERTY IngestAuditLog.actor    STRING",   // JWT preferred_username (fallback: sub)
            "CREATE PROPERTY IngestAuditLog.action   STRING",   // DATASOURCE_CREATED | MAPPING_UPDATED | JOB_TRIGGERED | ...
            "CREATE PROPERTY IngestAuditLog.detail   STRING",   // short method+path summary — NEVER credentials
            "CREATE PROPERTY IngestAuditLog.at       STRING")) { // ISO instant — lexicographic order == chronological
            tolerate(prop);
        }
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestAuditLog (id) UNIQUE", Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestAuditLog (tenantId, at) NOTUNIQUE", Map.of());
    }

    /**
     * Data-classification tags on the existing IngestDataSource type
     * (req 8.6). Property-only extension — the type itself is owned by
     * {@code DataIngestionSchemaBootstrap}, which has already run.
     */
    private void extendDataSourceWithClassification() {
        for (String prop : List.of(
            "CREATE PROPERTY IngestDataSource.classification     STRING",  // PUBLIC | INTERNAL | CONFIDENTIAL | RESTRICTED
            "CREATE PROPERTY IngestDataSource.classificationTags LIST")) { // free-form labels (e.g. pii, gdpr, finance)
            tolerate(prop);
        }
    }

    private void tolerate(String ddl) {
        try {
            arcade.commandNoResult(ddl, Map.of());
        } catch (Exception e) {
            // CREATE PROPERTY has no IF NOT EXISTS — duplicate-property errors are expected on restart.
            log.debug("DDL skipped ({}): {}", e.getMessage(), ddl);
        }
    }
}
