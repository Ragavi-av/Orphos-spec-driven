package io.orphos.eip.nlquery.repository;

import io.orphos.eip.common.access.PersonaPolicy;
import io.orphos.eip.common.access.PersonaPolicy.Snapshot;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.common.tenant.TenantDataUnavailableException;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.nlquery.component.QdrantIngestionRunner;
import io.orphos.eip.nlquery.component.RetrievalFederator;
import io.orphos.eip.nlquery.repository.PersonaPolicyStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/**
 * Builds LLM context strings from ArcadeDB data, filtered to the current tenant
 * AND to what the caller's persona is allowed to read.
 *
 * <p>Every query the builder runs includes {@code WHERE tenantId = :tenantId}.
 * The tenant is resolved from {@link TenantContextHolder} for request-scoped
 * calls (NlQueryController → NlQueryService → builder); background callers
 * (QdrantIngestionRunner) bind the context explicitly via
 * {@code TenantContextHolder.runWith} before calling
 * {@link #buildChunks()}.</p>
 *
 * <h2>Persona filtering (why it belongs here)</h2>
 * This class decides what the language model is told. Anything that reaches the
 * context can be paraphrased back to the user regardless of what the UI shows, so
 * this — not the SPA, and not the widget layer — is where data-level RBAC has to
 * bite. Before this existed, a CTO asking the co-pilot a general question received
 * aggregate payroll and cash-flow figures in the prompt.
 *
 * <p>Each section is now gated on the graph type it reads, via
 * {@link PersonaPolicy#allows}. The policy comes from the tenant's own
 * {@code Gov*} vertices ({@link PersonaPolicyStore}) and is a no-op for tenants
 * that have no persona vocabulary — see that class and {@code PersonaPolicy} for
 * the rollout-safety rules.</p>
 *
 * <h2>The corpus is filtered differently from the live context</h2>
 * {@link #buildLiveContext()} is request-scoped: the caller's roles are known, so
 * it filters per persona. {@link #buildChunkRecords()} builds the RAG corpus in a
 * background job with no principal, so per-persona filtering is impossible there
 * and would be wrong anyway — one corpus serves every persona. Instead the corpus
 * omits only what NO persona may ever read
 * ({@link PersonaPolicy#readableByNobody}), and each chunk carries its
 * {@code sourceType} so retrieval can apply the persona filter at query time.
 * <b>That retrieval-time filter is not implemented yet</b> — see the note on
 * {@link #buildChunkRecords()}.
 */
@Repository
public class ArcadeDbContextBuilder {

    private static final Logger log = LogManager.getLogger(ArcadeDbContextBuilder.class);
    private static final java.util.regex.Pattern IDENT = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final ArcadeDbTemplate arcade;
    private final PersonaPolicyStore personaPolicy;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Rows kept per entity type in the live context. Every row lands in the
     * briefing prompt, and on CPU-only inference the prompt's prefill is what
     * dominates answer latency — 13 entity types at 10 rows each was adding
     * thousands of tokens to every question. Raise it when answers need more
     * breadth and the latency budget allows.
     */
    private final int rowLimit;

    public ArcadeDbContextBuilder(ArcadeDbTemplate arcade,
                                  PersonaPolicyStore personaPolicy,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${nlquery.live-context.row-limit:4}") int rowLimit) {
        this.arcade = arcade;
        this.personaPolicy = personaPolicy;
        this.rowLimit = rowLimit;
    }

    /** Admin/shared DB — holds Tenant records (for DB resolution) and SHARED tenant data. */
    private static final String ADMIN_DB = "orphos";
    private final java.util.concurrent.ConcurrentHashMap<String, String> dbCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Per-(db, type) record of whether a vertex type is defined. Keyed "db|Type". */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> typeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether {@code type} is defined in the current tenant's database.
     *
     * <p>Guard every optional context query with this. Querying a type ArcadeDB
     * does not know throws {@code SchemaException} — surfaced as HTTP 500 — and
     * that aborts the surrounding transaction, so the failure does not stay
     * local: valid queries issued afterwards in the same request fail too. That
     * is how a sparsely-populated tenant (several here define none of the HR or
     * finance types) was knocking out the demo tenant's working cash-flow query.
     *
     * <p>Absent data is normal, not exceptional — a tenant that has onboarded no
     * HR feed simply contributes no workforce section. One cheap {@code
     * schema:types} lookup per db+type, cached for the process lifetime, keeps
     * that the quiet path it should always have been.
     */
    private boolean hasType(String type) {
        String db = resolveDb();
        return typeCache.computeIfAbsent(db + '|' + type, k -> {
            try {
                List<Map<String, Object>> rows = arcade.queryOn(db,
                        "SELECT name FROM schema:types WHERE name = :name", Map.of("name", type));
                return rows != null && !rows.isEmpty();
            } catch (Exception e) {
                // Cannot tell — let the caller try and handle its own failure
                // rather than silently dropping a section over a transient blip.
                log.debug("Type probe for {} in {} failed: {}", type, db, e.getMessage());
                return true;
            }
        });
    }

    /** Resolve the current tenant's data DB: orphos (SHARED) or eip_tenant_* (DEDICATED). */
    private String resolveDb() {
        try {
            String tid = TenantContextHolder.requireTenantId().toString();
            return dbCache.computeIfAbsent(tid, this::lookupDb);
        } catch (Exception e) {
            return ADMIN_DB;
        }
    }

    private String lookupDb(String tid) {
        try {
            List<Map<String, Object>> rows = arcade.queryOn(ADMIN_DB,
                    "SELECT arcadeDatabase FROM Tenant WHERE id = :id", Map.of("id", tid));
            if (!rows.isEmpty()) {
                Object db = rows.get(0).get("arcadeDatabase");
                if (db != null && !db.toString().isBlank()) return db.toString();
            }
        } catch (Exception ignored) { /* fall through to admin db */ }
        return ADMIN_DB;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public String buildLiveContext() {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Snapshot policy = personaPolicy.snapshot(resolveDb(), tenantId);
        Set<String> roles = PersonaPolicy.currentRoles();
        if (PersonaPolicy.enforced(policy) && !roles.isEmpty() && log.isDebugEnabled()) {
            log.debug("Persona filtering active (tenant={}, roles={})", tenantId, roles);
        }

        StringBuilder sb = new StringBuilder();
        if (permitted(policy, roles, "Incident")) {
            sb.append(queryToText(
                "SELECT id, title, severity, status, domain, affectedSystem FROM Incident " +
                "WHERE tenantId = :tenantId LIMIT " + rowLimit,
                "Active Incidents", tenantId));
        }
        if (permitted(policy, roles, "Decision")) {
            sb.append(queryToText(
                "SELECT id, title, status, decidedBy, decidedAt, domain, rationale, priority, nextAction, summary " +
                "FROM Decision WHERE tenantId = :tenantId LIMIT " + rowLimit,
                "Recent Decisions", tenantId));
        }
        if (permitted(policy, roles, "SLAMetric")) {
            sb.append(queryToText(
                "SELECT id, system, metricName, target, actual, unit, status FROM SLAMetric " +
                "WHERE tenantId = :tenantId LIMIT " + rowLimit,
                "SLA Metrics", tenantId));
        }
        if (permitted(policy, roles, "ITSystem")) {
            sb.append(queryToText(
                "SELECT id, name, type, status, owner FROM ITSystem " +
                "WHERE tenantId = :tenantId LIMIT " + rowLimit,
                "IT Systems", tenantId));
        }
        if (permitted(policy, roles, "CostCentre")) {
            sb.append(queryToText(
                "SELECT id, name, budget, spent, currency, variance FROM CostCentre " +
                "WHERE tenantId = :tenantId LIMIT " + rowLimit,
                "Cost Centres", tenantId));
        }
        if (permitted(policy, roles, "Alert")) {
            sb.append(queryToText(
                "SELECT id, title, severity, domain, status, message FROM Alert " +
                "WHERE tenantId = :tenantId AND status='ACTIVE' LIMIT " + rowLimit,
                "Active Alerts", tenantId));
        }
        // HR + Finance domains (e.g. the Zenitus tenant). Aggregate only — no
        // per-employee PII enters the LLM context. Sections are emitted only
        // when the tenant actually has this data, so IT-ops tenants are unaffected.
        // Both are Confidential finance/people data: gated, so a CTO no longer
        // receives payroll and cash-flow totals in the prompt.
        if (permitted(policy, roles, "Employee")) {
            sb.append(buildWorkforceContext(tenantId));
        }
        if (permitted(policy, roles, "FinCashFlowEntry")) {
            sb.append(buildCashFlowContext(tenantId));
        }
        // Enterprise-ontology domain data (governance/finance/HR/strategy/risk),
        // each section gated on its own type.
        sb.append(buildDomainContext(policy, roles, tenantId));
        // Generic: summarise any data onboarded via the Data & AI uploader
        // (TenantDataModel registry → per-entity vertex types). Aggregates only.
        sb.append(buildRegistryContext(tenantId));
        return sb.toString();
    }

    /**
     * True if the caller may be told about {@code graphType}. Unenforced tenants and
     * principal-less callers pass everything — see {@link PersonaPolicy}.
     */
    private boolean permitted(Snapshot policy, Set<String> roles, String graphType) {
        return PersonaPolicy.allows(policy, roles, graphType);
    }

    /**
     * Sections backed by the enterprise-ontology domain types loaded by
     * {@code LoadPersonaDomainProfile}. Each is gated independently, which is what
     * makes a CFO's context and a CTO's context genuinely different rather than the
     * same text with a different header.
     */
    private String buildDomainContext(Snapshot policy, Set<String> roles, String tenantId) {
        StringBuilder sb = new StringBuilder();
        if (permitted(policy, roles, "FinCashFlowEntry")) {
            sb.append(queryToText(
                "SELECT name, inflowAmount, outflowAmount, closingBalance, currency " +
                "FROM FinCashFlowEntry WHERE tenantId = :tenantId ORDER BY name LIMIT " + rowLimit,
                "Cash Flow by Period", tenantId));
        }
        if (permitted(policy, roles, "FinBudgetLine")) {
            sb.append(queryToText(
                "SELECT name, plannedAmount, actualAmount, committedAmount, currency " +
                "FROM FinBudgetLine WHERE tenantId = :tenantId LIMIT " + rowLimit,
                "Budget Lines", tenantId));
        }
        if (permitted(policy, roles, "HrHeadcountSnapshot")) {
            sb.append(queryToText(
                "SELECT name, headcount, fteCount FROM HrHeadcountSnapshot " +
                "WHERE tenantId = :tenantId LIMIT " + rowLimit,
                "Headcount Snapshots", tenantId));
        }
        if (permitted(policy, roles, "HrPayrollRun")) {
            sb.append(buildPayrollContext(tenantId));
        }
        if (permitted(policy, roles, "RskRisk")) {
            sb.append(queryToText(
                "SELECT name, riskCategory, residualSeverity, exposureAmount, riskStatus, currency " +
                "FROM RskRisk WHERE tenantId = :tenantId ORDER BY residualSeverity DESC LIMIT " + rowLimit,
                "Risk Register", tenantId));
        }
        if (permitted(policy, roles, "StrObjective")) {
            sb.append(queryToText(
                "SELECT name, objectiveStatus, horizon FROM StrObjective " +
                "WHERE tenantId = :tenantId LIMIT " + rowLimit,
                "Strategic Objectives", tenantId));
        }
        if (permitted(policy, roles, "StrInitiative")) {
            sb.append(queryToText(
                "SELECT name, ragStatus, percentComplete FROM StrInitiative " +
                "WHERE tenantId = :tenantId LIMIT " + rowLimit,
                "Initiatives", tenantId));
        }
        return sb.toString();
    }

    /**
     * Payroll aggregates, with the k-anonymity floor enforced.
     *
     * <p>{@code hr:minimumPopulation} (see {@code ontology/eip-hr.owl}) is the
     * smallest population an employment-cost aggregate may describe before it stops
     * being an aggregate in any meaningful sense: with a 4-person team and a known
     * headcount, a reader can approximate an individual salary by division. Rows
     * below their own floor are excluded in SQL — not filtered after formatting —
     * so the suppressed figure never enters the prompt string at all.</p>
     *
     * <p>The suppression is reported rather than silent: the model is told a period
     * was withheld, so it can say "not shown for privacy reasons" instead of
     * implying the team costs nothing.</p>
     */
    private String buildPayrollContext(String tenantId) {
        try {
            List<?> shown = executeQuery(
                "SELECT name, totalCost, coveredHeadcount, currency FROM HrPayrollRun " +
                "WHERE tenantId = :tenantId AND coveredHeadcount >= minimumPopulation LIMIT " + rowLimit,
                Map.of("tenantId", tenantId));
            long suppressed = asLong(scalar(
                "SELECT count(*) AS v FROM HrPayrollRun " +
                "WHERE tenantId = :tenantId AND coveredHeadcount < minimumPopulation", tenantId));
            if ((shown == null || shown.isEmpty()) && suppressed == 0) return "";

            StringBuilder sb = new StringBuilder("\n## Payroll Cost (aggregate)\n");
            for (Object r : shown == null ? List.of() : shown) {
                Map<?, ?> m = (Map<?, ?>) r;
                sb.append("- ").append(m.get("name")).append(": total employment cost ")
                  .append(m.get("totalCost")).append(" ").append(m.get("currency"))
                  .append(" across ").append(m.get("coveredHeadcount")).append(" people\n");
            }
            if (suppressed > 0) {
                sb.append("- NOTE: ").append(suppressed).append(" payroll aggregate(s) are withheld ")
                  .append("because they cover too few people to be reported without ")
                  .append("risking disclosure of an individual's pay. Do not estimate ")
                  .append("or infer the withheld figures.\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Payroll context query failed (tenant={}): {}", tenantId, e.getMessage());
            return "";
        }
    }

    /**
     * Summarises the tenant's onboarded entities (from the TenantDataModel
     * registry) generically: row counts, measure sums, and top category
     * breakdowns. Lets the co-pilot ground on arbitrary uploaded data with no
     * per-tenant code. Aggregates only — no row-level values enter the context.
     */
    @SuppressWarnings("unchecked")
    private String buildRegistryContext(String tenantId) {
        try {
            // The registry lives in the admin DB (orphos), keyed by tenantId —
            // read it there, but query the entity DATA from the tenant's own DB
            // (executeQuery resolves that per-tenant).
            List<?> reg = queryAdmin(
                "SELECT entitiesJson FROM TenantDataModel WHERE tenantId = :tenantId LIMIT 1",
                Map.of("tenantId", tenantId));
            if (reg == null || reg.isEmpty()) return "";
            Object ej = ((Map<String, Object>) reg.get(0)).get("entitiesJson");
            if (ej == null) return "";
            List<Map<String, Object>> entities = mapper.readValue(ej.toString(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> e : entities) {
                String cls = String.valueOf(e.get("className"));
                if (!IDENT.matcher(cls).matches()) continue;
                String label = String.valueOf(e.getOrDefault("label", cls));
                long count = asLong(scalar(
                        "SELECT count(*) AS v FROM " + cls + " WHERE tenantId = :tenantId", tenantId));
                if (count == 0) continue;

                StringBuilder body = new StringBuilder();
                body.append("- Total records: ").append(count).append("\n");
                List<Map<String, Object>> cols = (List<Map<String, Object>>) e.getOrDefault("columns", List.of());
                int measures = 0, cats = 0;
                for (Map<String, Object> col : cols) {
                    String role = String.valueOf(col.get("role"));
                    String prop = String.valueOf(col.get("property"));
                    String name = String.valueOf(col.getOrDefault("name", prop));
                    if (!IDENT.matcher(prop).matches()) continue;
                    if ("MEASURE".equals(role) && measures++ < 3) {
                        Object sum = scalar("SELECT sum(" + prop + ") AS v FROM " + cls
                                + " WHERE tenantId = :tenantId", tenantId);
                        if (sum != null) body.append("- Total ").append(name).append(": ").append(sum).append("\n");
                    } else if ("CATEGORY".equals(role) && cats++ < 2) {
                        List<?> rows = executeQuery("SELECT " + prop + " AS label, count(*) AS c FROM " + cls
                                + " WHERE tenantId = :tenantId GROUP BY " + prop + " ORDER BY c DESC LIMIT 5",
                                Map.of("tenantId", tenantId));
                        for (Object r : rows) {
                            Map<String, Object> m = (Map<String, Object>) r;
                            if (m.get("label") != null) {
                                body.append("- ").append(name).append(" ").append(m.get("label"))
                                    .append(": ").append(m.get("c")).append("\n");
                            }
                        }
                    }
                }
                sb.append("\n## ").append(label).append("\n").append(body);
            }
            return sb.toString();
        } catch (Exception ex) {
            log.warn("Registry context build failed (tenant={}): {}", tenantId, ex.getMessage());
            return "";
        }
    }

    private Object scalar(String sql, String tenantId) {
        List<?> rows = executeQuery(sql, Map.of("tenantId", tenantId));
        if (rows == null || rows.isEmpty()) return null;
        return ((Map<?, ?>) rows.get(0)).get("v");
    }

    /** Query the admin DB (orphos) directly — used for tenant-config types like
     *  TenantDataModel that always live centrally, regardless of tenant isolation. */
    private List<Map<String, Object>> queryAdmin(String sql, Map<String, Object> params) {
        try {
            return arcade.queryOn(ADMIN_DB, sql, params);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Workforce summary from the HR snapshot types: headcount per reported
     * segment/period, plus payroll runs.
     *
     * <p>Reads {@code HrHeadcountSnapshot} / {@code HrPayrollRun}, NOT a flat
     * {@code Employee} type — no tenant database defines one, so the previous
     * per-employee aggregate threw {@code SchemaException: Type with name
     * 'Employee' was not found} on every call. That exception aborted the
     * surrounding ArcadeDB transaction, which also took down the VALID cash-flow
     * query issued next in the same request, so a dead query was silently
     * costing us finance context too.</p>
     *
     * <p>Rows are listed rather than summed, deliberately. A snapshot's period
     * lives only inside its {@code name} ("Delivery headcount, Aug 2026" vs
     * "…, FY2027 Q1") — there is no period property to group by — so a
     * {@code sum(headcount)} spans periods and double-counts: 47 for a tenant
     * whose actual headcount is 23 in one period and 24 in the other. Listing
     * each labelled row lets the model read the period it was asked about
     * instead of us feeding it an invented total. Same shape as
     * {@link #buildCashFlowContext}, which lists entries for the same reason.</p>
     */
    private String buildWorkforceContext(String tenantId) {
        boolean haveHeads   = hasType("HrHeadcountSnapshot");
        boolean havePayroll = hasType("HrPayrollRun");
        if (!haveHeads && !havePayroll) return "";
        try {
            List<?> heads = !haveHeads ? List.of() : executeQuery(
                "SELECT name, headcount, fteCount FROM HrHeadcountSnapshot " +
                "WHERE tenantId = :tenantId ORDER BY name LIMIT " + rowLimit,
                Map.of("tenantId", tenantId));
            List<?> payroll = !havePayroll ? List.of() : executeQuery(
                "SELECT name, totalCost, coveredHeadcount, currency FROM HrPayrollRun " +
                "WHERE tenantId = :tenantId ORDER BY name LIMIT " + rowLimit,
                Map.of("tenantId", tenantId));

            boolean noHeads   = heads   == null || heads.isEmpty();
            boolean noPayroll = payroll == null || payroll.isEmpty();
            if (noHeads && noPayroll) return "";

            StringBuilder sb = new StringBuilder("\n## Workforce (HR)\n");
            if (!noHeads) {
                for (Object r : heads) {
                    Map<?, ?> m = (Map<?, ?>) r;
                    sb.append("- ").append(m.get("name"))
                      .append(": headcount ").append(asLong(m.get("headcount")));
                    if (m.get("fteCount") != null) {
                        sb.append(", ").append(m.get("fteCount")).append(" FTE");
                    }
                    sb.append('\n');
                }
            }
            if (!noPayroll) {
                for (Object r : payroll) {
                    Map<?, ?> m = (Map<?, ?>) r;
                    String currency = m.get("currency") == null || m.get("currency").toString().isBlank()
                            ? "INR" : m.get("currency").toString();
                    sb.append("- ").append(m.get("name"))
                      .append(": payroll cost ").append(asLong(m.get("totalCost")))
                      .append(' ').append(currency);
                    if (m.get("coveredHeadcount") != null) {
                        sb.append(", covering ").append(asLong(m.get("coveredHeadcount")))
                          .append(" people");
                    }
                    sb.append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Workforce context query failed (tenant={}): {}", tenantId, e.getMessage());
            return "";
        }
    }

    /**
     * Per-period cash flow: receipts (inflow), payments (outflow), net position.
     *
     * <p>Reads {@code FinCashFlowEntry} — the enterprise-ontology type the finance
     * loader actually populates. An earlier revision queried {@code CashFlowMonth},
     * which no tenant database defines, so every call threw and the section was
     * silently dropped; "what is the net cash flow" then had no figure to cite and
     * the model declined under the prompt's scope guard.</p>
     *
     * <p>The net per period is derived rather than read: the stored rows carry
     * inflow, outflow and closing balance but no net column.</p>
     */
    private String buildCashFlowContext(String tenantId) {
        if (!hasType("FinCashFlowEntry")) return "";
        try {
            List<?> rows = executeQuery(
                "SELECT name, inflowAmount, outflowAmount, closingBalance, currency " +
                "FROM FinCashFlowEntry WHERE tenantId = :tenantId ORDER BY name LIMIT " + rowLimit,
                Map.of("tenantId", tenantId));
            if (rows == null || rows.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("\n## Cash Flow (Finance)\n");
            long totalIn = 0L, totalOut = 0L;
            String currency = "INR";
            for (Object r : rows) {
                Map<?, ?> m = (Map<?, ?>) r;
                long in  = asLong(m.get("inflowAmount"));
                long out = asLong(m.get("outflowAmount"));
                totalIn  += in;
                totalOut += out;
                if (m.get("currency") != null && !m.get("currency").toString().isBlank()) {
                    currency = m.get("currency").toString();
                }
                sb.append("- ").append(m.get("name"))
                  .append(": receipts (cash inflow) ").append(in).append(' ').append(currency)
                  .append(", payments (cash outflow) ").append(out).append(' ').append(currency)
                  .append(", net cash flow ").append(in - out).append(' ').append(currency);
                if (m.get("closingBalance") != null) {
                    sb.append(", closing cash balance ")
                      .append(asLong(m.get("closingBalance"))).append(' ').append(currency);
                }
                sb.append('\n');
            }
            sb.append("- Across all reported periods: total receipts ").append(totalIn)
              .append(' ').append(currency).append(", total payments ").append(totalOut)
              .append(' ').append(currency).append(", overall net cash flow ")
              .append(totalIn - totalOut).append(' ').append(currency).append('\n');
            return sb.toString();
        } catch (Exception e) {
            log.warn("Cash flow context query failed (tenant={}): {}", tenantId, e.getMessage());
            return "";
        }
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? 0L : (long) Double.parseDouble(o.toString().trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

    /**
     * A retrievable chunk plus its provenance: the source record type and, for
     * per-record chunks, the record id. Aggregate summaries (workforce, cash
     * flow, onboarded data) carry a null id — they summarise many records.
     * Written into the Qdrant payload at ingestion so retrieval can report
     * <em>which</em> records fed an answer (see the trust layer's sources).
     */
    public record Chunk(String text, String sourceType, String sourceId) {}

    /** Backward-compatible view returning only the chunk text. */
    public List<String> buildChunks() {
        return buildChunkRecords().stream().map(Chunk::text).collect(Collectors.toList());
    }

    /**
     * Chunks with provenance metadata, used by the RAG ingestion runner.
     *
     * <p><b>Not persona-filtered, deliberately.</b> This runs as a background job
     * with no principal, and one corpus serves every persona, so filtering here
     * would either be impossible (no roles) or wrong (a CFO-shaped corpus that a
     * CTO then queries). What it DOES do is refuse to index anything classified at a
     * sensitivity no persona may read — defence in depth, so a retrieval bug cannot
     * surface data that was never meant to leave the graph.</p>
     *
     * <p><b>Known gap:</b> the persona filter at RETRIEVAL time is not implemented.
     * Each chunk carries {@code sourceType}, which is written into the Qdrant
     * payload, so {@code RetrievalFederator} can filter on it using the same
     * {@link PersonaPolicy} call this class uses — but until it does, semantic
     * retrieval can still surface a Confidential chunk to a persona that the live
     * context would have withheld. Tracked as the remaining step-3 item.</p>
     */
    public List<Chunk> buildChunkRecords() {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Snapshot policy = personaPolicy.snapshot(resolveDb(), tenantId);
        List<Chunk> chunks = new ArrayList<>();
        appendChunks(chunks, policy,
            "SELECT id, title, severity, status, domain, affectedSystem, createdAt FROM Incident " +
            "WHERE tenantId = :tenantId LIMIT 20",
            "Incident", tenantId);
        appendChunks(chunks, policy,
            "SELECT id, title, status, decidedBy, decidedAt, domain, rationale, priority, nextAction, summary " +
            "FROM Decision WHERE tenantId = :tenantId LIMIT 20",
            "Decision", tenantId);
        appendChunks(chunks, policy,
            "SELECT id, system, metricName, target, actual, unit, status, measuredAt FROM SLAMetric " +
            "WHERE tenantId = :tenantId LIMIT 20",
            "SLAMetric", tenantId);
        appendChunks(chunks, policy,
            "SELECT id, name, type, status, owner, description FROM ITSystem " +
            "WHERE tenantId = :tenantId LIMIT 20",
            "ITSystem", tenantId);
        appendChunks(chunks, policy,
            "SELECT id, name, budget, spent, currency, variance FROM CostCentre " +
            "WHERE tenantId = :tenantId LIMIT 20",
            "CostCentre", tenantId);
        // HR + Finance aggregate summaries (no PII) so semantic retrieval can also
        // surface workforce/cash-flow context for tenants like Zenitus. The chunk's
        // sourceType is the GRAPH type, not a label, so a retrieval-time persona
        // filter can decide on it with the same PersonaPolicy call.
        if (!PersonaPolicy.readableByNobody(policy, "Employee")) {
            String workforce = buildWorkforceContext(tenantId);
            if (workforce != null && !workforce.isBlank()) chunks.add(new Chunk(workforce.trim(), "Employee", null));
        }
        if (!PersonaPolicy.readableByNobody(policy, "CashFlowMonth")) {
            String cashflow = buildCashFlowContext(tenantId);
            if (cashflow != null && !cashflow.isBlank()) chunks.add(new Chunk(cashflow.trim(), "CashFlowMonth", null));
        }
        String registry = buildRegistryContext(tenantId);
        if (registry != null && !registry.isBlank()) chunks.add(new Chunk(registry.trim(), "OnboardedData", null));
        return chunks;
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String formatRow(Object rawRow, String section) {
        if (!(rawRow instanceof Map)) return String.valueOf(rawRow);
        Map<String, Object> row = (Map<String, Object>) rawRow;

        switch (section) {
            case "Active Incidents":
                return String.format(
                    "%s — %s severity, currently %s (domain: %s%s)",
                    val(row, "title"),
                    val(row, "severity"),
                    val(row, "status"),
                    val(row, "domain"),
                    hasVal(row, "affectedSystem") ? ", affects " + val(row, "affectedSystem") : "");
            case "Recent Decisions":
                return narrateDecision(row);
            case "SLA Metrics":
                return String.format(
                    "%s for system %s: actual %s %s vs target %s %s — %s",
                    val(row, "metricName"),
                    val(row, "system"),
                    val(row, "actual"),
                    val(row, "unit"),
                    val(row, "target"),
                    val(row, "unit"),
                    val(row, "status"));
            case "IT Systems":
                return String.format(
                    "%s: status %s, owned by %s",
                    val(row, "name"),
                    val(row, "status"),
                    val(row, "owner"));
            case "Cost Centres":
                return String.format(
                    "%s: budget %s %s, spent %s %s (variance %s %s)",
                    val(row, "name"),
                    val(row, "budget"),
                    val(row, "currency"),
                    val(row, "spent"),
                    val(row, "currency"),
                    val(row, "variance"),
                    val(row, "currency"));
            case "Active Alerts":
                return String.format(
                    "%s — %s severity in %s domain. %s",
                    val(row, "title"),
                    val(row, "severity"),
                    val(row, "domain"),
                    val(row, "message"));
            default:
                return row.entrySet().stream()
                    .filter(e -> e.getValue() != null
                        && !"null".equals(String.valueOf(e.getValue()))
                        && !String.valueOf(e.getValue()).isBlank())
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining(", "));
        }
    }

    @SuppressWarnings("unchecked")
    private String formatChunkRow(Object rawRow, String type) {
        if (!(rawRow instanceof Map)) return type + ": " + rawRow;
        Map<String, Object> row = (Map<String, Object>) rawRow;

        switch (type) {
            case "Incident":
                return String.format(
                    "Incident: %s (ID %s) — %s severity, status %s, domain %s%s",
                    val(row, "title"), val(row, "id"),
                    val(row, "severity"), val(row, "status"), val(row, "domain"),
                    hasVal(row, "affectedSystem") ? ", affected system: " + val(row, "affectedSystem") : "");
            case "Decision":
                return "Decision (ID " + val(row, "id") + "): " + narrateDecision(row);
            case "SLAMetric":
                return String.format(
                    "SLA Metric: %s for system %s — actual %s %s vs target %s %s, status %s",
                    val(row, "metricName"), val(row, "system"),
                    val(row, "actual"), val(row, "unit"),
                    val(row, "target"), val(row, "unit"),
                    val(row, "status"));
            case "ITSystem":
                return String.format(
                    "IT System: %s (ID %s) — type %s, status %s, owner %s%s",
                    val(row, "name"), val(row, "id"),
                    val(row, "type"), val(row, "status"), val(row, "owner"),
                    hasVal(row, "description") ? ". " + val(row, "description") : "");
            case "CostCentre":
                return String.format(
                    "Cost Centre: %s — budget %s %s, spent %s %s, variance %s %s",
                    val(row, "name"),
                    val(row, "budget"), val(row, "currency"),
                    val(row, "spent"), val(row, "currency"),
                    val(row, "variance"), val(row, "currency"));
            default:
                return type + ": " + formatRow(rawRow, "");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String narrateDecision(Map<String, Object> row) {
        String title   = val(row, "title");
        String status  = val(row, "status");
        String domain  = val(row, "domain");

        StringBuilder sb = new StringBuilder();
        sb.append(title).append(" [").append(domain).append(" domain]");

        switch (status.toUpperCase()) {
            case "PENDING":
                sb.append(" is currently PENDING and requires an executive decision.");
                if (hasVal(row, "nextAction")) {
                    sb.append(" Next required action: ").append(val(row, "nextAction")).append(".");
                } else {
                    sb.append(" No decision-maker has been formally assigned yet — CEO or relevant executive sign-off is needed to move this forward.");
                }
                if (hasVal(row, "priority")) {
                    sb.append(" Priority level: ").append(val(row, "priority")).append(".");
                }
                break;
            case "APPROVED":
                sb.append(" has been APPROVED");
                if (hasVal(row, "decidedBy")) {
                    sb.append(" by ").append(val(row, "decidedBy"));
                    if (hasVal(row, "decidedAt")) {
                        sb.append(" on ").append(date(row, "decidedAt"));
                    }
                }
                sb.append(".");
                break;
            case "REJECTED":
                sb.append(" was REJECTED");
                if (hasVal(row, "decidedBy")) {
                    sb.append(" by ").append(val(row, "decidedBy"));
                    if (hasVal(row, "decidedAt")) {
                        sb.append(" on ").append(date(row, "decidedAt"));
                    }
                }
                sb.append(".");
                break;
            default:
                sb.append(" — status: ").append(status).append(".");
        }

        if (hasVal(row, "summary")) {
            sb.append(" Summary: ").append(val(row, "summary")).append(".");
        } else if (hasVal(row, "rationale")) {
            sb.append(" Rationale: ").append(val(row, "rationale")).append(".");
        }

        if (!hasVal(row, "rationale") && !hasVal(row, "summary") && "PENDING".equalsIgnoreCase(status)) {
            sb.append(" No rationale has been recorded — the decision is in early review stage.");
        }

        return sb.toString();
    }

    private String val(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null || "null".equals(String.valueOf(v))) return "unknown";
        return String.valueOf(v).trim();
    }

    private boolean hasVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null && !"null".equals(String.valueOf(v)) && !String.valueOf(v).isBlank();
    }

    private String date(Map<String, Object> row, String key) {
        String s = val(row, key);
        return (s.length() > 10 && s.contains("T")) ? s.substring(0, 10) : s;
    }

    // ── Query execution ───────────────────────────────────────────────────────

    private String queryToText(String sql, String section, String tenantId) {
        try {
            List<?> rows = executeQuery(sql, Map.of("tenantId", tenantId));
            if (rows == null || rows.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n## ").append(section).append("\n");
            for (Object row : rows) {
                sb.append("- ").append(formatRow(row, section)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("ArcadeDB context query failed for {} (tenant={}): {}", section, tenantId, e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private void appendChunks(List<Chunk> chunks, String sql, String type, String tenantId) {
        appendChunks(chunks, null, sql, type, tenantId);
    }

    /**
     * As above, but skips types classified beyond every persona's reach. Passing a
     * null policy indexes unconditionally (used by the no-policy overload).
     */
    @SuppressWarnings("unchecked")
    private void appendChunks(List<Chunk> chunks, Snapshot policy, String sql, String type, String tenantId) {
        if (policy != null && PersonaPolicy.readableByNobody(policy, type)) {
            log.debug("Skipping {} in RAG corpus — no persona may read it", type);
            return;
        }
        try {
            List<?> rows = executeQuery(sql, Map.of("tenantId", tenantId));
            if (rows == null) return;
            for (Object row : rows) {
                String id = (row instanceof Map) ? val((Map<String, Object>) row, "id") : null;
                if ("unknown".equals(id)) id = null;
                chunks.add(new Chunk(formatChunkRow(row, type), type, id));
            }
        } catch (TenantDataUnavailableException e) {
            // Propagate so the SPA's degraded-data banner fires; we deliberately
            // skip the swallow-and-log path used for other failures because a
            // tenant DB outage is a first-class signal the user needs to see.
            throw e;
        } catch (Exception e) {
            log.warn("ArcadeDB chunk query failed for {} (tenant={}): {}", type, tenantId, e.getMessage());
        }
    }

    private List<Map<String, Object>> executeQuery(String sql, Map<String, Object> params) {
        // ArcadeDbTemplate wraps the call with ArcadeFailureTranslator internally,
        // converting 5xx / I/O failures into TenantDataUnavailableException so the
        // SPA can render its banner — same behavior as the hand-rolled call this
        // replaced.
        return arcade.queryOn(resolveDb(), sql, params);
    }
}
