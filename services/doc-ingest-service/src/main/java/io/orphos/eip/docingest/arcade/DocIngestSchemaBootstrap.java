package io.orphos.eip.docingest.arcade;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Creates DocIngestRecord and DocSearchHistory document types in ArcadeDB on startup.
 *
 * ArcadeDB notes:
 *   - CREATE DOCUMENT TYPE supports IF NOT EXISTS — idempotent.
 *   - CREATE PROPERTY does NOT support IF NOT EXISTS — silently ignored on duplicate.
 *   - CREATE INDEX supports IF NOT EXISTS and requires properties to exist first.
 */
@Component
@Order(1)
public class DocIngestSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(DocIngestSchemaBootstrap.class);

    private final DocArcadeClient arcade;

    /** Carries the connection binder's schema mode ({@code eip.connections.schema-mode}). */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    public DocIngestSchemaBootstrap(DocArcadeClient arcade) {
        this.arcade = arcade;
    }

    @Override
    public void run(ApplicationArguments args) {
        // EXISTING schema mode → the assigned database is operator-managed; do not run DDL.
        if (io.orphos.eip.core.dbregistry.SchemaModeGuard.isExisting(environment)) {
            log.info("Doc-ingest schema bootstrap skipped: assigned connection uses schemaMode=EXISTING "
                    + "(operator-managed schema) — no DDL applied.");
            return;
        }
        log.info("Applying doc-ingest schema to ArcadeDB...");
        try {
            createDocIngestRecord();
            createDocSearchHistory();
            createDocIngestSettings();
            createDocSourceConnection();
            log.info("Doc-ingest schema ready.");
        } catch (Exception e) {
            log.error("Schema bootstrap failed — doc-ingest metadata will not persist: {}", e.getMessage(), e);
        }
    }

    private void createDocIngestRecord() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE DocIngestRecord IF NOT EXISTS", Map.of());

        // Properties — silently skip if already exist (duplicate on re-run)
        for (String prop : List.of(
            "CREATE PROPERTY DocIngestRecord.documentId         STRING",
            "CREATE PROPERTY DocIngestRecord.tenantId           STRING",
            "CREATE PROPERTY DocIngestRecord.fileName           STRING",
            "CREATE PROPERTY DocIngestRecord.title              STRING",
            "CREATE PROPERTY DocIngestRecord.referenceNumber    STRING",
            "CREATE PROPERTY DocIngestRecord.documentType       STRING",
            "CREATE PROPERTY DocIngestRecord.source             STRING",
            "CREATE PROPERTY DocIngestRecord.status             STRING",
            "CREATE PROPERTY DocIngestRecord.chunksStored       INTEGER",
            "CREATE PROPERTY DocIngestRecord.charactersExtracted INTEGER",
            "CREATE PROPERTY DocIngestRecord.language           STRING",
            "CREATE PROPERTY DocIngestRecord.error              STRING",
            "CREATE PROPERTY DocIngestRecord.submittedAt        STRING",
            "CREATE PROPERTY DocIngestRecord.completedAt        STRING",
            "CREATE PROPERTY DocIngestRecord.durationMs         LONG",
            "CREATE PROPERTY DocIngestRecord.uploadedBy         STRING",
            "CREATE PROPERTY DocIngestRecord.fileSizeBytes      LONG",
            "CREATE PROPERTY DocIngestRecord.contentType        STRING",
            "CREATE PROPERTY DocIngestRecord.checksumSha256     STRING",
            "CREATE PROPERTY DocIngestRecord.storageBackend     STRING",
            "CREATE PROPERTY DocIngestRecord.storageLocation    STRING",
            "CREATE PROPERTY DocIngestRecord.category            STRING",
            "CREATE PROPERTY DocIngestRecord.department          STRING",
            "CREATE PROPERTY DocIngestRecord.folder              STRING",
            "CREATE PROPERTY DocIngestRecord.inputType           STRING",
            "CREATE PROPERTY DocIngestRecord.sourceChannel       STRING",
            "CREATE PROPERTY DocIngestRecord.ownerSub            STRING"
        )) {
            tryExec(prop);
        }

        // Indexes — idempotent via IF NOT EXISTS
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON DocIngestRecord (documentId) UNIQUE",   Map.of());
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON DocIngestRecord (tenantId) NOTUNIQUE",  Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON DocIngestRecord (tenantId, submittedAt) NOTUNIQUE", Map.of());
    }

    private void createDocSearchHistory() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE DocSearchHistory IF NOT EXISTS", Map.of());

        for (String prop : List.of(
            "CREATE PROPERTY DocSearchHistory.searchId     STRING",
            "CREATE PROPERTY DocSearchHistory.tenantId     STRING",
            "CREATE PROPERTY DocSearchHistory.userSub      STRING",
            "CREATE PROPERTY DocSearchHistory.query        STRING",
            "CREATE PROPERTY DocSearchHistory.resultCount  INTEGER",
            "CREATE PROPERTY DocSearchHistory.latencyMs    LONG",
            "CREATE PROPERTY DocSearchHistory.timestamp    STRING"
        )) {
            tryExec(prop);
        }

        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON DocSearchHistory (searchId) UNIQUE",    Map.of());
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON DocSearchHistory (tenantId) NOTUNIQUE", Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON DocSearchHistory (tenantId, timestamp) NOTUNIQUE", Map.of());
    }

    private void createDocIngestSettings() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE DocIngestSettings IF NOT EXISTS", Map.of());

        for (String prop : List.of(
            "CREATE PROPERTY DocIngestSettings.tenantId     STRING",
            "CREATE PROPERTY DocIngestSettings.settingsJson STRING",
            "CREATE PROPERTY DocIngestSettings.updatedAt    STRING",
            "CREATE PROPERTY DocIngestSettings.updatedBy    STRING"
        )) {
            tryExec(prop);
        }

        // One settings row per tenant — unique guarantees the UPDATE-then-INSERT
        // upsert in DocSettingsService can never create duplicates.
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON DocIngestSettings (tenantId) UNIQUE", Map.of());
    }

    private void createDocSourceConnection() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE DocSourceConnection IF NOT EXISTS", Map.of());

        for (String prop : List.of(
            "CREATE PROPERTY DocSourceConnection.id                STRING",
            "CREATE PROPERTY DocSourceConnection.tenantId          STRING",
            "CREATE PROPERTY DocSourceConnection.kind              STRING",
            "CREATE PROPERTY DocSourceConnection.displayName       STRING",
            "CREATE PROPERTY DocSourceConnection.configJson        STRING",
            "CREATE PROPERTY DocSourceConnection.credentialRef     STRING",
            "CREATE PROPERTY DocSourceConnection.defaultDepartment STRING",
            "CREATE PROPERTY DocSourceConnection.defaultCategory   STRING",
            "CREATE PROPERTY DocSourceConnection.defaultFolder     STRING",
            "CREATE PROPERTY DocSourceConnection.documentType      STRING",
            "CREATE PROPERTY DocSourceConnection.enabled           BOOLEAN",
            "CREATE PROPERTY DocSourceConnection.watermark         STRING",
            "CREATE PROPERTY DocSourceConnection.lastSyncAt        STRING",
            "CREATE PROPERTY DocSourceConnection.lastSyncStatus    STRING",
            "CREATE PROPERTY DocSourceConnection.lastSyncError     STRING",
            "CREATE PROPERTY DocSourceConnection.createdAt         STRING",
            "CREATE PROPERTY DocSourceConnection.updatedAt         STRING"
        )) {
            tryExec(prop);
        }

        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON DocSourceConnection (id) UNIQUE", Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON DocSourceConnection (tenantId) NOTUNIQUE", Map.of());
    }

    private void tryExec(String sql) {
        try {
            arcade.commandNoResult(sql, Map.of());
        } catch (Exception e) {
            log.debug("DDL skipped (likely already exists): {} — {}", sql.trim(), e.getMessage());
        }
    }
}
