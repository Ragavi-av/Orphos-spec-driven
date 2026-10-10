package io.orphos.eip.ingest.repository;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.ingest.component.JiraClient;
import io.orphos.eip.ingest.service.TenantInfoCache;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Upserts ontology-graph entities (projects, people, issues, issue links) into
 * ArcadeDB, routed to the specific database {@code targetDb} names — the
 * caller resolves {@code targetDb} once per tenant via {@code TenantInfoCache}
 * (SHARED tenants share {@code orphos}; DEDICATED tenants get their own
 * database), so this class stays tenant-routing-agnostic and just executes
 * against whichever database it's told to.
 *
 * <p>Every write is a manual upsert (SELECT existing by {@code (tenantId, externalId)},
 * then UPDATE or INSERT) — ArcadeDB has no native {@code MERGE}/{@code UPSERT}
 * statement, matching the convention used by every other repository in this
 * codebase (e.g. {@code MailboxConfigRepository.saveConfig}).</p>
 */
@Repository
public class OkgGraphRepository {

    // Stateless: the routed ArcadeDbTemplate is passed in per call (resolved by
    // the caller from the tenant's connection), so this repo can write to any
    // tenant's ArcadeDB server without holding a single injected template.

    // ── Project ───────────────────────────────────────────────────────────

    public void upsertProject(ArcadeDbTemplate arcade, String targetDb, UUID tenantId, String source, String externalId, String name) {
        String tenant = tenantId.toString();
        Map<String, Object> existing = findOneOn(arcade, targetDb, "OkgProject", tenant, externalId);
        String now = Instant.now().toString();
        if (existing != null) {
            arcade.commandNoResultOn(targetDb,
                    "UPDATE OkgProject SET name = :name, updatedAt = :now " +
                    "WHERE tenantId = :tenantId AND externalId = :externalId",
                    Map.of("name", name, "now", now, "tenantId", tenant, "externalId", externalId));
        } else {
            arcade.commandNoResultOn(targetDb,
                    "INSERT INTO OkgProject SET id = :id, tenantId = :tenantId, source = :source, " +
                    "externalId = :externalId, name = :name, createdAt = :now, updatedAt = :now",
                    Map.of("id", "prj-" + UUID.randomUUID(), "tenantId", tenant, "source", source,
                            "externalId", externalId, "name", name, "now", now));
        }
    }

    // ── Person ────────────────────────────────────────────────────────────

    /**
     * Upserts a person by their external account id. Deliberately does NOT
     * accept or store an email address — see {@link io.orphos.eip.ingest.component.JiraClient}'s
     * narrow field list for the same PII-minimisation rationale.
     */
    public void upsertPerson(ArcadeDbTemplate arcade, String targetDb, UUID tenantId, String source, String externalId, String displayName) {
        if (externalId == null || externalId.isBlank()) return;
        String tenant = tenantId.toString();
        Map<String, Object> existing = findOneOn(arcade, targetDb, "OkgPerson", tenant, externalId);
        String now = Instant.now().toString();
        if (existing != null) {
            arcade.commandNoResultOn(targetDb,
                    "UPDATE OkgPerson SET displayName = :dn, updatedAt = :now " +
                    "WHERE tenantId = :tenantId AND externalId = :externalId",
                    Map.of("dn", displayName == null ? "" : displayName, "now", now,
                            "tenantId", tenant, "externalId", externalId));
        } else {
            arcade.commandNoResultOn(targetDb,
                    "INSERT INTO OkgPerson SET id = :id, tenantId = :tenantId, source = :source, " +
                    "externalId = :externalId, displayName = :dn, createdAt = :now, updatedAt = :now",
                    Map.of("id", "per-" + UUID.randomUUID(), "tenantId", tenant, "source", source,
                            "externalId", externalId, "dn", displayName == null ? "" : displayName, "now", now));
        }
    }

    // ── Issue ─────────────────────────────────────────────────────────────

    public void upsertIssue(ArcadeDbTemplate arcade, String targetDb, IssueRecord r) {
        String tenant = r.tenantId().toString();
        Map<String, Object> existing = findOneOn(arcade, targetDb, "OkgIssue", tenant, r.externalId());
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenant);
        p.put("externalId", r.externalId());
        p.put("projectExternalId", r.projectExternalId());
        p.put("summary", r.summary());
        p.put("statusName", r.statusName());
        p.put("statusCategory", r.statusCategory());
        p.put("issueTypeName", r.issueTypeName());
        p.put("priorityName", r.priorityName() == null ? "" : r.priorityName());
        p.put("assigneeExternalId", r.assigneeExternalId() == null ? "" : r.assigneeExternalId());
        p.put("reporterExternalId", r.reporterExternalId() == null ? "" : r.reporterExternalId());
        p.put("dueDate", r.dueDate() == null ? "" : r.dueDate());
        p.put("jiraCreatedAt", r.jiraCreatedAt() == null ? "" : r.jiraCreatedAt());
        p.put("jiraUpdatedAt", r.jiraUpdatedAt() == null ? "" : r.jiraUpdatedAt());
        String now = Instant.now().toString();
        p.put("syncedAt", now);

        if (existing != null) {
            arcade.commandNoResultOn(targetDb,
                    "UPDATE OkgIssue SET summary = :summary, statusName = :statusName, " +
                    "statusCategory = :statusCategory, issueTypeName = :issueTypeName, " +
                    "priorityName = :priorityName, assigneeExternalId = :assigneeExternalId, " +
                    "reporterExternalId = :reporterExternalId, dueDate = :dueDate, " +
                    "jiraUpdatedAt = :jiraUpdatedAt, syncedAt = :syncedAt " +
                    "WHERE tenantId = :tenantId AND externalId = :externalId", p);
        } else {
            p.put("id", "iss-" + UUID.randomUUID());
            p.put("source", r.source());
            p.put("createdAt", now);
            arcade.commandNoResultOn(targetDb,
                    "INSERT INTO OkgIssue SET id = :id, tenantId = :tenantId, source = :source, " +
                    "externalId = :externalId, projectExternalId = :projectExternalId, summary = :summary, " +
                    "statusName = :statusName, statusCategory = :statusCategory, issueTypeName = :issueTypeName, " +
                    "priorityName = :priorityName, assigneeExternalId = :assigneeExternalId, " +
                    "reporterExternalId = :reporterExternalId, dueDate = :dueDate, " +
                    "jiraCreatedAt = :jiraCreatedAt, jiraUpdatedAt = :jiraUpdatedAt, " +
                    "createdAt = :createdAt, syncedAt = :syncedAt", p);
        }
    }

    public record IssueRecord(
            UUID tenantId, String source, String externalId, String projectExternalId,
            String summary, String statusName, String statusCategory, String issueTypeName,
            String priorityName, String assigneeExternalId, String reporterExternalId,
            String dueDate, String jiraCreatedAt, String jiraUpdatedAt) {}

    // ── Issue links (dependencies) ───────────────────────────────────────

    /**
     * Creates a directed, typed relationship between two issue vertices as a real
     * ArcadeDB <b>edge</b> ({@code OkgIssueLink}). The from/to external ids and
     * linkType are also stored as edge properties so the idempotency check stays
     * a plain indexed lookup (no graph traversal needed to dedup).
     *
     * <p>An edge requires both endpoint vertices to exist. Jira can reference an
     * issue not yet ingested (out of scope, or later in the result page), so a
     * minimal stub {@code OkgIssue} vertex is created for any missing endpoint;
     * a later {@link #upsertIssue} fills it in (same {@code tenantId+externalId}
     * key), turning the stub into the full record.</p>
     */
    public void upsertIssueLink(ArcadeDbTemplate arcade, String targetDb, UUID tenantId, String source,
                                 String fromExternalId, String toExternalId, String linkType) {
        String tenant = tenantId.toString();
        List<Map<String, Object>> existing = arcade.queryOn(targetDb,
                "SELECT FROM OkgIssueLink WHERE tenantId = :tenantId AND fromExternalId = :from " +
                "AND toExternalId = :to AND linkType = :type LIMIT 1",
                Map.of("tenantId", tenant, "from", fromExternalId, "to", toExternalId, "type", linkType));
        if (!existing.isEmpty()) return; // links are immutable facts — nothing to update

        ensureIssueStub(arcade, targetDb, tenant, source, fromExternalId);
        ensureIssueStub(arcade, targetDb, tenant, source, toExternalId);

        arcade.commandNoResultOn(targetDb,
                "CREATE EDGE OkgIssueLink " +
                "FROM (SELECT FROM OkgIssue WHERE tenantId = :tenantId AND externalId = :from LIMIT 1) " +
                "TO (SELECT FROM OkgIssue WHERE tenantId = :tenantId AND externalId = :to LIMIT 1) " +
                "SET tenantId = :tenantId, source = :source, linkType = :type, " +
                "fromExternalId = :from, toExternalId = :to, createdAt = :now",
                Map.of("tenantId", tenant, "source", source, "type", linkType,
                        "from", fromExternalId, "to", toExternalId, "now", Instant.now().toString()));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Ensures a minimal {@code OkgIssue} vertex exists for {@code externalId} so an
     * edge can attach to it. Idempotent; a full {@link #upsertIssue} later updates
     * the same vertex (matched on {@code tenantId+externalId}).
     */
    private void ensureIssueStub(ArcadeDbTemplate arcade, String targetDb, String tenant, String source, String externalId) {
        if (externalId == null || externalId.isBlank()) return;
        if (findOneOn(arcade, targetDb, "OkgIssue", tenant, externalId) != null) return;
        String now = Instant.now().toString();
        arcade.commandNoResultOn(targetDb,
                "INSERT INTO OkgIssue SET id = :id, tenantId = :tenantId, source = :source, " +
                "externalId = :externalId, summary = '', statusName = '', statusCategory = '', " +
                "issueTypeName = '', createdAt = :now, syncedAt = :now",
                Map.of("id", "iss-" + UUID.randomUUID(), "tenantId", tenant, "source", source,
                        "externalId", externalId, "now", now));
    }

    private Map<String, Object> findOneOn(ArcadeDbTemplate arcade, String targetDb, String type, String tenantId, String externalId) {
        List<Map<String, Object>> rows = arcade.queryOn(targetDb,
                "SELECT FROM " + type + " WHERE tenantId = :tenantId AND externalId = :externalId LIMIT 1",
                Map.of("tenantId", tenantId, "externalId", externalId));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
