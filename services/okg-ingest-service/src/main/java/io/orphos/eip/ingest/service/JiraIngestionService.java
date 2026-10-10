package io.orphos.eip.ingest.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeConnectionResolver;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.ingest.repository.OkgConnectionRepository;
import io.orphos.eip.ingest.repository.OkgGraphRepository;
import io.orphos.eip.ingest.component.JiraClient;
import io.orphos.eip.ingest.vo.JiraConnection;
import io.orphos.eip.ingest.component.SecretRefService;
import io.orphos.eip.ingest.service.TenantInfoCache;
import io.orphos.eip.ingest.vo.TenantRoutingInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Syncs the current tenant's Jira instance into the ontology graph.
 *
 * <p>Flow: resolve the tenant's stored {@code OkgConnection} → resolve the
 * vault-referenced API token → resolve which ArcadeDB database to write to
 * (SHARED tenants share {@code orphos}; DEDICATED tenants get their own) →
 * pull projects and issues from Jira → upsert into the graph.</p>
 *
 * <p>Must be called with a tenant already bound via {@link TenantContextHolder}
 * (the scheduler and the Kafka listener both do this via {@code TenantTaskExecutor}
 * / an explicit {@code runWith} respectively).</p>
 */
@Service
public class JiraIngestionService {

    private static final Logger log = LogManager.getLogger(JiraIngestionService.class);
    public static final String SOURCE = "JIRA";

    private final OkgConnectionRepository connections;
    private final OkgGraphRepository graph;
    private final JiraClient jira;
    private final SecretRefService secrets;
    private final TenantInfoCache tenantInfo;
    private final ArcadeConnectionResolver resolver;

    public JiraIngestionService(OkgConnectionRepository connections, OkgGraphRepository graph,
                                 JiraClient jira, SecretRefService secrets, TenantInfoCache tenantInfo,
                                 ArcadeConnectionResolver resolver) {
        this.connections = connections;
        this.graph = graph;
        this.jira = jira;
        this.secrets = secrets;
        this.tenantInfo = tenantInfo;
        this.resolver = resolver;
    }

    public record SyncResult(int projectsSynced, int issuesSynced, int linksSynced) {}

    /** Syncs the tenant bound to the current thread. Throws on any failure — callers decide how to report it. */
    public SyncResult syncCurrentTenant() {
        UUID tenantId = TenantContextHolder.requireTenantId();

        Map<String, Object> connRow = connections.findConnection(SOURCE);
        if (connRow == null || !Boolean.TRUE.equals(connRow.get("enabled"))) {
            log.debug("No enabled Jira connection for tenant {} — skipping sync", tenantId);
            return new SyncResult(0, 0, 0);
        }

        String apiTokenRef = String.valueOf(connRow.getOrDefault("apiTokenRef", ""));
        Optional<String> token = secrets.resolve(apiTokenRef);
        if (token.isEmpty()) {
            throw new IllegalStateException("Jira connection for tenant " + tenantId
                    + " has no resolvable API token (ref=" + apiTokenRef + ")");
        }

        JiraConnection conn = new JiraConnection(
                String.valueOf(connRow.getOrDefault("baseUrl", "")),
                String.valueOf(connRow.getOrDefault("email", "")),
                token.get(),
                String.valueOf(connRow.getOrDefault("projectKeys", "")));

        TenantRoutingInfo routing = tenantInfo.find(tenantId)
                .filter(TenantRoutingInfo::active)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot resolve routing (or tenant inactive) for " + tenantId + " — refusing to ingest"));
        String targetDb = routing.dataset();
        // Route writes to the ArcadeDB server hosting this tenant's database
        // (its chosen DB Connection; the platform default when unset).
        ArcadeDbTemplate arcade = resolver.templateFor(routing.arcadeConnectionId());

        int[] counts = {0, 0, 0}; // projects, issues, links

        for (JsonNode project : jira.listAllProjects(conn)) {
            String key = project.path("key").asText(null);
            String name = project.path("name").asText(key);
            if (key == null) continue;
            if (!inScope(conn, key)) continue;
            graph.upsertProject(arcade, targetDb, tenantId, SOURCE, key, name);
            counts[0]++;
        }

        String jql = buildJql(conn);
        jira.searchAll(conn, jql, page -> {
            for (JsonNode issue : page) {
                ingestIssue(arcade, targetDb, tenantId, issue, counts);
            }
        });

        connections.recordSyncResult(SOURCE, true, null);
        log.info("Jira sync complete for tenant {}: {} projects, {} issues, {} links",
                tenantId, counts[0], counts[1], counts[2]);
        return new SyncResult(counts[0], counts[1], counts[2]);
    }

    private void ingestIssue(ArcadeDbTemplate arcade, String targetDb, UUID tenantId, JsonNode issue, int[] counts) {
        String issueKey = issue.path("key").asText(null);
        if (issueKey == null) return;
        JsonNode fields = issue.path("fields");

        JsonNode assignee = fields.path("assignee");
        JsonNode reporter = fields.path("reporter");
        String assigneeId = person(arcade, targetDb, tenantId, assignee);
        String reporterId = person(arcade, targetDb, tenantId, reporter);

        String projectKey = fields.path("project").path("key").asText(null);
        String statusName = fields.path("status").path("name").asText("");
        String statusCategory = fields.path("status").path("statusCategory").path("name").asText("");

        graph.upsertIssue(arcade, targetDb, new OkgGraphRepository.IssueRecord(
                tenantId, SOURCE, issueKey, projectKey,
                fields.path("summary").asText(""),
                statusName, statusCategory,
                fields.path("issuetype").path("name").asText(""),
                fields.path("priority").path("name").asText(null),
                assigneeId, reporterId,
                textOrNull(fields, "duedate"),
                textOrNull(fields, "created"),
                textOrNull(fields, "updated")));
        counts[1]++;

        JsonNode links = fields.path("issuelinks");
        if (links.isArray()) {
            for (JsonNode link : links) {
                ingestLink(arcade, targetDb, tenantId, issueKey, link, counts);
            }
        }
    }

    private String person(ArcadeDbTemplate arcade, String targetDb, UUID tenantId, JsonNode personNode) {
        if (personNode == null || personNode.isMissingNode() || personNode.isNull()) return null;
        String accountId = personNode.path("accountId").asText(null);
        if (accountId == null) return null;
        String displayName = personNode.path("displayName").asText("");
        graph.upsertPerson(arcade, targetDb, tenantId, SOURCE, accountId, displayName);
        return accountId;
    }

    private void ingestLink(ArcadeDbTemplate arcade, String targetDb, UUID tenantId, String fromIssueKey, JsonNode link, int[] counts) {
        JsonNode type = link.path("type");
        JsonNode outward = link.path("outwardIssue");
        JsonNode inward = link.path("inwardIssue");
        if (!outward.isMissingNode()) {
            String toKey = outward.path("key").asText(null);
            String linkType = type.path("outward").asText("relates to");
            if (toKey != null) {
                graph.upsertIssueLink(arcade, targetDb, tenantId, SOURCE, fromIssueKey, toKey, normalizeLinkType(linkType));
                counts[2]++;
            }
        }
        if (!inward.isMissingNode()) {
            String fromKey = inward.path("key").asText(null);
            String linkType = type.path("inward").asText("relates to");
            if (fromKey != null) {
                graph.upsertIssueLink(arcade, targetDb, tenantId, SOURCE, fromKey, fromIssueKey, normalizeLinkType(linkType));
                counts[2]++;
            }
        }
    }

    /** Jira's free-text link phrasing ("is blocked by", "blocks", ...) collapsed to a small fixed vocabulary the ontology understands. */
    private static String normalizeLinkType(String jiraPhrase) {
        String p = jiraPhrase == null ? "" : jiraPhrase.toLowerCase();
        if (p.contains("block")) return p.contains("blocked") ? "blockedBy" : "blocks";
        if (p.contains("duplicate")) return "duplicates";
        return "relatesTo";
    }

    private boolean inScope(JiraConnection conn, String projectKey) {
        String[] scope = conn.projectKeyList();
        if (scope.length == 0) return true; // empty = sync every visible project
        for (String k : scope) {
            if (k.equalsIgnoreCase(projectKey)) return true;
        }
        return false;
    }

    private static String buildJql(JiraConnection conn) {
        String[] keys = conn.projectKeyList();
        if (keys.length == 0) return "ORDER BY updated DESC";
        String in = String.join(",", keys);
        return "project in (" + in + ") ORDER BY updated DESC";
    }

    private static String textOrNull(JsonNode fields, String field) {
        JsonNode v = fields.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText(null);
    }
}
