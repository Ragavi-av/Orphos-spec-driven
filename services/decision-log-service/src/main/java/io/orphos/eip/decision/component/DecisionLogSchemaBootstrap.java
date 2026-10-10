package io.orphos.eip.decision.component;

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
 * Creates the Decision Log RAG document types in ArcadeDB on startup. Idempotent.
 *
 * ArcadeDB DDL notes (same as data-ingestion / mail-insight / doc-ingest):
 *   - CREATE DOCUMENT TYPE supports IF NOT EXISTS.
 *   - CREATE PROPERTY does NOT support IF NOT EXISTS — tolerate duplicate failures.
 *   - CREATE INDEX supports IF NOT EXISTS and requires the properties to exist first.
 *
 * Backward compatibility (Req 9.1): the {@code Decision} type predates this
 * bootstrap — it was created implicitly by the original controller's INSERT and
 * may hold live rows. {@code CREATE DOCUMENT TYPE ... IF NOT EXISTS} keeps those
 * rows; the new properties are additive and legacy rows are lazily upgraded by
 * DecisionMigrationService (schemaVersion watermark).
 *
 * Types:
 *   Decision              — one organizational decision (manual, imported, or AI-detected)
 *   DecisionArea          — hierarchical department / functional-area taxonomy (Req 1.1)
 *   DecisionStatusEvent   — append-only status/implementation audit trail (Req 10)
 *   DecisionCorrelation   — links a decision to entities in other systems (Req 11)
 *   DecisionCorrelationRule — tenant-defined custom correlation rules (Req 11.4)
 *   DecisionAnnotation    — cached AI-copilot annotations, one per (decision, role) (Req 7)
 *   DecisionSyncWatermark — per-source high-water mark for the RAG sync sweep (Req 2.5)
 */
@Component
@Order(1)
public class DecisionLogSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(DecisionLogSchemaBootstrap.class);

    private final ArcadeDbTemplate arcade;

    /** Carries the connection binder's schema mode ({@code eip.connections.schema-mode}). */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    public DecisionLogSchemaBootstrap(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    @Override
    public void run(ApplicationArguments args) {
        // EXISTING schema mode → the assigned database is operator-managed; do not run DDL.
        if (io.orphos.eip.core.dbregistry.SchemaModeGuard.isExisting(environment)) {
            log.info("Decision-log schema bootstrap skipped: assigned connection uses schemaMode=EXISTING "
                    + "(operator-managed schema) — no DDL applied.");
            return;
        }
        log.info("Applying decision-log schema to ArcadeDB...");
        try {
            // Every statement is individually tolerated: a transient schema-lock
            // failure on one DDL (ArcadeDB serializes schema changes and can 500
            // under rapid-fire DDL) must not abort the rest — a later restart or
            // the next statement retries what's missing.
            createDecision();
            createDecisionArea();
            createStatusEvent();
            createCorrelation();
            createCorrelationRule();
            createAnnotation();
            createSyncWatermark();
            log.info("Decision-log schema ready.");
        } catch (Exception e) {
            log.error("Schema bootstrap failed — decision-log data will not persist: {}", e.getMessage(), e);
        }
    }

    private void createDecision() {
        // init-arcadedb-schema.bat pre-creates Decision as a VERTEX type; ArcadeDB
        // 500s on CREATE DOCUMENT TYPE for an existing name of a different kind
        // even with IF NOT EXISTS. Properties/indexes below apply to either kind,
        // so tolerate the clash (fresh DBs still get the document type created).
        tolerate("CREATE DOCUMENT TYPE Decision IF NOT EXISTS");
        for (String prop : List.of(
            // ---- legacy fields (pre-RAG rows carry only these) ----
            "CREATE PROPERTY Decision.id                   STRING",
            "CREATE PROPERTY Decision.tenantId             STRING",
            "CREATE PROPERTY Decision.title                STRING",
            "CREATE PROPERTY Decision.status               STRING",   // DETECTED | PENDING | APPROVED | REJECTED
            "CREATE PROPERTY Decision.domain               STRING",
            "CREATE PROPERTY Decision.rationale            STRING",
            "CREATE PROPERTY Decision.decidedBy            STRING",
            "CREATE PROPERTY Decision.decidedAt            STRING",
            "CREATE PROPERTY Decision.linkedIncident       STRING",
            "CREATE PROPERTY Decision.linkedBudget         STRING",
            // ---- core data model (Req 1) ----
            "CREATE PROPERTY Decision.description          STRING",
            "CREATE PROPERTY Decision.decisionArea         STRING",   // leaf of the DecisionArea hierarchy
            "CREATE PROPERTY Decision.involvedPeopleJson   STRING",   // [{name,role,participation}] (Req 1.2)
            "CREATE PROPERTY Decision.affectedAreasJson    STRING",   // [{kind:DEPARTMENT|PERSON|POLICY,name}] (Req 1.3)
            "CREATE PROPERTY Decision.priorityLevel        STRING",   // CRITICAL|HIGH|MEDIUM|LOW (Req 1.4)
            "CREATE PROPERTY Decision.priorityScore        DOUBLE",   // 0-100 criticality score
            "CREATE PROPERTY Decision.tagsJson             STRING",
            // ---- AI detection & classification (Req 3) ----
            "CREATE PROPERTY Decision.detectionSource      STRING",   // MANUAL|COMMUNICATION_RAG|DOCUMENT_RAG|DATA_INGESTION_RAG|IMPORTED
            "CREATE PROPERTY Decision.sourceRef            STRING",   // id of the source item in the origin RAG system
            "CREATE PROPERTY Decision.confidenceScore      DOUBLE",   // 0-1 detection confidence
            "CREATE PROPERTY Decision.classificationType   STRING",   // STRATEGIC|OPERATIONAL|TACTICAL|POLICY|FINANCIAL|PERSONNEL
            "CREATE PROPERTY Decision.strategicImportance  STRING",   // HIGH|MEDIUM|LOW
            // ---- impact analysis (Req 4) ----
            "CREATE PROPERTY Decision.impactSummary        STRING",
            "CREATE PROPERTY Decision.predictedImpactJson  STRING",   // full ML/LLM prediction payload
            "CREATE PROPERTY Decision.actualOutcome        STRING",   // recorded for continuous learning (Req 4.5)
            "CREATE PROPERTY Decision.outcomeRecordedAt    STRING",
            // ---- real-time tracking (Req 10) ----
            "CREATE PROPERTY Decision.implementationStatus STRING",   // NOT_STARTED|IN_PROGRESS|ON_HOLD|COMPLETED
            "CREATE PROPERTY Decision.dueDate              STRING",
            "CREATE PROPERTY Decision.milestonesJson       STRING",   // [{name,dueDate,status}]
            "CREATE PROPERTY Decision.escalated            BOOLEAN",
            "CREATE PROPERTY Decision.escalatedAt          STRING",
            // ---- bookkeeping ----
            "CREATE PROPERTY Decision.embeddingId          STRING",   // Qdrant point id for this decision's vector
            "CREATE PROPERTY Decision.schemaVersion        INTEGER",  // 2 = RAG schema; null/absent = legacy row
            "CREATE PROPERTY Decision.createdAt            STRING",
            "CREATE PROPERTY Decision.updatedAt            STRING")) {
            tolerate(prop);
        }
        // Legacy rows could theoretically hold duplicate ids (millis-based generator),
        // so a failed UNIQUE build must not kill the boot — tolerate() it.
        tolerate("CREATE INDEX IF NOT EXISTS ON Decision (id) UNIQUE");
        tolerate("CREATE INDEX IF NOT EXISTS ON Decision (tenantId, decidedAt) NOTUNIQUE");
        tolerate("CREATE INDEX IF NOT EXISTS ON Decision (tenantId, sourceRef) NOTUNIQUE");
    }

    private void createDecisionArea() {
        tolerate("CREATE DOCUMENT TYPE DecisionArea IF NOT EXISTS");
        for (String prop : List.of(
            "CREATE PROPERTY DecisionArea.id          STRING",
            "CREATE PROPERTY DecisionArea.tenantId    STRING",
            "CREATE PROPERTY DecisionArea.name        STRING",
            "CREATE PROPERTY DecisionArea.parentId    STRING",   // '' = top-level department (hierarchy, Req 1.1)
            "CREATE PROPERTY DecisionArea.description STRING",
            "CREATE PROPERTY DecisionArea.createdAt   STRING")) {
            tolerate(prop);
        }
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionArea (id) UNIQUE");
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionArea (tenantId, name) UNIQUE");
    }

    private void createStatusEvent() {
        tolerate("CREATE DOCUMENT TYPE DecisionStatusEvent IF NOT EXISTS");
        for (String prop : List.of(
            "CREATE PROPERTY DecisionStatusEvent.id         STRING",
            "CREATE PROPERTY DecisionStatusEvent.tenantId   STRING",
            "CREATE PROPERTY DecisionStatusEvent.decisionId STRING",
            "CREATE PROPERTY DecisionStatusEvent.kind       STRING",   // STATUS|IMPLEMENTATION|MILESTONE|ESCALATION|OUTCOME
            "CREATE PROPERTY DecisionStatusEvent.fromValue  STRING",
            "CREATE PROPERTY DecisionStatusEvent.toValue    STRING",
            "CREATE PROPERTY DecisionStatusEvent.note       STRING",
            "CREATE PROPERTY DecisionStatusEvent.changedBy  STRING",   // display name / 'system' — never raw ids
            "CREATE PROPERTY DecisionStatusEvent.source     STRING",   // UI|SYSTEM|EXTERNAL (Req 10.4)
            "CREATE PROPERTY DecisionStatusEvent.occurredAt STRING")) {
            tolerate(prop);
        }
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionStatusEvent (id) UNIQUE");
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionStatusEvent (tenantId, decisionId) NOTUNIQUE");
    }

    private void createCorrelation() {
        tolerate("CREATE DOCUMENT TYPE DecisionCorrelation IF NOT EXISTS");
        for (String prop : List.of(
            "CREATE PROPERTY DecisionCorrelation.id          STRING",
            "CREATE PROPERTY DecisionCorrelation.tenantId    STRING",
            "CREATE PROPERTY DecisionCorrelation.decisionId  STRING",
            "CREATE PROPERTY DecisionCorrelation.targetType  STRING",   // Incident|Alert|BudgetItem|Project|KeyResult|SLAMetric|Decision
            "CREATE PROPERTY DecisionCorrelation.targetId    STRING",
            "CREATE PROPERTY DecisionCorrelation.targetTitle STRING",
            "CREATE PROPERTY DecisionCorrelation.relation    STRING",   // RELATED|IMPACTS|CAUSED_BY|METRIC
            "CREATE PROPERTY DecisionCorrelation.confidence  DOUBLE",   // 0-1 (Req 11.3)
            "CREATE PROPERTY DecisionCorrelation.source      STRING",   // AUTO|RULE|SEMANTIC|MANUAL
            "CREATE PROPERTY DecisionCorrelation.ruleId      STRING",
            "CREATE PROPERTY DecisionCorrelation.createdAt   STRING")) {
            tolerate(prop);
        }
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionCorrelation (id) UNIQUE");
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionCorrelation (tenantId, decisionId) NOTUNIQUE");
    }

    private void createCorrelationRule() {
        tolerate("CREATE DOCUMENT TYPE DecisionCorrelationRule IF NOT EXISTS");
        for (String prop : List.of(
            "CREATE PROPERTY DecisionCorrelationRule.id             STRING",
            "CREATE PROPERTY DecisionCorrelationRule.tenantId       STRING",
            "CREATE PROPERTY DecisionCorrelationRule.name           STRING",
            "CREATE PROPERTY DecisionCorrelationRule.targetType     STRING",
            "CREATE PROPERTY DecisionCorrelationRule.keywordPattern STRING",   // case-insensitive regex on decision text
            "CREATE PROPERTY DecisionCorrelationRule.relation       STRING",
            "CREATE PROPERTY DecisionCorrelationRule.confidence     DOUBLE",
            "CREATE PROPERTY DecisionCorrelationRule.enabled        BOOLEAN",
            "CREATE PROPERTY DecisionCorrelationRule.createdAt      STRING",
            "CREATE PROPERTY DecisionCorrelationRule.updatedAt      STRING")) {
            tolerate(prop);
        }
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionCorrelationRule (id) UNIQUE");
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionCorrelationRule (tenantId, name) UNIQUE");
    }

    private void createAnnotation() {
        tolerate("CREATE DOCUMENT TYPE DecisionAnnotation IF NOT EXISTS");
        for (String prop : List.of(
            "CREATE PROPERTY DecisionAnnotation.id             STRING",
            "CREATE PROPERTY DecisionAnnotation.tenantId       STRING",
            "CREATE PROPERTY DecisionAnnotation.decisionId     STRING",
            "CREATE PROPERTY DecisionAnnotation.audienceRole   STRING",   // executive|admin|user — adapts content (Req 7.5)
            "CREATE PROPERTY DecisionAnnotation.annotationJson STRING",   // {executiveSummary,keyImpacts,recommendations,...}
            "CREATE PROPERTY DecisionAnnotation.model          STRING",   // provider that produced it, or 'template'
            "CREATE PROPERTY DecisionAnnotation.createdAt      STRING")) {
            tolerate(prop);
        }
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionAnnotation (id) UNIQUE");
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionAnnotation (tenantId, decisionId, audienceRole) UNIQUE");
    }

    private void createSyncWatermark() {
        tolerate("CREATE DOCUMENT TYPE DecisionSyncWatermark IF NOT EXISTS");
        for (String prop : List.of(
            "CREATE PROPERTY DecisionSyncWatermark.tenantId   STRING",
            "CREATE PROPERTY DecisionSyncWatermark.source     STRING",   // COMMUNICATION_RAG|DOCUMENT_RAG|MIGRATION
            "CREATE PROPERTY DecisionSyncWatermark.lastSyncAt STRING",   // high-water mark (source item createdAt/completedAt)
            "CREATE PROPERTY DecisionSyncWatermark.updatedAt  STRING")) {
            tolerate(prop);
        }
        tolerate("CREATE INDEX IF NOT EXISTS ON DecisionSyncWatermark (tenantId, source) UNIQUE");
    }

    /**
     * Runs one DDL statement, never propagating failure. Expected re-run noise
     * (duplicate property/type) logs at DEBUG; anything else at WARN so genuine
     * schema problems stay visible without aborting the remaining statements.
     */
    private void tolerate(String ddl) {
        try {
            arcade.commandNoResult(ddl, Map.of());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("already exist") || msg.contains("duplicated") || msg.contains("Duplicate")) {
                log.debug("DDL tolerated ({}): {}", msg, ddl);
            } else {
                log.warn("DDL failed, continuing ({}): {}", msg, ddl);
            }
        }
    }
}
