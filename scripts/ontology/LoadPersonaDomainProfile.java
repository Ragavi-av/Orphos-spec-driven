import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads the governance/persona vocabulary and the business-domain instance data
 * described by {@code ontology/eip-governance.owl} and its six domain siblings
 * into ArcadeDB — the graph mirror of the ontology, in the same way
 * {@code EnterpriseSchemaBootstrap} + {@code EnterpriseGraphRepository} mirror
 * {@code eip-enterprise.owl}.
 *
 * <p>Deliberately a SIBLING of {@code scripts/enterprise/LoadEnterpriseProfile},
 * not an edit to it: that file has uncommitted local changes, and the enterprise
 * profile it loads is a separate, already-working concern. Every convention is
 * copied from it so a load done here is indistinguishable from one done there:</p>
 * <ul>
 *   <li>identity is {@code (tenantId, externalId)} with a composite UNIQUE index;</li>
 *   <li>writes are SELECT-then-UPDATE-or-INSERT (ArcadeDB has no MERGE), and only
 *       non-null properties are written, so a partial profile never clobbers;</li>
 *   <li>{@code id} is minted as {@code <prefix>-<uuid>}, {@code source=ADMIN};</li>
 *   <li>all vertices are written before any edges, and an edge whose target is
 *       unknown is skipped with a warning rather than failing the load;</li>
 *   <li>HTTP goes through {@link HttpURLConnection}, not
 *       {@code java.net.http.HttpClient}, because the latter opens a loopback
 *       selector pipe that is blocked in some sandboxed shells.</li>
 * </ul>
 *
 * <h2>Why classification is type-level, not row-level</h2>
 * In OWL, a record's data domain and sensitivity are INFERRED from a class-level
 * {@code owl:hasValue} restriction — one axiom covers every instance forever. The
 * faithful ArcadeDB mirror of that is one {@code GovClassification} row per graph
 * TYPE, not one edge per record. This tenant already holds 8000 IT-ops rows
 * (1000 each of Incident, ITSystem, CostCentre, BudgetItem, Project, Decision,
 * Alert, SLAMetric); classifying them by type is 8 rows instead of 8000 edges,
 * cannot drift as rows are added, and lets enforcement join on a record's
 * {@code @type}. Getting this wrong — materialising per-row edges — would make
 * the classification a batch job that new data silently escapes.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -cp "&lt;jackson jars&gt;" LoadPersonaDomainProfile.java \
 *        &lt;tenantId&gt; &lt;database&gt; &lt;governance-profile.json&gt; [&lt;domain-profile.json&gt; ...]
 * </pre>
 * A governance profile is recognised by its {@code "profileType": "governance"}
 * field, a domain profile by {@code "profileType": "domain"}; order on the
 * command line does not matter because governance profiles are always applied
 * first (domain data references personas and domains, not the other way round).
 *
 * <p>Connection details come from the repo {@code .env}
 * ({@code ARCADEDB_URL}, {@code ARCADEDB_USER}, {@code ARCADEDB_PASSWORD}),
 * falling back to environment variables of the same name. The password is never
 * printed.</p>
 */
public final class LoadPersonaDomainProfile {

    /** Matches EnterpriseGraphService.SOURCE — marks rows as admin-submitted. */
    private static final String SOURCE = "ADMIN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String baseUrl;
    private static String authHeader;
    private static String db;
    private static String tenantId;
    private static boolean dryRun;

    private static int verticesWritten;
    private static int verticesInserted;
    private static int edgesWritten;
    private static final List<String> warnings = new ArrayList<>();

    /** externalIds seen per vertex type, so refEdge can warn on dangling references. */
    private static final Map<String, Set<String>> known = new LinkedHashMap<>();

    // ── Vertex specs ───────────────────────────────────────────────────────────

    /**
     * One JSON array → one ArcadeDB vertex type.
     *
     * @param path   dotted path into the profile, e.g. {@code "finance.budgets"}
     * @param type   ArcadeDB vertex type
     * @param props  JSON field names copied straight through as properties
     */
    private record VertexSpec(String path, String type, List<String> props) {}

    /**
     * One JSON field on the objects of {@code fromPath} → one edge.
     *
     * @param field  field holding the target externalId; {@code *ExternalIds} (plural) is read as an array
     */
    private record EdgeSpec(String fromPath, String fromType, String field,
                            String edgeType, String toType, boolean many) {}

    private static final List<String> IDENTITY = List.of("name", "description");

    /** Governance vocabulary — the platform-fixed terms, mirrored per tenant. */
    private static final List<VertexSpec> GOV_VERTICES = List.of(
            new VertexSpec("sensitivityLevels", "GovSensitivity", List.of("name", "description", "sensitivityRank")),
            new VertexSpec("dataDomains", "GovDataDomain", List.of("name", "description")),
            new VertexSpec("personas", "GovPersona", List.of("name", "personaLabel", "roleKey", "description",
                    "displayOrder", "roleProvisioned")),
            new VertexSpec("capabilities", "GovCapability", List.of("name", "description", "resourceKey",
                    "groupKey", "locked")),
            new VertexSpec("widgets", "GovWidget", List.of("name", "description", "widgetKey", "isDefaultVisible")),
            // figureKind (FLOW | BALANCE) says whether the metric accumulates over
            // its period or stands at a point in it. Only currency metrics use it,
            // and only for choosing between a period-average and a closing FX rate
            // when a reader asks for the board in another currency — see
            // io.orphos.eip.common.money.FigureKind. Absent on a metric that
            // predates the property, which the reader defaults to BALANCE.
            new VertexSpec("metrics", "GovMetric", List.of("name", "description", "metricKey", "unit",
                    "aggregation", "higherIsBetter", "figureKind")),
            new VertexSpec("periods", "GovPeriod", List.of("name", "periodKey", "periodType",
                    "startDate", "endDate")),
            new VertexSpec("classifications", "GovClassification", List.of("name", "description", "graphType")),
            new VertexSpec("accessGrants", "GovAccessGrant", List.of("name", "description", "effect", "accessScope")),
            new VertexSpec("metricObservations", "GovMetricObservation", List.of("name", "value", "targetValue",
                    "ragStatus", "currency")));

    private static final List<EdgeSpec> GOV_EDGES = List.of(
            new EdgeSpec("dataDomains", "GovDataDomain", "parentDomainExternalId",
                    "SUB_DOMAIN_OF", "GovDataDomain", false),
            new EdgeSpec("dataDomains", "GovDataDomain", "parentDomainExternalIds",
                    "SUB_DOMAIN_OF", "GovDataDomain", true),
            new EdgeSpec("personas", "GovPersona", "inheritsFromExternalId",
                    "INHERITS_FROM", "GovPersona", false),
            new EdgeSpec("personas", "GovPersona", "maxSensitivityExternalId",
                    "MAX_SENSITIVITY", "GovSensitivity", false),
            new EdgeSpec("personas", "GovPersona", "canAccessDomainExternalIds",
                    "CAN_ACCESS_DOMAIN", "GovDataDomain", true),
            new EdgeSpec("personas", "GovPersona", "hasCapabilityExternalIds",
                    "HAS_CAPABILITY", "GovCapability", true),
            new EdgeSpec("personas", "GovPersona", "watchesMetricExternalIds",
                    "WATCHES_METRIC", "GovMetric", true),
            new EdgeSpec("personas", "GovPersona", "accountableForExternalIds",
                    "ACCOUNTABLE_FOR", "GovMetric", true),
            // The persona → real person link. EntExecutive already exists for this
            // tenant (loaded by the enterprise profile), which is why this loader
            // must run AFTER LoadEnterpriseProfile.
            new EdgeSpec("personas", "GovPersona", "embodiedByExecutiveExternalId",
                    "EMBODIED_BY", "EntExecutive", false),
            new EdgeSpec("capabilities", "GovCapability", "groupExternalId",
                    "CAPABILITY_IN_GROUP", "GovCapability", false),
            new EdgeSpec("widgets", "GovWidget", "inDataDomainExternalId",
                    "IN_DATA_DOMAIN", "GovDataDomain", false),
            new EdgeSpec("metrics", "GovMetric", "inDataDomainExternalId",
                    "IN_DATA_DOMAIN", "GovDataDomain", false),
            new EdgeSpec("metrics", "GovMetric", "sensitivityExternalId",
                    "HAS_SENSITIVITY", "GovSensitivity", false),
            new EdgeSpec("metrics", "GovMetric", "renderedByWidgetExternalId",
                    "RENDERED_BY", "GovWidget", false),
            new EdgeSpec("periods", "GovPeriod", "withinPeriodExternalId",
                    "WITHIN_PERIOD", "GovPeriod", false),
            new EdgeSpec("classifications", "GovClassification", "inDataDomainExternalId",
                    "IN_DATA_DOMAIN", "GovDataDomain", false),
            new EdgeSpec("classifications", "GovClassification", "sensitivityExternalId",
                    "HAS_SENSITIVITY", "GovSensitivity", false),
            new EdgeSpec("accessGrants", "GovAccessGrant", "personaExternalId",
                    "GRANT_TO", "GovPersona", false),
            new EdgeSpec("accessGrants", "GovAccessGrant", "domainExternalId",
                    "GRANT_ON_DOMAIN", "GovDataDomain", false),
            new EdgeSpec("accessGrants", "GovAccessGrant", "capabilityExternalId",
                    "GRANT_ON_CAPABILITY", "GovCapability", false),
            new EdgeSpec("accessGrants", "GovAccessGrant", "widgetExternalId",
                    "GRANT_ON_WIDGET", "GovWidget", false),
            new EdgeSpec("metricObservations", "GovMetricObservation", "metricExternalId",
                    "OBSERVATION_OF", "GovMetric", false),
            new EdgeSpec("metricObservations", "GovMetricObservation", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("metricObservations", "GovMetricObservation", "orgUnitExternalId",
                    "SCOPED_TO_ORG_UNIT", "EntOrgUnit", false));

    /** Business-domain instance data across finance, HR, commercial, strategy, risk, IT ops. */
    private static final List<VertexSpec> DOMAIN_VERTICES = List.of(
            // ── Finance. NOTE: no FinCostCentre — fin:CostCentre is declared
            // owl:equivalentClass eip:CostCentre, and the existing CostCentre type
            // already holds 1000 rows for this tenant. Creating a second cost-centre
            // type would fork the concept the ontology deliberately unified.
            new VertexSpec("finance.glAccounts", "FinGLAccount",
                    List.of("name", "description", "accountCode", "accountType")),
            new VertexSpec("finance.vendors", "FinVendor",
                    List.of("name", "description", "vendorCategory")),
            new VertexSpec("finance.budgets", "FinBudget",
                    List.of("name", "description", "currency")),
            new VertexSpec("finance.budgetLines", "FinBudgetLine",
                    List.of("name", "description", "plannedAmount", "actualAmount",
                            "committedAmount", "currency")),
            new VertexSpec("finance.cashFlowEntries", "FinCashFlowEntry",
                    List.of("name", "description", "inflowAmount", "outflowAmount",
                            "closingBalance", "currency")),
            new VertexSpec("finance.invoices", "FinInvoice",
                    List.of("name", "description", "amount", "currency", "invoiceDirection",
                            "invoiceStatus", "dueDate")),
            new VertexSpec("finance.statements", "FinFinancialStatement",
                    List.of("name", "description", "statementType", "currency")),
            // ── HR
            new VertexSpec("hr.designations", "HrDesignation",
                    List.of("name", "description", "gradeLevel")),
            new VertexSpec("hr.employmentTypes", "HrEmploymentType",
                    List.of("name", "description")),
            new VertexSpec("hr.skills", "HrSkill", List.of("name", "description")),
            new VertexSpec("hr.positions", "HrPosition",
                    List.of("name", "description", "positionStatus", "fteRatio")),
            new VertexSpec("hr.compensationBands", "HrCompensationBand",
                    List.of("name", "description", "bandMinimum", "bandMaximum", "currency")),
            new VertexSpec("hr.headcountSnapshots", "HrHeadcountSnapshot",
                    List.of("name", "headcount", "fteCount")),
            new VertexSpec("hr.attritionSnapshots", "HrAttritionSnapshot",
                    List.of("name", "joinerCount", "leaverCount", "attritionRate")),
            new VertexSpec("hr.payrollRuns", "HrPayrollRun",
                    List.of("name", "description", "totalCost", "coveredHeadcount",
                            "minimumPopulation", "currency")),
            new VertexSpec("hr.requisitions", "HrRequisition",
                    List.of("name", "description", "requisitionStatus", "openedDate")),
            // ── Commercial
            new VertexSpec("commercial.products", "ComProduct", List.of("name", "description")),
            new VertexSpec("commercial.serviceLines", "ComServiceLine", List.of("name", "description")),
            new VertexSpec("commercial.opportunities", "ComOpportunity",
                    List.of("name", "description", "stage", "probability", "dealValue",
                            "weightedValue", "expectedCloseDate", "currency")),
            new VertexSpec("commercial.contracts", "ComContract",
                    List.of("name", "description", "contractValue", "annualRecurringRevenue",
                            "contractStatus", "signedDate", "expiryDate", "currency")),
            new VertexSpec("commercial.pipelineSnapshots", "ComPipelineSnapshot",
                    List.of("name", "pipelineValue", "opportunityCount", "winRate", "currency")),
            new VertexSpec("commercial.satisfactionScores", "ComSatisfactionScore",
                    List.of("name", "scoreType", "scoreValue", "responseCount")),
            // ── Strategy. NOTE: no StrProject / StrDecision — the existing Project
            // and Decision types (1000 rows each) ARE the portfolio projects and
            // decisions; they are classified by type instead of duplicated.
            new VertexSpec("strategy.objectives", "StrObjective",
                    List.of("name", "description", "objectiveStatus", "horizon")),
            new VertexSpec("strategy.keyResults", "StrKeyResult",
                    List.of("name", "description", "baselineValue", "currentValue", "targetValue")),
            new VertexSpec("strategy.initiatives", "StrInitiative",
                    List.of("name", "description", "ragStatus", "percentComplete",
                            "plannedStartDate", "plannedEndDate")),
            new VertexSpec("strategy.milestones", "StrMilestone",
                    List.of("name", "description", "milestoneStatus", "plannedEndDate", "actualEndDate")),
            // ── Risk
            new VertexSpec("risk.policies", "RskPolicy", List.of("name", "description")),
            new VertexSpec("risk.obligations", "RskComplianceObligation",
                    List.of("name", "description", "obligationSource", "complianceStatus")),
            new VertexSpec("risk.controls", "RskControl",
                    List.of("name", "description", "controlType", "controlEffectiveness", "lastTestedDate")),
            new VertexSpec("risk.risks", "RskRisk",
                    List.of("name", "description", "riskCategory", "likelihood", "impact",
                            "inherentSeverity", "residualSeverity", "exposureAmount",
                            "riskStatus", "identifiedDate", "currency")),
            new VertexSpec("risk.audits", "RskAudit",
                    List.of("name", "description", "auditType", "complianceStatus")),
            new VertexSpec("risk.findings", "RskAuditFinding",
                    List.of("name", "findingDetail", "findingSeverity", "findingStatus",
                            "remediationDueDate")),
            // ── IT ops (extends the existing ITSystem / Incident / SLAMetric types)
            new VertexSpec("itops.businessServices", "ItopsBusinessService",
                    List.of("name", "description", "criticality", "healthStatus")),
            new VertexSpec("itops.assets", "ItopsAsset",
                    List.of("name", "description", "assetType", "criticality", "annualCost",
                            "endOfLifeDate", "environment", "currency")),
            new VertexSpec("itops.slas", "ItopsServiceLevelAgreement",
                    List.of("name", "description", "slaTargetPercent", "responseTimeMinutes")),
            new VertexSpec("itops.changeRequests", "ItopsChangeRequest",
                    List.of("name", "description", "changeStatus", "changeRisk", "scheduledDate")),
            new VertexSpec("itops.releases", "ItopsRelease",
                    List.of("name", "description", "releaseVersion", "scheduledDate")),
            new VertexSpec("itops.vulnerabilities", "ItopsVulnerability",
                    List.of("name", "description", "cveId", "severity", "remediationStatus")));

    private static final List<EdgeSpec> DOMAIN_EDGES = List.of(
            // Finance
            new EdgeSpec("finance.glAccounts", "FinGLAccount", "parentAccountExternalId",
                    "PARENT_ACCOUNT", "FinGLAccount", false),
            new EdgeSpec("finance.budgets", "FinBudget", "orgUnitExternalId",
                    "BUDGET_FOR_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("finance.budgets", "FinBudget", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("finance.budgetLines", "FinBudgetLine", "budgetExternalId",
                    "LINE_OF_BUDGET", "FinBudget", false),
            new EdgeSpec("finance.budgetLines", "FinBudgetLine", "accountExternalId",
                    "FOR_ACCOUNT", "FinGLAccount", false),
            new EdgeSpec("finance.budgetLines", "FinBudgetLine", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("finance.cashFlowEntries", "FinCashFlowEntry", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("finance.invoices", "FinInvoice", "clientExternalId",
                    "BILLED_TO_CLIENT", "EntClient", false),
            new EdgeSpec("finance.invoices", "FinInvoice", "vendorExternalId",
                    "PAID_TO_VENDOR", "FinVendor", false),
            new EdgeSpec("finance.invoices", "FinInvoice", "engagementExternalId",
                    "ATTRIBUTED_TO_ENGAGEMENT", "EntEngagement", false),
            new EdgeSpec("finance.statements", "FinFinancialStatement", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("finance.statements", "FinFinancialStatement", "companyExternalId",
                    "STATEMENT_FOR", "EntCompany", false),
            // HR
            new EdgeSpec("hr.positions", "HrPosition", "orgUnitExternalId",
                    "POSITION_IN_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("hr.positions", "HrPosition", "designationExternalId",
                    "HAS_DESIGNATION", "HrDesignation", false),
            new EdgeSpec("hr.positions", "HrPosition", "requiresSkillExternalIds",
                    "REQUIRES_SKILL", "HrSkill", true),
            new EdgeSpec("hr.compensationBands", "HrCompensationBand", "designationExternalId",
                    "BAND_FOR_DESIGNATION", "HrDesignation", false),
            new EdgeSpec("hr.headcountSnapshots", "HrHeadcountSnapshot", "orgUnitExternalId",
                    "SNAPSHOT_FOR_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("hr.headcountSnapshots", "HrHeadcountSnapshot", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("hr.attritionSnapshots", "HrAttritionSnapshot", "orgUnitExternalId",
                    "SNAPSHOT_FOR_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("hr.attritionSnapshots", "HrAttritionSnapshot", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("hr.payrollRuns", "HrPayrollRun", "orgUnitExternalId",
                    "SNAPSHOT_FOR_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("hr.payrollRuns", "HrPayrollRun", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("hr.requisitions", "HrRequisition", "positionExternalId",
                    "REQUISITION_FOR_POSITION", "HrPosition", false),
            new EdgeSpec("hr.requisitions", "HrRequisition", "requiresSkillExternalIds",
                    "REQUIRES_SKILL", "HrSkill", true),
            // Commercial
            new EdgeSpec("commercial.opportunities", "ComOpportunity", "clientExternalId",
                    "FOR_CLIENT", "EntClient", false),
            new EdgeSpec("commercial.opportunities", "ComOpportunity", "serviceLineExternalId",
                    "IN_SERVICE_LINE", "ComServiceLine", false),
            new EdgeSpec("commercial.opportunities", "ComOpportunity", "orgUnitExternalId",
                    "SOLD_BY_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("commercial.opportunities", "ComOpportunity", "wonAsContractExternalId",
                    "WON_AS_CONTRACT", "ComContract", false),
            new EdgeSpec("commercial.contracts", "ComContract", "clientExternalId",
                    "FOR_CLIENT", "EntClient", false),
            new EdgeSpec("commercial.contracts", "ComContract", "engagementExternalId",
                    "CONTRACT_REALISES_ENGAGEMENT", "EntEngagement", false),
            new EdgeSpec("commercial.contracts", "ComContract", "productExternalId",
                    "FOR_PRODUCT", "ComProduct", false),
            new EdgeSpec("commercial.pipelineSnapshots", "ComPipelineSnapshot", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("commercial.pipelineSnapshots", "ComPipelineSnapshot", "orgUnitExternalId",
                    "SOLD_BY_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("commercial.satisfactionScores", "ComSatisfactionScore", "clientExternalId",
                    "FOR_CLIENT", "EntClient", false),
            new EdgeSpec("commercial.satisfactionScores", "ComSatisfactionScore", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            // Strategy — contributesTo is one edge type across the whole chain, so
            // the transitive closure in the OWL has a single graph counterpart.
            new EdgeSpec("strategy.objectives", "StrObjective", "parentObjectiveExternalId",
                    "PARENT_OBJECTIVE", "StrObjective", false),
            new EdgeSpec("strategy.objectives", "StrObjective", "ownerExecutiveExternalId",
                    "OWNED_BY_EXECUTIVE", "EntExecutive", false),
            new EdgeSpec("strategy.objectives", "StrObjective", "periodExternalId",
                    "FOR_PERIOD", "GovPeriod", false),
            new EdgeSpec("strategy.keyResults", "StrKeyResult", "objectiveExternalId",
                    "CONTRIBUTES_TO", "StrObjective", false),
            new EdgeSpec("strategy.keyResults", "StrKeyResult", "metricExternalId",
                    "MEASURED_BY_METRIC", "GovMetric", false),
            new EdgeSpec("strategy.initiatives", "StrInitiative", "contributesToExternalId",
                    "CONTRIBUTES_TO", "StrObjective", false),
            new EdgeSpec("strategy.initiatives", "StrInitiative", "ownerExecutiveExternalId",
                    "OWNED_BY_EXECUTIVE", "EntExecutive", false),
            new EdgeSpec("strategy.initiatives", "StrInitiative", "orgUnitExternalId",
                    "DELIVERED_BY_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("strategy.milestones", "StrMilestone", "contributesToExternalId",
                    "CONTRIBUTES_TO", "StrInitiative", false),
            // Risk
            new EdgeSpec("risk.risks", "RskRisk", "mitigatedByExternalIds",
                    "MITIGATED_BY", "RskControl", true),
            new EdgeSpec("risk.risks", "RskRisk", "threatensObjectiveExternalId",
                    "THREATENS_OBJECTIVE", "StrObjective", false),
            new EdgeSpec("risk.risks", "RskRisk", "ownerExecutiveExternalId",
                    "OWNED_BY_EXECUTIVE", "EntExecutive", false),
            new EdgeSpec("risk.controls", "RskControl", "implementsPolicyExternalId",
                    "IMPLEMENTS_POLICY", "RskPolicy", false),
            new EdgeSpec("risk.controls", "RskControl", "satisfiesObligationExternalIds",
                    "SATISFIES_OBLIGATION", "RskComplianceObligation", true),
            new EdgeSpec("risk.findings", "RskAuditFinding", "auditExternalId",
                    "RAISED_FINDING", "RskAudit", false),
            new EdgeSpec("risk.findings", "RskAuditFinding", "controlExternalId",
                    "FINDING_AGAINST_CONTROL", "RskControl", false),
            // IT ops
            new EdgeSpec("itops.businessServices", "ItopsBusinessService", "orgUnitExternalId",
                    "SERVICE_FOR_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("itops.businessServices", "ItopsBusinessService", "operatedByOrgUnitExternalId",
                    "OPERATED_BY_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("itops.assets", "ItopsAsset", "operatedByOrgUnitExternalId",
                    "OPERATED_BY_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("itops.slas", "ItopsServiceLevelAgreement", "businessServiceExternalId",
                    "SLA_FOR_SERVICE", "ItopsBusinessService", false),
            new EdgeSpec("itops.changeRequests", "ItopsChangeRequest", "releaseExternalId",
                    "CHANGE_IN_RELEASE", "ItopsRelease", false),
            new EdgeSpec("itops.changeRequests", "ItopsChangeRequest", "operatedByOrgUnitExternalId",
                    "OPERATED_BY_ORG_UNIT", "EntOrgUnit", false),
            new EdgeSpec("itops.vulnerabilities", "ItopsVulnerability", "assetExternalId",
                    "VULNERABILITY_AFFECTS", "ItopsAsset", false));

    // ── main ───────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        List<String> positional = new ArrayList<>();
        for (String a : args) {
            if ("--dry-run".equals(a)) dryRun = true;
            else positional.add(a);
        }
        if (positional.size() < 3) {
            System.err.println("Usage: java LoadPersonaDomainProfile.java [--dry-run] "
                    + "<tenantId> <database> <profile.json>...");
            System.exit(2);
        }
        tenantId = positional.get(0);
        db = positional.get(1);

        List<Path> profiles = new ArrayList<>();
        for (int i = 2; i < positional.size(); i++) profiles.add(Path.of(positional.get(i)));

        Map<String, String> env = readEnv();
        baseUrl = firstNonBlank(env.get("ARCADEDB_URL"), System.getenv("ARCADEDB_URL"), "http://localhost:2480");
        String user = firstNonBlank(env.get("ARCADEDB_USER"), System.getenv("ARCADEDB_USER"), "root");
        String password = firstNonBlank(env.get("ARCADEDB_PASSWORD"), System.getenv("ARCADEDB_PASSWORD"), null);
        if (password == null) {
            System.err.println("ARCADEDB_PASSWORD not found in .env or environment.");
            System.exit(3);
        }
        authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        System.out.printf("ArcadeDB %s, database %s, tenant %s%s%n",
                baseUrl, db, tenantId, dryRun ? "  [DRY RUN — no writes]" : "");

        // Governance first: domain data references personas, domains and periods.
        List<JsonNode> governance = new ArrayList<>();
        List<JsonNode> domain = new ArrayList<>();
        for (Path p : profiles) {
            JsonNode root = MAPPER.readTree(Files.readString(p, StandardCharsets.UTF_8));
            String kind = text(root, "profileType");
            if ("governance".equals(kind)) governance.add(root);
            else if ("domain".equals(kind)) domain.add(root);
            else {
                System.err.println("Skipping " + p.getFileName()
                        + ": profileType must be \"governance\" or \"domain\", was " + kind);
                continue;
            }
            System.out.printf("  loaded %s (%s)%n", p.getFileName(), kind);
        }

        System.out.println("\n── Applying governance + domain schema ─────────────────────────");
        applySchema();
        System.out.println("Schema ready.");

        for (JsonNode root : governance) applyProfile(root, GOV_VERTICES, GOV_EDGES, "governance");
        for (JsonNode root : domain) applyProfile(root, DOMAIN_VERTICES, DOMAIN_EDGES, "domain");

        System.out.printf("%nTotal: %d vertices (%d new, %d updated), %d edges, %d warnings.%n",
                verticesWritten, verticesInserted, verticesWritten - verticesInserted,
                edgesWritten, warnings.size());
        for (String w : warnings) System.out.println("  [warn] " + w);

        if (!dryRun) printCounts();
    }

    private static void applyProfile(JsonNode root, List<VertexSpec> vertices,
                                     List<EdgeSpec> edges, String label) throws Exception {
        System.out.printf("%n── Loading %s profile ──────────────────────────────────%n", label);
        int v0 = verticesWritten, e0 = edgesWritten;

        // Pass 1: every vertex, so pass 2 can never hit a not-yet-written target.
        for (VertexSpec spec : vertices) {
            int n = 0;
            for (JsonNode node : at(root, spec.path())) {
                String externalId = text(node, "externalId");
                if (!notBlank(externalId)) {
                    warnings.add(spec.path() + ": entry without externalId skipped");
                    continue;
                }
                vertex(spec.type(), externalId, pick(node, spec.props()));
                known.computeIfAbsent(spec.type(), k -> new LinkedHashSet<>()).add(externalId);
                n++;
            }
            if (n > 0) System.out.printf("  %-28s %d%n", spec.type(), n);
        }

        // Pass 2: edges.
        for (EdgeSpec spec : edges) {
            for (JsonNode node : at(root, spec.fromPath())) {
                String from = text(node, "externalId");
                if (!notBlank(from)) continue;
                if (spec.many()) {
                    for (JsonNode t : node.path(spec.field())) {
                        refEdge(spec.edgeType(), spec.fromType(), from, spec.toType(), t.asText(),
                                spec.fromPath() + "." + spec.field());
                    }
                } else {
                    refEdge(spec.edgeType(), spec.fromType(), from, spec.toType(),
                            text(node, spec.field()), spec.fromPath() + "." + spec.field());
                }
            }
        }
        System.out.printf("  → %d vertices, %d edges%n", verticesWritten - v0, edgesWritten - e0);
    }

    // ── Writes (mirror EnterpriseGraphRepository) ──────────────────────────────

    private static void vertex(String type, String externalId, Map<String, JsonNode> props) throws Exception {
        if (dryRun) { verticesWritten++; verticesInserted++; return; }
        String now = Instant.now().toString();

        ObjectNode key = MAPPER.createObjectNode().put("tenantId", tenantId).put("externalId", externalId);
        boolean exists = !query("SELECT FROM " + type
                + " WHERE tenantId = :tenantId AND externalId = :externalId LIMIT 1", key).isEmpty();

        ObjectNode params = MAPPER.createObjectNode();
        List<String> assignable = new ArrayList<>();
        props.forEach((k, v) -> {
            if (v != null && !v.isNull() && !v.isMissingNode()) {
                params.set(k, v);
                assignable.add(k);
            }
        });
        params.put("tenantId", tenantId).put("externalId", externalId);

        if (exists) {
            params.put("updatedAt", now);
            assignable.add("updatedAt");
            if (assignable.size() == 1) return; // nothing but updatedAt — skip a pointless write
            command("UPDATE " + type + " SET " + setClause(assignable)
                    + " WHERE tenantId = :tenantId AND externalId = :externalId", params);
        } else {
            params.put("id", idPrefix(type) + "-" + UUID.randomUUID())
                    .put("source", SOURCE).put("createdAt", now).put("updatedAt", now);
            assignable.addAll(List.of("tenantId", "externalId", "id", "source", "createdAt", "updatedAt"));
            command("INSERT INTO " + type + " SET " + setClause(assignable), params);
            verticesInserted++;
        }
        verticesWritten++;
    }

    /**
     * Creates an edge between two upserted vertices, idempotently.
     *
     * <p>The "already linked?" probe is anchored on the source VERTEX and
     * traverses out, rather than filtering the edge type by {@code out.<property>}
     * the way {@code EnterpriseGraphRepository.linkOnce} does: on ArcadeDB 26.7.2
     * an {@code out.<property>} predicate in a WHERE clause over an edge type
     * matches nothing at all, so that check silently never fires and every re-run
     * duplicates the edge. This is the same workaround
     * {@code LoadEnterpriseProfile} documents.</p>
     */
    private static void edge(String edgeType, String fromType, String fromId,
                             String toType, String toId) throws Exception {
        if (fromId == null || toId == null) return;
        if (dryRun) { edgesWritten++; return; }
        ObjectNode p = MAPPER.createObjectNode()
                .put("tenantId", tenantId).put("from", fromId).put("to", toId);

        List<JsonNode> linkRows = query("SELECT count(*) AS c FROM " + fromType
                + " WHERE tenantId = :tenantId AND externalId = :from"
                + " AND out('" + edgeType + "').externalId CONTAINS :to", p);
        if (!linkRows.isEmpty() && linkRows.get(0).path("c").asLong() > 0) return;

        boolean endpointsExist = !query("SELECT FROM " + fromType
                + " WHERE tenantId = :tenantId AND externalId = :from LIMIT 1", p).isEmpty()
                && !query("SELECT FROM " + toType
                + " WHERE tenantId = :tenantId AND externalId = :to LIMIT 1", p).isEmpty();
        if (!endpointsExist) return;

        command("CREATE EDGE " + edgeType
                + " FROM (SELECT FROM " + fromType + " WHERE tenantId = :tenantId AND externalId = :from) "
                + "TO (SELECT FROM " + toType + " WHERE tenantId = :tenantId AND externalId = :to)", p);
        edgesWritten++;
    }

    /**
     * Edge whose target is optional: null → skip silently; unknown → skip with a
     * warning. Targets in types this loader does not write (EntOrgUnit,
     * EntExecutive, EntClient, EntEngagement, EntCompany — all owned by the
     * enterprise profile) are not in {@link #known}, so they are checked against
     * the database instead of being wrongly reported as dangling.
     */
    private static void refEdge(String edgeType, String fromType, String fromId, String toType,
                                String toId, String fieldDesc) throws Exception {
        if (!notBlank(toId)) return;
        Set<String> seen = known.get(toType);
        if (seen != null && !seen.contains(toId)) {
            if (dryRun || !existsInDb(toType, toId)) {
                warnings.add("Skipped " + edgeType + ": " + fieldDesc
                        + " references unknown " + toType + " '" + toId + "'");
                return;
            }
        } else if (seen == null && !dryRun && !existsInDb(toType, toId)) {
            warnings.add("Skipped " + edgeType + ": " + fieldDesc
                    + " references " + toType + " '" + toId + "' which is not in the database"
                    + " (is the enterprise profile loaded for this tenant?)");
            return;
        }
        edge(edgeType, fromType, fromId, toType, toId);
    }

    private static boolean existsInDb(String type, String externalId) throws Exception {
        ObjectNode p = MAPPER.createObjectNode().put("tenantId", tenantId).put("externalId", externalId);
        try {
            return !query("SELECT FROM " + type
                    + " WHERE tenantId = :tenantId AND externalId = :externalId LIMIT 1", p).isEmpty();
        } catch (Exception e) {
            return false; // unknown type — treated as a dangling reference
        }
    }

    // ── Schema DDL (mirrors EnterpriseSchemaBootstrap's conventions) ────────────

    private static final List<String> GOV_TYPES = List.of(
            "GovSensitivity", "GovDataDomain", "GovPersona", "GovCapability", "GovWidget",
            "GovMetric", "GovMetricObservation", "GovPeriod", "GovClassification", "GovAccessGrant");

    private static final List<String> EDGE_TYPES = List.of(
            // governance
            "SUB_DOMAIN_OF", "INHERITS_FROM", "MAX_SENSITIVITY", "CAN_ACCESS_DOMAIN",
            "HAS_CAPABILITY", "WATCHES_METRIC", "ACCOUNTABLE_FOR", "EMBODIED_BY",
            "CAPABILITY_IN_GROUP", "IN_DATA_DOMAIN", "HAS_SENSITIVITY", "RENDERED_BY",
            "WITHIN_PERIOD", "GRANT_TO", "GRANT_ON_DOMAIN", "GRANT_ON_CAPABILITY",
            "GRANT_ON_WIDGET", "OBSERVATION_OF", "FOR_PERIOD", "SCOPED_TO_ORG_UNIT",
            // finance
            "PARENT_ACCOUNT", "BUDGET_FOR_ORG_UNIT", "LINE_OF_BUDGET", "FOR_ACCOUNT",
            "BILLED_TO_CLIENT", "PAID_TO_VENDOR", "ATTRIBUTED_TO_ENGAGEMENT", "STATEMENT_FOR",
            // hr
            "POSITION_IN_ORG_UNIT", "HAS_DESIGNATION", "REQUIRES_SKILL", "BAND_FOR_DESIGNATION",
            "SNAPSHOT_FOR_ORG_UNIT", "REQUISITION_FOR_POSITION",
            // commercial
            "FOR_CLIENT", "IN_SERVICE_LINE", "SOLD_BY_ORG_UNIT", "WON_AS_CONTRACT",
            "CONTRACT_REALISES_ENGAGEMENT", "FOR_PRODUCT",
            // strategy
            "PARENT_OBJECTIVE", "OWNED_BY_EXECUTIVE", "CONTRIBUTES_TO", "MEASURED_BY_METRIC",
            "DELIVERED_BY_ORG_UNIT",
            // risk
            "MITIGATED_BY", "THREATENS_OBJECTIVE", "IMPLEMENTS_POLICY", "SATISFIES_OBLIGATION",
            "RAISED_FINDING", "FINDING_AGAINST_CONTROL",
            // itops
            "SERVICE_FOR_ORG_UNIT", "OPERATED_BY_ORG_UNIT", "SLA_FOR_SERVICE",
            "CHANGE_IN_RELEASE", "VULNERABILITY_AFFECTS");

    private static void applySchema() throws Exception {
        List<String> allTypes = new ArrayList<>(GOV_TYPES);
        for (VertexSpec s : DOMAIN_VERTICES) if (!allTypes.contains(s.type())) allTypes.add(s.type());

        if (dryRun) {
            System.out.printf("  would create %d vertex types, %d edge types%n",
                    allTypes.size(), EDGE_TYPES.size());
            return;
        }

        for (String type : allTypes) command("CREATE VERTEX TYPE " + type + " IF NOT EXISTS", null);
        for (String type : EDGE_TYPES) command("CREATE EDGE TYPE " + type + " IF NOT EXISTS", null);

        // CREATE PROPERTY has no IF NOT EXISTS in ArcadeDB — tolerate "already exists".
        for (String type : allTypes) {
            for (String p : List.of("id", "tenantId", "externalId", "source", "createdAt", "updatedAt")) {
                tryExec("CREATE PROPERTY " + type + "." + p + " STRING");
            }
            for (String p : IDENTITY) tryExec("CREATE PROPERTY " + type + "." + p + " STRING");
        }

        // Typed properties. Everything not named here stays schemaless, which
        // ArcadeDB allows and which keeps this list to the fields that are
        // actually filtered, summed or compared.
        for (String p : List.of(
                "CREATE PROPERTY GovSensitivity.sensitivityRank INTEGER",
                "CREATE PROPERTY GovPersona.roleKey STRING",
                "CREATE PROPERTY GovPersona.personaLabel STRING",
                "CREATE PROPERTY GovPersona.displayOrder INTEGER",
                "CREATE PROPERTY GovPersona.roleProvisioned BOOLEAN",
                "CREATE PROPERTY GovCapability.resourceKey STRING",
                "CREATE PROPERTY GovCapability.groupKey STRING",
                "CREATE PROPERTY GovCapability.locked BOOLEAN",
                "CREATE PROPERTY GovWidget.widgetKey STRING",
                "CREATE PROPERTY GovWidget.isDefaultVisible BOOLEAN",
                "CREATE PROPERTY GovMetric.metricKey STRING",
                "CREATE PROPERTY GovMetric.unit STRING",
                "CREATE PROPERTY GovMetric.aggregation STRING",
                "CREATE PROPERTY GovMetric.higherIsBetter BOOLEAN",
                "CREATE PROPERTY GovMetricObservation.value DOUBLE",
                "CREATE PROPERTY GovMetricObservation.targetValue DOUBLE",
                "CREATE PROPERTY GovMetricObservation.ragStatus STRING",
                "CREATE PROPERTY GovPeriod.periodKey STRING",
                "CREATE PROPERTY GovPeriod.periodType STRING",
                "CREATE PROPERTY GovClassification.graphType STRING",
                "CREATE PROPERTY GovAccessGrant.effect STRING",
                "CREATE PROPERTY GovAccessGrant.accessScope STRING",
                "CREATE PROPERTY FinGLAccount.accountCode STRING",
                "CREATE PROPERTY FinGLAccount.accountType STRING",
                "CREATE PROPERTY FinBudgetLine.plannedAmount DOUBLE",
                "CREATE PROPERTY FinBudgetLine.actualAmount DOUBLE",
                "CREATE PROPERTY FinBudgetLine.committedAmount DOUBLE",
                "CREATE PROPERTY FinCashFlowEntry.inflowAmount DOUBLE",
                "CREATE PROPERTY FinCashFlowEntry.outflowAmount DOUBLE",
                "CREATE PROPERTY FinCashFlowEntry.closingBalance DOUBLE",
                "CREATE PROPERTY FinInvoice.amount DOUBLE",
                "CREATE PROPERTY FinInvoice.invoiceDirection STRING",
                "CREATE PROPERTY FinInvoice.invoiceStatus STRING",
                "CREATE PROPERTY HrDesignation.gradeLevel INTEGER",
                "CREATE PROPERTY HrPosition.positionStatus STRING",
                "CREATE PROPERTY HrPosition.fteRatio DOUBLE",
                "CREATE PROPERTY HrCompensationBand.bandMinimum DOUBLE",
                "CREATE PROPERTY HrCompensationBand.bandMaximum DOUBLE",
                "CREATE PROPERTY HrHeadcountSnapshot.headcount INTEGER",
                "CREATE PROPERTY HrHeadcountSnapshot.fteCount DOUBLE",
                "CREATE PROPERTY HrAttritionSnapshot.joinerCount INTEGER",
                "CREATE PROPERTY HrAttritionSnapshot.leaverCount INTEGER",
                "CREATE PROPERTY HrAttritionSnapshot.attritionRate DOUBLE",
                "CREATE PROPERTY HrPayrollRun.totalCost DOUBLE",
                "CREATE PROPERTY HrPayrollRun.coveredHeadcount INTEGER",
                "CREATE PROPERTY HrPayrollRun.minimumPopulation INTEGER",
                "CREATE PROPERTY ComOpportunity.stage STRING",
                "CREATE PROPERTY ComOpportunity.probability DOUBLE",
                "CREATE PROPERTY ComOpportunity.dealValue DOUBLE",
                "CREATE PROPERTY ComOpportunity.weightedValue DOUBLE",
                "CREATE PROPERTY ComContract.contractValue DOUBLE",
                "CREATE PROPERTY ComContract.annualRecurringRevenue DOUBLE",
                "CREATE PROPERTY ComContract.contractStatus STRING",
                "CREATE PROPERTY ComPipelineSnapshot.pipelineValue DOUBLE",
                "CREATE PROPERTY ComPipelineSnapshot.opportunityCount INTEGER",
                "CREATE PROPERTY ComPipelineSnapshot.winRate DOUBLE",
                "CREATE PROPERTY ComSatisfactionScore.scoreValue DOUBLE",
                "CREATE PROPERTY ComSatisfactionScore.responseCount INTEGER",
                "CREATE PROPERTY StrObjective.objectiveStatus STRING",
                "CREATE PROPERTY StrKeyResult.baselineValue DOUBLE",
                "CREATE PROPERTY StrKeyResult.currentValue DOUBLE",
                "CREATE PROPERTY StrKeyResult.targetValue DOUBLE",
                "CREATE PROPERTY StrInitiative.ragStatus STRING",
                "CREATE PROPERTY StrInitiative.percentComplete DOUBLE",
                "CREATE PROPERTY StrMilestone.milestoneStatus STRING",
                "CREATE PROPERTY RskRisk.likelihood INTEGER",
                "CREATE PROPERTY RskRisk.impact INTEGER",
                "CREATE PROPERTY RskRisk.inherentSeverity INTEGER",
                "CREATE PROPERTY RskRisk.residualSeverity INTEGER",
                "CREATE PROPERTY RskRisk.exposureAmount DOUBLE",
                "CREATE PROPERTY RskRisk.riskStatus STRING",
                "CREATE PROPERTY RskControl.controlEffectiveness STRING",
                "CREATE PROPERTY RskAuditFinding.findingSeverity STRING",
                "CREATE PROPERTY RskAuditFinding.findingStatus STRING",
                "CREATE PROPERTY ItopsBusinessService.criticality STRING",
                "CREATE PROPERTY ItopsBusinessService.healthStatus STRING",
                "CREATE PROPERTY ItopsAsset.assetType STRING",
                "CREATE PROPERTY ItopsAsset.annualCost DOUBLE",
                "CREATE PROPERTY ItopsServiceLevelAgreement.slaTargetPercent DOUBLE",
                "CREATE PROPERTY ItopsServiceLevelAgreement.responseTimeMinutes INTEGER",
                "CREATE PROPERTY ItopsChangeRequest.changeStatus STRING",
                "CREATE PROPERTY ItopsChangeRequest.changeRisk STRING",
                "CREATE PROPERTY ItopsVulnerability.cveId STRING",
                "CREATE PROPERTY ItopsVulnerability.severity STRING")) {
            tryExec(p);
        }

        for (String type : allTypes) {
            command("CREATE INDEX IF NOT EXISTS ON " + type + " (id) UNIQUE", null);
            command("CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId) NOTUNIQUE", null);
            command("CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId, externalId) UNIQUE", null);
        }
        // Enforcement looks records up by graph type, so that lookup gets an index.
        command("CREATE INDEX IF NOT EXISTS ON GovClassification (tenantId, graphType) UNIQUE", null);
        command("CREATE INDEX IF NOT EXISTS ON GovPersona (tenantId, roleKey) UNIQUE", null);
    }

    private static void printCounts() throws Exception {
        System.out.println("\n── Counts for this tenant ──────────────────────────────────────");
        List<String> allTypes = new ArrayList<>(GOV_TYPES);
        for (VertexSpec s : DOMAIN_VERTICES) if (!allTypes.contains(s.type())) allTypes.add(s.type());
        for (String type : allTypes) {
            long c = countOfType(type);
            if (c > 0) System.out.printf("  %-32s %d%n", type, c);
        }
    }

    private static long countOfType(String type) throws Exception {
        List<JsonNode> rows = query("SELECT count(*) AS c FROM " + type + " WHERE tenantId = :tenantId",
                MAPPER.createObjectNode().put("tenantId", tenantId));
        return rows.isEmpty() ? 0L : rows.get(0).path("c").asLong();
    }

    private static void tryExec(String sql) {
        try {
            command(sql, null);
        } catch (Exception e) {
            // Property already exists — the bootstrap tolerates this too.
        }
    }

    // ── ArcadeDB HTTP plumbing (identical to LoadEnterpriseProfile) ─────────────

    private static JsonNode post(String endpoint, String sql, ObjectNode params) throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("language", "sql").put("command", sql);
        if (params != null) body.set("params", params);
        byte[] payload = MAPPER.writeValueAsBytes(body);

        HttpURLConnection conn = (HttpURLConnection) URI
                .create(baseUrl + "/api/v1/" + endpoint + "/" + db).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            // Fixed length, not chunked: ArcadeDB's HTTP server stalls on a
            // chunked request body, which surfaces as a read timeout.
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", authHeader);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int status = conn.getResponseCode();
            String responseBody = readAll(status / 100 == 2 ? conn.getInputStream() : conn.getErrorStream());
            if (status / 100 != 2) {
                throw new IOException("HTTP " + status + " on [" + sql + "] → " + responseBody);
            }
            return MAPPER.readTree(responseBody);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<JsonNode> query(String sql, ObjectNode params) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        post("query", sql, params).path("result").forEach(rows::add);
        return rows;
    }

    private static void command(String sql, ObjectNode params) throws Exception {
        post("command", sql, params);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Resolves a dotted path like {@code "finance.budgets"} to an array node. */
    private static Iterable<JsonNode> at(JsonNode root, String path) {
        JsonNode n = root;
        for (String part : path.split("\\.")) {
            n = n.path(part);
            if (n.isMissingNode()) return List.of();
        }
        return n.isArray() ? n : List.of();
    }

    private static Map<String, JsonNode> pick(JsonNode node, List<String> fields) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (!v.isMissingNode() && !v.isNull()) m.put(f, v);
        }
        return m;
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static String setClause(List<String> keys) {
        List<String> parts = new ArrayList<>();
        for (String k : new LinkedHashSet<>(keys)) parts.add(k + " = :" + k);
        return String.join(", ", parts);
    }

    /** GovPersona → "gov-persona", FinBudgetLine → "fin-budgetline". */
    private static String idPrefix(String type) {
        for (String prefix : List.of("Gov", "Fin", "Hr", "Com", "Str", "Rsk", "Itops", "Ent")) {
            if (type.startsWith(prefix)) {
                return prefix.toLowerCase() + "-" + type.substring(prefix.length()).toLowerCase();
            }
        }
        return type.toLowerCase();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** Reads ARCADEDB_* settings from the repo {@code .env} (two levels up), if present. */
    private static Map<String, String> readEnv() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path env = Path.of("..", "..", ".env").normalize();
        if (!Files.exists(env)) return out;
        for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            out.put(t.substring(0, eq).strip(), t.substring(eq + 1).strip());
        }
        return out;
    }

    private LoadPersonaDomainProfile() {
    }
}
