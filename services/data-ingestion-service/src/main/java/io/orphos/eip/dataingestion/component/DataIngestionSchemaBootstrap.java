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
 * Creates the data-ingestion document types in ArcadeDB on startup. Idempotent.
 *
 * ArcadeDB DDL notes (same as mail-insight / doc-ingest):
 *   - CREATE DOCUMENT TYPE supports IF NOT EXISTS.
 *   - CREATE PROPERTY does NOT support IF NOT EXISTS — tolerate duplicate failures.
 *   - CREATE INDEX supports IF NOT EXISTS and requires the properties to exist first.
 *
 * Types:
 *   IngestDataSource — one per (tenant, name): a configured enterprise data source
 *                      (JDBC database or REST API). Credentials live in Infisical;
 *                      only the vault ref is stored here.
 */
@Component
@Order(1)
public class DataIngestionSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(DataIngestionSchemaBootstrap.class);

    private final ArcadeDbTemplate arcade;

    /** Carries the connection binder's schema mode ({@code eip.connections.schema-mode}). */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    public DataIngestionSchemaBootstrap(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    @Override
    public void run(ApplicationArguments args) {
        // EXISTING schema mode → the assigned database is operator-managed; do not run DDL.
        if (io.orphos.eip.core.dbregistry.SchemaModeGuard.isExisting(environment)) {
            log.info("Data-ingestion schema bootstrap skipped: assigned connection uses schemaMode=EXISTING "
                    + "(operator-managed schema) — no DDL applied.");
            return;
        }
        log.info("Applying data-ingestion schema to ArcadeDB...");
        try {
            createDataSource();
            createMapping();
            createJob();
            createJobRun();
            log.info("Data-ingestion schema ready.");
        } catch (Exception e) {
            log.error("Schema bootstrap failed — data-ingestion config will not persist: {}", e.getMessage(), e);
        }
    }

    private void createMapping() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE IngestMapping IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY IngestMapping.id                STRING",
            "CREATE PROPERTY IngestMapping.tenantId          STRING",
            "CREATE PROPERTY IngestMapping.name              STRING",   // unique per tenant, reusable across jobs
            "CREATE PROPERTY IngestMapping.dataSourceId      STRING",
            "CREATE PROPERTY IngestMapping.sourceSchema      STRING",
            "CREATE PROPERTY IngestMapping.sourceTable       STRING",
            "CREATE PROPERTY IngestMapping.targetClass       STRING",   // ArcadeDB type the rows load into
            "CREATE PROPERTY IngestMapping.targetKind        STRING",   // DOCUMENT | VERTEX
            "CREATE PROPERTY IngestMapping.keyTarget         STRING",   // upsert-key target property (= externalId for VERTEX)
            "CREATE PROPERTY IngestMapping.incrementalColumn STRING",   // source watermark column (req 6.2)
            "CREATE PROPERTY IngestMapping.mappingsJson      STRING",   // List<ColumnMapping> as JSON
            "CREATE PROPERTY IngestMapping.edgesJson         STRING",   // List<EdgeMapping> as JSON (VERTEX targets)
            "CREATE PROPERTY IngestMapping.warningsJson      STRING",   // validation warnings at save time
            "CREATE PROPERTY IngestMapping.enabled           BOOLEAN",
            "CREATE PROPERTY IngestMapping.createdAt         STRING",
            "CREATE PROPERTY IngestMapping.updatedAt         STRING")) {
            tolerate(prop);
        }
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestMapping (id) UNIQUE", Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestMapping (tenantId, name) UNIQUE", Map.of());
    }

    private void createDataSource() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE IngestDataSource IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY IngestDataSource.id             STRING",
            "CREATE PROPERTY IngestDataSource.tenantId       STRING",
            "CREATE PROPERTY IngestDataSource.name           STRING",   // user-facing label, unique per tenant
            "CREATE PROPERTY IngestDataSource.kind           STRING",   // POSTGRESQL | SQLSERVER | GENERIC_JDBC | REST | REST_OPENAPI | ZOHO_ERP
            "CREATE PROPERTY IngestDataSource.jdbcUrl        STRING",   // JDBC kinds
            "CREATE PROPERTY IngestDataSource.driverClass    STRING",   // GENERIC_JDBC only (covers ODBC-JDBC bridge drivers)
            "CREATE PROPERTY IngestDataSource.username       STRING",
            "CREATE PROPERTY IngestDataSource.credentialRef  STRING",   // vault:<path>#<field> — never the raw secret
            "CREATE PROPERTY IngestDataSource.baseUrl        STRING",   // HTTP kinds
            "CREATE PROPERTY IngestDataSource.authType       STRING",   // NONE | BASIC | BEARER | API_KEY | OAUTH2
            "CREATE PROPERTY IngestDataSource.authHeaderName STRING",   // API_KEY: custom header carrying the key
            "CREATE PROPERTY IngestDataSource.testPath       STRING",   // REST: probe path used by test-connection
            "CREATE PROPERTY IngestDataSource.configJson     STRING",   // ERP/OpenAPI config (objects, pagination, OAuth params) — no secrets
            "CREATE PROPERTY IngestDataSource.maxPoolSize    INTEGER",
            "CREATE PROPERTY IngestDataSource.enabled        BOOLEAN",
            "CREATE PROPERTY IngestDataSource.lastTestAt     STRING",
            "CREATE PROPERTY IngestDataSource.lastTestStatus STRING",   // NEVER_TESTED | SUCCESS | FAILED
            "CREATE PROPERTY IngestDataSource.lastTestError  STRING",
            "CREATE PROPERTY IngestDataSource.createdAt      STRING",
            "CREATE PROPERTY IngestDataSource.updatedAt      STRING")) {
            tolerate(prop);
        }
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestDataSource (id) UNIQUE", Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestDataSource (tenantId, name) UNIQUE", Map.of());
    }

    private void createJob() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE IngestJob IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY IngestJob.id             STRING",
            "CREATE PROPERTY IngestJob.tenantId       STRING",
            "CREATE PROPERTY IngestJob.name           STRING",   // unique per tenant
            "CREATE PROPERTY IngestJob.mappingId      STRING",   // the IngestMapping this job executes
            "CREATE PROPERTY IngestJob.scheduleType   STRING",   // NONE | CRON | ONE_TIME (req 4.1/4.2)
            "CREATE PROPERTY IngestJob.cron           STRING",   // Spring six-field cron (CRON only)
            "CREATE PROPERTY IngestJob.runAt          STRING",   // ISO instant (ONE_TIME only)
            "CREATE PROPERTY IngestJob.loadMode       STRING",   // FULL | INCREMENTAL (req 6.2/6.3)
            "CREATE PROPERTY IngestJob.mergeStrategy  STRING",   // UPSERT | INSERT (req 6.4)
            "CREATE PROPERTY IngestJob.batchSize      INTEGER",  // 0 = service default (req 6.7)
            "CREATE PROPERTY IngestJob.maxRetries     INTEGER",  // -1 = service default (req 4.6)
            "CREATE PROPERTY IngestJob.dependsOnJobId STRING",   // triggered after parent succeeds (req 4.5)
            "CREATE PROPERTY IngestJob.enabled        BOOLEAN",
            "CREATE PROPERTY IngestJob.paused         BOOLEAN",  // req 4.8 — skipped, config kept
            "CREATE PROPERTY IngestJob.watermark      STRING",   // raw incremental high-water mark (req 6.2)
            "CREATE PROPERTY IngestJob.nextRunAt      STRING",   // ISO instant; '' = nothing scheduled
            "CREATE PROPERTY IngestJob.createdAt      STRING",
            "CREATE PROPERTY IngestJob.updatedAt      STRING")) {
            tolerate(prop);
        }
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestJob (id) UNIQUE", Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestJob (tenantId, name) UNIQUE", Map.of());
    }

    private void createJobRun() {
        arcade.commandNoResult("CREATE DOCUMENT TYPE IngestJobRun IF NOT EXISTS", Map.of());
        for (String prop : List.of(
            "CREATE PROPERTY IngestJobRun.id              STRING",
            "CREATE PROPERTY IngestJobRun.tenantId        STRING",
            "CREATE PROPERTY IngestJobRun.jobId           STRING",
            "CREATE PROPERTY IngestJobRun.jobName         STRING",   // denormalised for the audit trail (req 6.8)
            "CREATE PROPERTY IngestJobRun.attempt         INTEGER",  // 0 = first execution; retries count up (req 4.6)
            "CREATE PROPERTY IngestJobRun.status          STRING",   // QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELLED
            "CREATE PROPERTY IngestJobRun.triggeredBy     STRING",   // SCHEDULE | MANUAL | RETRY | DEPENDENCY
            "CREATE PROPERTY IngestJobRun.nextRetryAt     STRING",   // non-blank only on queued retries (req 4.6)
            "CREATE PROPERTY IngestJobRun.startedAt       STRING",
            "CREATE PROPERTY IngestJobRun.finishedAt      STRING",
            "CREATE PROPERTY IngestJobRun.recordsRead     LONG",     // req 4.4 — execution counters
            "CREATE PROPERTY IngestJobRun.recordsWritten  LONG",
            "CREATE PROPERTY IngestJobRun.recordsFailed   LONG",
            "CREATE PROPERTY IngestJobRun.totalRecords    LONG",     // -1 = unknown
            "CREATE PROPERTY IngestJobRun.progressPercent INTEGER",  // -1 = unknown (req 5.4)
            "CREATE PROPERTY IngestJobRun.error           STRING",
            "CREATE PROPERTY IngestJobRun.createdAt       STRING")) {
            tolerate(prop);
        }
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestJobRun (id) UNIQUE", Map.of());
        arcade.commandNoResult(
            "CREATE INDEX IF NOT EXISTS ON IngestJobRun (tenantId, jobId) NOTUNIQUE", Map.of());
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
