package io.orphos.eip.ingest.component;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.core.dbregistry.SchemaModeGuard;
import io.orphos.eip.ingest.repository.OkgGraphRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Creates the Enterprise ontology's vertex and edge types in ArcadeDB on
 * startup — the graph mirror of {@code eip-enterprise.owl} (loaded by
 * okg-reasoning-service). Models the enterprise itself: the company, its
 * mission/vision/values, functional areas, industries, clients and
 * engagements, key executives, organisational structure, branches and
 * locations.
 *
 * <p>Naming: every type is prefixed {@code Ent} so it never collides with the
 * work-item ontology ({@code OkgProject/OkgIssue/OkgPerson}) that shares the
 * same {@code orphos} database. Edge types use the SCREAMING_SNAKE convention
 * already established by {@code init-arcadedb-schema.bat}.</p>
 *
 * <p>Every vertex carries {@code (id, tenantId, externalId)}: {@code id} is the
 * platform-minted surrogate key (unique), while {@code (tenantId, externalId)}
 * is the natural upsert key a connector matches on — the same identity model
 * {@code OkgGraphRepository} uses for the work-item graph.</p>
 *
 * ArcadeDB DDL notes (see also {@code DocIngestSchemaBootstrap}):
 *   - CREATE VERTEX/EDGE TYPE supports IF NOT EXISTS — idempotent.
 *   - CREATE PROPERTY does NOT support IF NOT EXISTS — wrapped in {@link #tryExec}.
 *   - CREATE INDEX supports IF NOT EXISTS and requires the properties to exist first.
 */
@Component
@Order(1)
public class EnterpriseSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(EnterpriseSchemaBootstrap.class);

    private final ArcadeDbTemplate arcade;

    /** Carries the connection binder's schema mode ({@code eip.connections.schema-mode}). */
    @Autowired
    private Environment environment;

    public EnterpriseSchemaBootstrap(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    @Override
    public void run(ApplicationArguments args) {
        // EXISTING schema mode → the assigned database is operator-managed; do not run DDL.
        if (SchemaModeGuard.isExisting(environment)) {
            log.info("Enterprise schema bootstrap skipped: assigned connection uses schemaMode=EXISTING "
                    + "(operator-managed schema) — no DDL applied.");
            return;
        }
        log.info("Applying enterprise ontology schema to ArcadeDB...");
        try {
            createVertexTypes();
            createEdgeTypes();
            createProperties();
            createIndexes();
            log.info("Enterprise ontology schema ready.");
        } catch (Exception e) {
            log.error("Schema bootstrap failed — enterprise ontology graph will not persist: {}", e.getMessage(), e);
        }
    }

    // ── Vertex types ────────────────────────────────────────────────────────

    private void createVertexTypes() {
        // EntPerson before EntExecutive: the latter EXTENDS the former (mirrors
        // the ent:Executive rdfs:subClassOf ent:Person relation in the OWL).
        for (String type : List.of(
                "EntCompany", "EntMission", "EntVision", "EntCoreValue",
                "EntFunctionalArea", "EntIndustry", "EntClient", "EntEngagement",
                "EntPerson", "EntOrgUnit", "EntBranch", "EntLocation")) {
            arcade.commandNoResult("CREATE VERTEX TYPE " + type + " IF NOT EXISTS", Map.of());
        }
        arcade.commandNoResult("CREATE VERTEX TYPE EntExecutive IF NOT EXISTS EXTENDS EntPerson", Map.of());
    }

    // ── Edge types ──────────────────────────────────────────────────────────

    private void createEdgeTypes() {
        for (String type : List.of(
                "HAS_MISSION", "HAS_VISION", "UPHOLDS_VALUE", "OPERATES_IN_AREA",
                "SERVES_INDUSTRY", "HAS_CLIENT", "HAS_ENGAGEMENT", "HAS_ORG_UNIT",
                "HAS_BRANCH", "HEADQUARTERED_AT", "LED_BY", "SUB_UNIT_OF",
                "HEADED_BY", "RESPONSIBLE_FOR", "MEMBER_OF", "REPORTS_TO",
                "LOCATED_AT", "ENGAGEMENT_WITH", "ENGAGEMENT_IN_INDUSTRY",
                "DELIVERED_BY", "CLIENT_IN_INDUSTRY")) {
            arcade.commandNoResult("CREATE EDGE TYPE " + type + " IF NOT EXISTS", Map.of());
        }
    }

    // ── Properties ──────────────────────────────────────────────────────────

    private void createProperties() {
        // Shared identity/label triple on every vertex type.
        for (String type : List.of(
                "EntCompany", "EntMission", "EntVision", "EntCoreValue",
                "EntFunctionalArea", "EntIndustry", "EntClient", "EntEngagement",
                "EntPerson", "EntExecutive", "EntOrgUnit", "EntBranch", "EntLocation")) {
            tryExec("CREATE PROPERTY " + type + ".id         STRING");
            tryExec("CREATE PROPERTY " + type + ".tenantId   STRING");
            tryExec("CREATE PROPERTY " + type + ".externalId STRING");
            tryExec("CREATE PROPERTY " + type + ".source     STRING");
            tryExec("CREATE PROPERTY " + type + ".createdAt  STRING");
            tryExec("CREATE PROPERTY " + type + ".updatedAt  STRING");
        }

        // Human-readable label: most types use `name`; people use `displayName`.
        for (String type : List.of(
                "EntCompany", "EntCoreValue", "EntFunctionalArea", "EntIndustry",
                "EntClient", "EntEngagement", "EntOrgUnit", "EntBranch", "EntLocation")) {
            tryExec("CREATE PROPERTY " + type + ".name        STRING");
            tryExec("CREATE PROPERTY " + type + ".description STRING");
        }

        // Company
        for (String prop : List.of(
                "CREATE PROPERTY EntCompany.legalName          STRING",
                "CREATE PROPERTY EntCompany.tradeName           STRING",
                "CREATE PROPERTY EntCompany.foundedYear         INTEGER",
                "CREATE PROPERTY EntCompany.registrationNumber  STRING",
                "CREATE PROPERTY EntCompany.website             STRING",
                "CREATE PROPERTY EntCompany.companyType         STRING",
                "CREATE PROPERTY EntCompany.stockSymbol         STRING",
                "CREATE PROPERTY EntCompany.employeeCount       INTEGER",
                "CREATE PROPERTY EntCompany.annualRevenue       DOUBLE",
                "CREATE PROPERTY EntCompany.currency            STRING")) {
            tryExec(prop);
        }

        // Mission / Vision / CoreValue share the free-text `statement`.
        for (String type : List.of("EntMission", "EntVision", "EntCoreValue")) {
            tryExec("CREATE PROPERTY " + type + ".statement STRING");
        }

        // Industry
        tryExec("CREATE PROPERTY EntIndustry.sectorCode STRING");

        // Client
        for (String prop : List.of(
                "CREATE PROPERTY EntClient.clientSince         STRING",
                "CREATE PROPERTY EntClient.clientTier          STRING",
                "CREATE PROPERTY EntClient.relationshipStatus  STRING")) {
            tryExec(prop);
        }

        // Engagement
        for (String prop : List.of(
                "CREATE PROPERTY EntEngagement.startDate         STRING",
                "CREATE PROPERTY EntEngagement.endDate           STRING",
                "CREATE PROPERTY EntEngagement.engagementStatus  STRING",
                "CREATE PROPERTY EntEngagement.contractValue     DOUBLE",
                "CREATE PROPERTY EntEngagement.currency          STRING")) {
            tryExec(prop);
        }

        // Person / Executive — display label + title, no PII beyond that.
        // (displayName is defined on the base type; EntExecutive inherits it.)
        tryExec("CREATE PROPERTY EntPerson.displayName   STRING");
        tryExec("CREATE PROPERTY EntPerson.jobTitle      STRING");
        tryExec("CREATE PROPERTY EntExecutive.appointedDate STRING");

        // OrgUnit
        tryExec("CREATE PROPERTY EntOrgUnit.unitType STRING");
        tryExec("CREATE PROPERTY EntOrgUnit.unitCode STRING");

        // Branch
        tryExec("CREATE PROPERTY EntBranch.branchType STRING");
        tryExec("CREATE PROPERTY EntBranch.openedDate STRING");

        // Location
        for (String prop : List.of(
                "CREATE PROPERTY EntLocation.addressLine    STRING",
                "CREATE PROPERTY EntLocation.city           STRING",
                "CREATE PROPERTY EntLocation.stateProvince  STRING",
                "CREATE PROPERTY EntLocation.country        STRING",
                "CREATE PROPERTY EntLocation.postalCode     STRING",
                "CREATE PROPERTY EntLocation.latitude       DOUBLE",
                "CREATE PROPERTY EntLocation.longitude      DOUBLE",
                "CREATE PROPERTY EntLocation.timezone       STRING")) {
            tryExec(prop);
        }
    }

    // ── Indexes ───────────────────────────────────────────────────────────────

    private void createIndexes() {
        // EntExecutive is excluded: it inherits EntPerson's properties, and an
        // ArcadeDB index declared on the parent already covers subtype records.
        for (String type : List.of(
                "EntCompany", "EntMission", "EntVision", "EntCoreValue",
                "EntFunctionalArea", "EntIndustry", "EntClient", "EntEngagement",
                "EntPerson", "EntOrgUnit", "EntBranch", "EntLocation")) {
            arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON " + type + " (id) UNIQUE", Map.of());
            arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId) NOTUNIQUE", Map.of());
            // Natural upsert key — a connector matches an entity per tenant by externalId.
            arcade.commandNoResult(
                    "CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId, externalId) UNIQUE", Map.of());
        }
    }

    private void tryExec(String sql) {
        try {
            arcade.commandNoResult(sql, Map.of());
        } catch (Exception e) {
            log.debug("DDL skipped (likely already exists): {} — {}", sql.trim(), e.getMessage());
        }
    }
}
