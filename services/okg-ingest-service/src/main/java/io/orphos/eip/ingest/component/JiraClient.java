package io.orphos.eip.ingest.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orphos.eip.ingest.vo.JiraConnection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Thin REST client for Jira Cloud (e.g. {@code https://appverseai.atlassian.net}),
 * using API-token Basic Auth per Atlassian's documented Jira Cloud auth model:
 * {@code Authorization: Basic base64(email:apiToken)}.
 *
 * <p>Uses REST API v3. Only the fields the OKG ontology cares about are
 * requested — Jira issues can carry dozens of custom fields, most of them
 * irrelevant to graph ingestion and some potentially containing free-text PII,
 * so we deliberately request a narrow, fixed field list rather than
 * {@code fields=*all}.</p>
 */
@Component
public class JiraClient {

    private static final Logger log = LogManager.getLogger(JiraClient.class);

    /** Deliberately narrow — see class Javadoc. No email/PII-bearing fields requested. */
    private static final List<String> ISSUE_FIELDS = List.of(
            "summary", "status", "issuetype", "priority", "assignee", "reporter",
            "project", "duedate", "created", "updated", "issuelinks", "parent");

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int pageSize;

    public JiraClient(@Value("${okg.jira.timeout-seconds:30}") int timeoutSeconds,
                       @Value("${okg.jira.page-size:100}") int pageSize) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        this.pageSize = pageSize;
    }

    /** One page of the project directory. Jira paginates this endpoint too, but project counts are typically small. */
    public List<JsonNode> listAllProjects(JiraConnection conn) {
        List<JsonNode> out = new ArrayList<>();
        int startAt = 0;
        while (true) {
            JsonNode page = get(conn, "/rest/api/3/project/search?startAt=" + startAt + "&maxResults=" + pageSize);
            JsonNode values = page.path("values");
            if (!values.isArray() || values.isEmpty()) break;
            values.forEach(out::add);
            if (page.path("isLast").asBoolean(true)) break;
            startAt += pageSize;
        }
        return out;
    }

    /**
     * Runs {@code jql}, paginating through all results, invoking {@code onPage}
     * once per page so callers can persist incrementally instead of buffering
     * the whole result set (a tenant's backlog can be tens of thousands of issues).
     */
    public void searchAll(JiraConnection conn, String jql, java.util.function.Consumer<List<JsonNode>> onPage) {
        int startAt = 0;
        while (true) {
            ObjectNode body = mapper.createObjectNode();
            body.put("jql", jql);
            body.put("startAt", startAt);
            body.put("maxResults", pageSize);
            ArrayNodeHelper.addAll(body.putArray("fields"), ISSUE_FIELDS);

            JsonNode resp = post(conn, "/rest/api/3/search", body);
            JsonNode issues = resp.path("issues");
            if (!issues.isArray() || issues.isEmpty()) break;

            List<JsonNode> page = new ArrayList<>();
            issues.forEach(page::add);
            onPage.accept(page);

            int total = resp.path("total").asInt(0);
            startAt += page.size();
            if (startAt >= total || page.size() < pageSize) break;
        }
    }

    private JsonNode get(JiraConnection conn, String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(conn.baseUrl()) + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", basicAuth(conn))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return send(req);
        } catch (Exception e) {
            throw new JiraException("Jira GET " + path + " failed: " + e.getMessage(), e);
        }
    }

    private JsonNode post(JiraConnection conn, String path, ObjectNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(conn.baseUrl()) + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", basicAuth(conn))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            return send(req);
        } catch (Exception e) {
            throw new JiraException("Jira POST " + path + " failed: " + e.getMessage(), e);
        }
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new JiraException("Jira authentication failed (HTTP " + res.statusCode()
                    + ") — check the connection's email/API token");
        }
        if (res.statusCode() / 100 != 2) {
            throw new JiraException("Jira request failed: HTTP " + res.statusCode() + " " + truncate(res.body()));
        }
        return mapper.readTree(res.body());
    }

    private static String basicAuth(JiraConnection conn) {
        String raw = conn.email() + ":" + conn.apiToken();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    public static class JiraException extends RuntimeException {
        public JiraException(String message) { super(message); }
        public JiraException(String message, Throwable cause) { super(message, cause); }
    }

    /** Tiny helper so searchAll's field array literal stays readable. */
    private static final class ArrayNodeHelper {
        static void addAll(com.fasterxml.jackson.databind.node.ArrayNode arr, List<String> values) {
            values.forEach(arr::add);
        }
    }
}
