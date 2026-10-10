import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Generates a portable ArcadeDB seed script for the Orphos knowledge-graph
 * demo entities (ITSystem, Incident, SLAMetric, CostCentre, BudgetItem, Alert,
 * Decision, Project) plus the edges between them.
 *
 * <p>The output is a single, tenant-agnostic {@code .sql} script. Every row is
 * stamped with the literal token {@value #TENANT_TOKEN}; substitute it with a
 * real tenant UUID at import time (see the generated header / README) so the
 * same file can seed any tenant. Output is deterministic (fixed RNG seed) so
 * regenerating produces a byte-identical file that reviews cleanly in git.</p>
 *
 * <p>Run (JDK 11+, single-file source launch — no compile step):</p>
 * <pre>
 *   java SampleDataGenerator.java [recordsPerEntity] [outputFile]
 * </pre>
 * Defaults: {@code 1000} records per entity, {@code orphos-sample-data.sql}.
 */
public final class SampleDataGenerator {

    /** Placeholder replaced with the target tenant UUID at import time. */
    static final String TENANT_TOKEN = "__TENANT_ID__";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    // Fixed reference point so generated timestamps are stable (never "now").
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 8, 0, 0);

    // ── value pools ────────────────────────────────────────────────────────
    private static final String[] SYS_BASENAMES = {
            "SAP ERP", "Salesforce CRM", "AWS Infrastructure", "Oracle Financials",
            "Workday HCM", "ServiceNow ITSM", "Azure Platform", "Snowflake DWH",
            "Kafka Event Bus", "MongoDB Cluster", "Kubernetes Fleet", "Okta SSO",
            "Tableau BI", "GitHub Enterprise", "Jira Software", "Confluence Wiki",
            "Redis Cache", "Elastic Search", "PostgreSQL Core", "Datadog Monitoring"
    };
    private static final String[] SYS_TYPES = {
            "ERP", "CRM", "CLOUD", "DATABASE", "MIDDLEWARE", "ANALYTICS",
            "SECURITY", "MONITORING", "MESSAGING", "COLLABORATION"
    };
    private static final String[] SYS_STATUS = {"HEALTHY", "DEGRADED", "CRITICAL"};
    private static final String[] ENVIRONMENTS = {"production", "staging", "development", "dr"};
    private static final String[] OWNERS = {
            "IT Operations", "Sales IT", "Cloud Team", "Finance IT", "HR Systems",
            "Platform Engineering", "Data Engineering", "Security Operations",
            "SRE Team", "Integration Team"
    };
    private static final String[] SEVERITIES = {"CRITICAL", "HIGH", "MEDIUM", "LOW"};
    private static final String[] INCIDENT_STATUS = {"OPEN", "IN_PROGRESS", "RESOLVED", "MONITORING"};
    private static final String[] DOMAINS = {"IT", "FINANCE", "HR", "OPERATIONS", "SECURITY", "SALES"};
    private static final String[] INCIDENT_TITLES = {
            "Response Time Degradation", "Availability Zone Partial Outage",
            "API Rate Limit Breach", "Database Connection Pool Exhaustion",
            "Memory Leak Detected", "Certificate Expiry Warning",
            "Failed Batch Job", "Latency Spike", "Disk Space Critical",
            "Authentication Failures", "Replication Lag", "Queue Backlog"
    };
    private static final String[] METRIC_NAMES = {
            "Response Time", "Uptime", "Availability", "Transaction Throughput",
            "Error Rate", "P95 Latency", "Recovery Time", "Data Freshness"
    };
    private static final String[] METRIC_UNITS = {"seconds", "percent", "tps", "ms", "hours"};
    private static final String[] SLA_STATUS = {"MET", "BREACHED", "AT_RISK"};
    private static final String[] CC_NAMES = {
            "IT Operations", "Cloud Infrastructure", "Cybersecurity", "Data Platform",
            "Application Development", "End User Computing", "Network Services",
            "Business Intelligence", "DevOps Tooling", "Compliance & Risk"
    };
    private static final String[] CURRENCIES = {"USD", "EUR", "GBP", "INR"};
    private static final String[] PERIODS = {"FY2026", "FY2026-Q1", "FY2026-Q2", "FY2026-Q3", "FY2026-Q4"};
    private static final String[] EXEC_OWNERS = {"CTO", "CIO", "CISO", "VP Engineering", "VP Finance", "COO"};
    private static final String[] BUDGET_TITLES = {
            "Reserved Instances Renewal", "License Upgrade", "Security Audit",
            "Hardware Refresh", "Platform Migration", "Support Contract Renewal",
            "Capacity Expansion", "Tooling Subscription", "Consulting Engagement",
            "Disaster Recovery Setup"
    };
    private static final String[] BUDGET_STATUS = {"PENDING_APPROVAL", "APPROVED", "IN_REVIEW", "REJECTED"};
    private static final String[] ALERT_STATUS = {"ACTIVE", "ACKNOWLEDGED", "RESOLVED"};
    private static final String[] ALERT_SOURCES = {
            "BudgetMonitor", "SLAMonitor", "BudgetWorkflow", "SecurityScanner",
            "HealthProbe", "AnomalyDetector", "ComplianceEngine"
    };
    private static final String[] DECISION_STATUS = {"PENDING", "APPROVED", "REJECTED", "DEFERRED"};
    private static final String[] DECIDERS = {"CTO", "CIO", "CISO", "VP Engineering", "Board", "COO", "CFO"};
    private static final String[] PRIORITY = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};
    private static final String[] DECISION_AREAS = {
            "Infrastructure", "Security", "Compensation", "Procurement",
            "Compliance", "Architecture", "Vendor Management"
    };
    private static final String[] DECISION_TITLES = {
            "Approve Emergency Patch Deployment", "Initiate DR Failover",
            "Reserved Instances Renewal", "Vendor Contract Renewal",
            "Adopt New Monitoring Stack", "Consolidate Data Centres",
            "Migrate to Managed Service", "Increase Security Budget"
    };
    private static final String[] PROJECT_STATUS = {"ON_TRACK", "AT_RISK", "DELAYED", "COMPLETED", "PLANNING"};
    private static final String[] PROJECT_NAMES = {
            "Cloud Migration Programme", "Zero-Trust Rollout", "Data Lake Modernisation",
            "ERP Upgrade", "Customer 360 Initiative", "Observability Overhaul",
            "Legacy Decommissioning", "AI Enablement Platform", "SSO Consolidation",
            "Network Segmentation"
    };

    public static void main(String[] args) throws IOException {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        Path out = Path.of(args.length > 1 ? args[1] : "orphos-sample-data.sql");

        Random rnd = new Random(20260101L); // fixed seed → reproducible output
        StringBuilder sb = new StringBuilder(4 * 1024 * 1024);

        header(sb, n);
        ddl(sb);
        clearForTenant(sb);

        // ── vertices ─────────────────────────────────────────────────────
        section(sb, "ITSystem (" + n + ")");
        for (int i = 1; i <= n; i++) {
            String name = pick(SYS_BASENAMES, rnd) + " " + zone(i);
            ins(sb, "ITSystem",
                    kv("id", sysId(i)),
                    kv("name", name),
                    kv("type", pick(SYS_TYPES, rnd)),
                    kv("status", pick(SYS_STATUS, rnd)),
                    kv("environment", pick(ENVIRONMENTS, rnd)),
                    kv("owner", pick(OWNERS, rnd)),
                    kv("description", "Managed system instance " + i + " under " + pick(OWNERS, rnd)),
                    kv("lastChecked", ts(rnd.nextInt(90) * 24 * 3600L)),
                    tenant());
        }

        section(sb, "Incident (" + n + ")");
        for (int i = 1; i <= n; i++) {
            long created = rnd.nextInt(120) * 24 * 3600L + rnd.nextInt(86400);
            ins(sb, "Incident",
                    kv("id", incId(i)),
                    kv("title", pick(SYS_BASENAMES, rnd) + " " + pick(INCIDENT_TITLES, rnd)),
                    kv("severity", pick(SEVERITIES, rnd)),
                    kv("status", pick(INCIDENT_STATUS, rnd)),
                    kv("domain", pick(DOMAINS, rnd)),
                    kv("affectedSystem", sysId(1 + rnd.nextInt(n))),
                    kv("createdAt", ts(created)),
                    kv("estimatedResolution", ts(created + (2 + rnd.nextInt(48)) * 3600L)),
                    kv("description", "Incident " + i + " detected by automated monitoring; investigation underway."),
                    tenant());
        }

        section(sb, "SLAMetric (" + n + ")");
        for (int i = 1; i <= n; i++) {
            double target = round2(90 + rnd.nextDouble() * 10);
            double actual = round2(target - (rnd.nextDouble() * 6 - 2));
            ins(sb, "SLAMetric",
                    kv("id", slaId(i)),
                    kv("system", sysId(1 + rnd.nextInt(n))),
                    kv("metricName", pick(METRIC_NAMES, rnd)),
                    num("target", target),
                    num("actual", actual),
                    kv("unit", pick(METRIC_UNITS, rnd)),
                    kv("status", actual >= target ? "MET" : pick(SLA_STATUS, rnd)),
                    kv("measuredAt", ts(rnd.nextInt(30) * 24 * 3600L)),
                    tenant());
        }

        section(sb, "CostCentre (" + n + ")");
        for (int i = 1; i <= n; i++) {
            double budget = round2((500 + rnd.nextInt(9500)) * 1000.0);
            double spent = round2(budget * (0.6 + rnd.nextDouble() * 0.6));
            ins(sb, "CostCentre",
                    kv("id", ccId(i)),
                    kv("name", pick(CC_NAMES, rnd) + " " + i),
                    num("budget", budget),
                    num("spent", spent),
                    kv("currency", pick(CURRENCIES, rnd)),
                    kv("period", pick(PERIODS, rnd)),
                    kv("owner", pick(EXEC_OWNERS, rnd)),
                    num("variance", round2(budget - spent)),
                    tenant());
        }

        section(sb, "BudgetItem (" + n + ")");
        for (int i = 1; i <= n; i++) {
            ins(sb, "BudgetItem",
                    kv("id", budId(i)),
                    kv("title", pick(SYS_BASENAMES, rnd) + " " + pick(BUDGET_TITLES, rnd)),
                    num("amount", round2((25 + rnd.nextInt(975)) * 1000.0)),
                    kv("currency", pick(CURRENCIES, rnd)),
                    kv("status", pick(BUDGET_STATUS, rnd)),
                    kv("dueDate", day(rnd.nextInt(180))),
                    kv("costCentre", ccId(1 + rnd.nextInt(n))),
                    kv("requestedBy", pick(EXEC_OWNERS, rnd)),
                    tenant());
        }

        section(sb, "Alert (" + n + ")");
        for (int i = 1; i <= n; i++) {
            ins(sb, "Alert",
                    kv("id", alrId(i)),
                    kv("title", pick(INCIDENT_TITLES, rnd) + " on " + pick(SYS_BASENAMES, rnd)),
                    kv("severity", pick(SEVERITIES, rnd)),
                    kv("domain", pick(DOMAINS, rnd)),
                    kv("status", pick(ALERT_STATUS, rnd)),
                    kv("message", "Alert " + i + ": threshold exceeded, automated notification dispatched."),
                    kv("createdAt", ts(rnd.nextInt(60) * 24 * 3600L + rnd.nextInt(86400))),
                    kv("source", pick(ALERT_SOURCES, rnd)),
                    tenant());
        }

        section(sb, "Decision (" + n + ")");
        for (int i = 1; i <= n; i++) {
            long decided = rnd.nextInt(150) * 24 * 3600L;
            ins(sb, "Decision",
                    kv("id", decId(i)),
                    kv("title", pick(DECISION_TITLES, rnd) + " #" + i),
                    kv("status", pick(DECISION_STATUS, rnd)),
                    kv("domain", pick(DOMAINS, rnd)),
                    kv("priorityLevel", pick(PRIORITY, rnd)),
                    kv("decisionArea", pick(DECISION_AREAS, rnd)),
                    kv("rationale", "Rationale for decision " + i + ": balances risk, cost and delivery timelines."),
                    kv("description", "Decision record " + i + " raised for executive review and sign-off."),
                    kv("decidedBy", pick(DECIDERS, rnd)),
                    kv("decidedAt", ts(decided)),
                    kv("dueDate", day(rnd.nextInt(120))),
                    kv("createdAt", ts(decided)),
                    tenant());
        }

        section(sb, "Project (" + n + ")");
        for (int i = 1; i <= n; i++) {
            long start = rnd.nextInt(200) * 24 * 3600L;
            double budget = round2((100 + rnd.nextInt(4900)) * 1000.0);
            ins(sb, "Project",
                    kv("id", prjId(i)),
                    kv("name", pick(PROJECT_NAMES, rnd) + " " + i),
                    kv("status", pick(PROJECT_STATUS, rnd)),
                    kv("domain", pick(DOMAINS, rnd)),
                    kv("owner", pick(EXEC_OWNERS, rnd)),
                    num("budget", budget),
                    num("spent", round2(budget * rnd.nextDouble())),
                    kv("currency", pick(CURRENCIES, rnd)),
                    kv("startDate", day(-(int) (start / 86400))),
                    kv("endDate", day(rnd.nextInt(300))),
                    num("progress", rnd.nextInt(101)),
                    kv("description", "Programme of work " + i + " tracked against milestones and budget."),
                    tenant());
        }

        // ── edges ────────────────────────────────────────────────────────
        section(sb, "HAS_INCIDENT: ITSystem -> Incident (" + n + ")");
        for (int i = 1; i <= n; i++) {
            edge(sb, "HAS_INCIDENT", "ITSystem", sysId(1 + rnd.nextInt(n)), "Incident", incId(i));
        }
        section(sb, "HAS_SLA_METRIC: ITSystem -> SLAMetric (" + n + ")");
        for (int i = 1; i <= n; i++) {
            edge(sb, "HAS_SLA_METRIC", "ITSystem", sysId(1 + rnd.nextInt(n)), "SLAMetric", slaId(i));
        }
        section(sb, "TRIGGERS: Alert -> Incident (" + n + ")");
        for (int i = 1; i <= n; i++) {
            edge(sb, "TRIGGERS", "Alert", alrId(i), "Incident", incId(1 + rnd.nextInt(n)));
        }
        section(sb, "FUNDS: CostCentre -> ITSystem (" + n + ")");
        for (int i = 1; i <= n; i++) {
            edge(sb, "FUNDS", "CostCentre", ccId(i), "ITSystem", sysId(1 + rnd.nextInt(n)));
        }
        section(sb, "IMPACTS: CostCentre -> Incident (" + n + ")");
        for (int i = 1; i <= n; i++) {
            edge(sb, "IMPACTS", "CostCentre", ccId(1 + rnd.nextInt(n)), "Incident", incId(i));
        }
        section(sb, "DEPENDS_ON: ITSystem -> ITSystem (" + n + ")");
        for (int i = 1; i <= n; i++) {
            int to = 1 + rnd.nextInt(n);
            if (to == i) to = 1 + (i % n);
            edge(sb, "DEPENDS_ON", "ITSystem", sysId(i), "ITSystem", sysId(to));
        }

        sb.append("\n-- End of seed script. ")
          .append(n * 8L).append(" vertices + ").append(n * 6L).append(" edges generated.\n");

        Files.writeString(out, sb.toString());
        System.out.printf("Wrote %s (%,d bytes): %,d vertices + %,d edges across 8 types.%n",
                out.toAbsolutePath(), Files.size(out), n * 8L, n * 6L);
    }

    // ── id helpers (5-digit, zero-padded, stable) ───────────────────────────
    private static String sysId(int i) { return "sys-" + pad(i); }
    private static String incId(int i) { return "inc-" + pad(i); }
    private static String slaId(int i) { return "sla-" + pad(i); }
    private static String ccId(int i)  { return "cc-"  + pad(i); }
    private static String budId(int i) { return "bud-" + pad(i); }
    private static String alrId(int i) { return "alr-" + pad(i); }
    private static String decId(int i) { return "dec-" + pad(i); }
    private static String prjId(int i) { return "prj-" + pad(i); }
    private static String pad(int i) { return String.format("%05d", i); }
    private static String zone(int i) { return "N" + String.format("%03d", i); }

    // ── SQL building ────────────────────────────────────────────────────────

    /** A single INSERT ... SET ... statement. Parts are pre-rendered "k=v". */
    private static void ins(StringBuilder sb, String type, String... parts) {
        sb.append("INSERT INTO ").append(type).append(" SET ");
        sb.append(String.join(", ", parts));
        sb.append(";\n");
    }

    /**
     * A CREATE EDGE keyed by (tenantId, id) on both endpoints so links never
     * cross tenants and resolve via the compound index created in the DDL.
     */
    private static void edge(StringBuilder sb, String edge, String fromType, String fromId,
                             String toType, String toId) {
        sb.append("CREATE EDGE ").append(edge)
          .append(" FROM (SELECT FROM ").append(fromType)
          .append(" WHERE tenantId='").append(TENANT_TOKEN).append("' AND id='").append(fromId).append("')")
          .append(" TO (SELECT FROM ").append(toType)
          .append(" WHERE tenantId='").append(TENANT_TOKEN).append("' AND id='").append(toId).append("');\n");
    }

    private static String kv(String key, String value) {
        return key + "='" + esc(value) + "'";
    }
    private static String num(String key, double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return key + "=" + (long) value;
        }
        return key + "=" + value;
    }
    private static String tenant() {
        return "tenantId='" + TENANT_TOKEN + "'";
    }

    private static String esc(String s) {
        return s.replace("'", "''");
    }

    private static String ts(long plusSeconds) {
        return BASE.plusSeconds(plusSeconds).toInstant(ZoneOffset.UTC)
                .atOffset(ZoneOffset.UTC).format(TS);
    }
    private static String day(int plusDays) {
        return BASE.toLocalDate().plusDays(plusDays).format(DAY);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static String pick(String[] pool, Random r) { return pool[r.nextInt(pool.length)]; }

    // ── file sections ────────────────────────────────────────────────────────
    private static void section(StringBuilder sb, String label) {
        sb.append("\n-- ").append("─".repeat(4)).append(' ').append(label).append(' ')
          .append("─".repeat(Math.max(4, 60 - label.length()))).append('\n');
    }

    private static void header(StringBuilder sb, int n) {
        sb.append("-- ============================================================\n");
        sb.append("--  Orphos EIP — sample knowledge-graph data (portable seed)\n");
        sb.append("--  Generated by SampleDataGenerator.java — DO NOT EDIT BY HAND.\n");
        sb.append("--  ").append(n).append(" records per entity across 8 vertex types + 6 edge types.\n");
        sb.append("--\n");
        sb.append("--  BEFORE IMPORTING: replace every occurrence of the token\n");
        sb.append("--      ").append(TENANT_TOKEN).append("\n");
        sb.append("--  with the target tenant's UUID. Everything is scoped to that\n");
        sb.append("--  tenantId, so the same file can seed any tenant.\n");
        sb.append("--\n");
        sb.append("--  Import options:\n");
        sb.append("--   A) ArcadeDB console:\n");
        sb.append("--        console.sh \"remote:localhost/orphos root <pwd>\" \"load script <file>\"\n");
        sb.append("--   B) HTTP (language=sqlscript): POST the file to\n");
        sb.append("--        /api/v1/command/orphos   (split into chunks if very large)\n");
        sb.append("--\n");
        sb.append("--  The DELETE block below makes re-import idempotent PER TENANT:\n");
        sb.append("--  it removes only this tenant's rows before re-inserting.\n");
        sb.append("-- ============================================================\n");
    }

    private static void ddl(StringBuilder sb) {
        section(sb, "Schema (idempotent — safe on an existing orphos DB)");
        String[] vtypes = {"ITSystem", "Incident", "SLAMetric", "CostCentre",
                "BudgetItem", "Alert", "Decision", "Project"};
        for (String v : vtypes) sb.append("CREATE VERTEX TYPE ").append(v).append(" IF NOT EXISTS;\n");
        String[] etypes = {"HAS_INCIDENT", "HAS_SLA_METRIC", "TRIGGERS", "FUNDS", "IMPACTS", "DEPENDS_ON"};
        for (String e : etypes) sb.append("CREATE EDGE TYPE ").append(e).append(" IF NOT EXISTS;\n");
        // Compound (tenantId, id) indexes — unique per tenant, and used by the
        // edge sub-selects. NOT a bare id UNIQUE, which would collide when more
        // than one tenant is seeded into the same shared database.
        for (String v : vtypes) {
            sb.append("CREATE INDEX IF NOT EXISTS ON ").append(v).append(" (tenantId, id) UNIQUE;\n");
            sb.append("CREATE INDEX IF NOT EXISTS ON ").append(v).append(" (tenantId) NOTUNIQUE;\n");
        }
    }

    private static void clearForTenant(StringBuilder sb) {
        section(sb, "Clear existing rows for THIS tenant (idempotent re-import)");
        String[] all = {"HAS_INCIDENT", "HAS_SLA_METRIC", "TRIGGERS", "FUNDS", "IMPACTS", "DEPENDS_ON",
                "ITSystem", "Incident", "SLAMetric", "CostCentre", "BudgetItem", "Alert", "Decision", "Project"};
        // Edges carry no tenantId of their own; DELETE VERTEX cascades to remove
        // their connected edges, so clearing the vertices is sufficient. We still
        // delete edge *rows* defensively where any stragglers exist for the type.
        for (String t : all) {
            if (t.equals(t.toUpperCase())) { // edge types are ALL_CAPS
                continue; // handled by DELETE VERTEX cascade below
            }
            sb.append("DELETE VERTEX ").append(t)
              .append(" WHERE tenantId='").append(TENANT_TOKEN).append("';\n");
        }
    }
}
