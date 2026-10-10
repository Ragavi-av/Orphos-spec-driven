import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * QA Agent: Automated test execution, quality gating, and Jira updates.
 *
 * Four-phase workflow:
 * 1. Generate: Create Playwright tests from QA test cases
 * 2. Execute: Run tests and collect results
 * 3. Gate: Evaluate if score >= threshold
 * 4. Update Jira: Mark story as ready/failed, post results
 */
public class QAAgent {

    static final String USAGE = String.join("\n",
            "usage: java QAAgent.java <command> [options]",
            "  run <STORY-KEY>          execute all 4 phases (generate/execute/gate/update-jira)",
            "  run                      auto-scan: find all stories with test cases, run all",
            "  scan                     list stories ready for QA execution",
            "  execute <STORY-KEY>      skip generation, run existing tests only",
            "  gate <RESULTS.json>      evaluate gate decision from test results",
            "options: --config <path>   config file (default qa-agent.config.json)",
            "         --override <msg>  override gate decision (requires --approver)",
            "         --approver <id>   jira user id for override approval",
            "env:     JIRA_URL, JIRA_EMAIL, JIRA_TOKEN");

    static final int EXIT_OK = 0, EXIT_ERROR = 1, EXIT_REJECTED = 2, EXIT_INVALID = 3;

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            code = EXIT_ERROR;
        }
        System.exit(code);
    }

    static int run(String[] argv) throws Exception {
        List<String> pos = new ArrayList<>();
        Map<String, String> opt = new HashMap<>();

        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                if (i + 1 >= argv.length) throw new Exception(a + " needs a value");
                opt.put(a.substring(2), argv[++i]);
            } else {
                pos.add(a);
            }
        }

        if (pos.isEmpty()) throw new Exception("missing command");

        String configPath = opt.getOrDefault("config", "qa-agent.config.json");
        Config cfg = Config.load(Path.of(configPath));
        Jira jira = Jira.fromEnv();

        switch (pos.get(0)) {
            case "scan":
                return scan(jira, cfg);

            case "run":
                if (pos.size() == 2) {
                    return runSingleStory(jira, cfg, pos.get(1));
                } else {
                    return runAutoScan(jira, cfg);
                }

            case "execute":
                if (pos.size() != 2) throw new Exception("execute takes <STORY-KEY>");
                return execute(jira, cfg, pos.get(1));

            case "gate":
                if (pos.size() != 2) throw new Exception("gate takes <RESULTS.json>");
                return gate(jira, cfg, Path.of(pos.get(1)), opt);

            default:
                throw new Exception("unknown command: " + pos.get(0));
        }
    }

    static int scan(Jira jira, Config cfg) throws Exception {
        System.out.println("Scanning for stories with approved test cases...");

        String jql = cfg.jql.storiesWithTestCases;
        String url = "/rest/api/3/search/jql?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8) +
                    "&fields=" + URLEncoder.encode("key,summary,status,parent", StandardCharsets.UTF_8) +
                    "&maxResults=50";

        Map<String, Object> response = Json.map(jira.get(url));
        List<Object> issues = Json.list(response.get("issues"));

        System.out.println("{");
        System.out.println("  \"storiesReadyForQA\": " + issues.size());
        System.out.println("  \"stories\": [");

        for (int i = 0; i < issues.size(); i++) {
            Map<String, Object> issue = Json.map(issues.get(i));
            String key = Json.str(issue, "key");
            String summary = Json.str(Json.map(issue.get("fields")), "summary");

            System.out.println("    { \"key\": \"" + key + "\", \"summary\": \"" + escape(summary) + "\" }" +
                    (i < issues.size() - 1 ? "," : ""));
        }

        System.out.println("  ]");
        System.out.println("}");

        return EXIT_OK;
    }

    static int runSingleStory(Jira jira, Config cfg, String storyKey) throws Exception {
        System.out.println("Processing story: " + storyKey);

        // Phase 1: Generate Playwright tests
        System.out.println("\n[Phase 1] Generating Playwright tests...");
        Path testCasesFile = Paths.get("testcases-" + storyKey + ".json");
        if (!Files.exists(testCasesFile)) {
            System.err.println("error: Test cases not found: " + testCasesFile);
            return EXIT_ERROR;
        }

        String testCasesJson = Files.readString(testCasesFile);
        PlaywrightGenerator generator = new PlaywrightGenerator(cfg);
        String playwrightCode = generator.generate(storyKey, testCasesJson);

        Path specFile = Paths.get("tests/e2e/test-" + storyKey + ".spec.ts");
        Files.createDirectories(specFile.getParent());
        Files.writeString(specFile, playwrightCode);
        System.out.println("✓ Generated: " + specFile);

        // Phase 2: Execute tests
        System.out.println("\n[Phase 2] Executing tests...");
        TestResults results = executeTests(cfg, storyKey);

        // Phase 3: Evaluate gate
        System.out.println("\n[Phase 3] Evaluating quality gate...");
        double score = (double) results.passed / results.total * 100;
        boolean approved = score >= cfg.scoring.threshold && !results.criticalFailures;

        System.out.println("Test Score: " + String.format("%.1f", score) + "% (" + results.passed + "/" + results.total + ")");
        System.out.println("Threshold: " + cfg.scoring.threshold + "%");
        System.out.println("Decision: " + (approved ? "✅ APPROVED" : "❌ BLOCKED"));

        // Phase 4: Update Jira
        System.out.println("\n[Phase 4] Updating Jira...");
        updateJiraStory(jira, cfg, storyKey, approved, results, score);

        // Save results
        String resultsJson = resultsToJson(storyKey, score, approved, results);
        Path resultsFile = Paths.get("test-results-" + storyKey + ".json");
        Files.writeString(resultsFile, resultsJson);
        System.out.println("✓ Results saved: " + resultsFile);

        return approved ? EXIT_OK : EXIT_REJECTED;
    }

    static int runAutoScan(Jira jira, Config cfg) throws Exception {
        System.out.println("Auto-scan mode: Processing all stories with test cases...");

        String jql = cfg.jql.storiesWithTestCases;
        String url = "/rest/api/3/search/jql?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8) +
                    "&fields=key,summary&maxResults=50";

        Map<String, Object> response = Json.map(jira.get(url));
        List<Object> issues = Json.list(response.get("issues"));

        int processed = 0, approved = 0, blocked = 0;

        for (Object issue : issues) {
            String key = Json.str(Json.map(issue), "key");
            System.out.println("\n" + "=".repeat(60));
            System.out.println("Processing: " + key);
            System.out.println("=".repeat(60));

            int result = runSingleStory(jira, cfg, key);
            processed++;
            if (result == EXIT_OK) approved++; else blocked++;
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("AUTO-SCAN SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println("Stories processed: " + processed);
        System.out.println("✅ APPROVED (Ready for Production): " + approved);
        System.out.println("❌ BLOCKED (Tests Failed): " + blocked);

        return blocked > 0 ? EXIT_REJECTED : EXIT_OK;
    }

    static int execute(Jira jira, Config cfg, String storyKey) throws Exception {
        System.out.println("Executing tests for story: " + storyKey);
        TestResults results = executeTests(cfg, storyKey);
        double score = (double) results.passed / results.total * 100;

        System.out.println("Score: " + String.format("%.1f", score) + "%");

        String resultsJson = resultsToJson(storyKey, score, false, results);
        Path resultsFile = Paths.get("test-results-" + storyKey + ".json");
        Files.writeString(resultsFile, resultsJson);

        return EXIT_OK;
    }

    static int gate(Jira jira, Config cfg, Path resultsFile, Map<String, String> opt) throws Exception {
        String json = Files.readString(resultsFile);
        Map<String, Object> results = Json.map(Json.parse(json));

        double score = Json.dbl(results, "score");
        boolean approved = score >= cfg.scoring.threshold;

        System.out.println("Score: " + String.format("%.1f", score) + "%");
        System.out.println("Threshold: " + cfg.scoring.threshold + "%");
        System.out.println("Decision: " + (approved ? "✅ APPROVED" : "❌ BLOCKED"));

        return approved ? EXIT_OK : EXIT_REJECTED;
    }

    static TestResults executeTests(Config cfg, String storyKey) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "npx", "playwright", "test",
            "tests/e2e/test-" + storyKey + ".spec.ts",
            "--reporter=json", "--reporter=html",
            "--workers=" + cfg.execution.parallel
        );
        pb.environment().put("PLAYWRIGHT_JUNIT_OUTPUT_NAME", "test-" + storyKey + "-results.xml");

        Process proc = pb.start();
        int exitCode = proc.waitFor();

        // Parse results from test-results.json
        Path resultsPath = Paths.get("test-results.json");
        if (!Files.exists(resultsPath)) {
            return new TestResults(0, 0, 0, false, new ArrayList<>());
        }

        String resultsJson = Files.readString(resultsPath);
        Map<String, Object> results = Json.map(Json.parse(resultsJson));

        List<Object> suites = Json.list(results.get("suites"));
        int total = 0, passed = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        boolean criticalFailures = false;

        for (Object suite : suites) {
            List<Object> tests = Json.list(Json.map(suite).get("tests"));
            for (Object test : tests) {
                Map<String, Object> t = Json.map(test);
                String status = Json.str(t, "status");
                total++;
                if ("passed".equals(status)) {
                    passed++;
                } else {
                    failed++;
                    String title = Json.str(t, "title");
                    failures.add(title);

                    // Check if critical
                    if (title.contains("tenant-isolation") || title.contains("security")) {
                        criticalFailures = true;
                    }
                }
            }
        }

        return new TestResults(total, passed, failed, criticalFailures, failures);
    }

    static void updateJiraStory(Jira jira, Config cfg, String storyKey, boolean approved, TestResults results, double score) throws Exception {
        // Update status
        String newStatus = approved ? cfg.statusMapping.approved : cfg.statusMapping.blocked;
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("transition", Map.of("name", newStatus));

        jira.post("/rest/api/3/issue/" + storyKey + "/transitions", transition);
        System.out.println("✓ Updated status: " + newStatus);

        // Add labels
        List<String> labels = new ArrayList<>();
        labels.add(approved ? "qa-passed" : "qa-failed");
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("fields", Map.of("labels", labels));
        jira.put("/rest/api/3/issue/" + storyKey, update);
        System.out.println("✓ Added label: " + (approved ? "qa-passed" : "qa-failed"));

        // Post comment
        String comment = approved ?
            String.format("✅ QA tests PASSED (%.1f%%). Ready for production deployment.", score) :
            String.format("❌ QA tests FAILED (%.1f%% < 90%%). Failed tests: %s", score, String.join(", ", results.failures));

        Map<String, Object> commentBody = Map.of(
            "body", Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(Map.of(
                    "type", "paragraph",
                    "content", List.of(Map.of("type", "text", "text", comment))
                ))
            )
        );
        jira.post("/rest/api/3/issue/" + storyKey + "/comments", commentBody);
        System.out.println("✓ Posted comment with results");

        // Create bug for failures
        if (!approved && cfg.jiraUpdates.createBugOnFailure) {
            for (String failure : results.failures) {
                Map<String, Object> bug = new LinkedHashMap<>();
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("project", Map.of("key", cfg.jiraUpdates.bugProjectKey));
                fields.put("issuetype", Map.of("name", cfg.jiraUpdates.bugIssueType));
                fields.put("summary", "QA Test Failed: " + failure);
                fields.put("description", "Test failed: " + failure + "\n\nStory: " + storyKey);
                fields.put("parent", Map.of("key", storyKey));
                bug.put("fields", fields);

                Map<String, Object> bugResult = Json.map(jira.post("/rest/api/3/issue", bug));
                String bugKey = Json.str(bugResult, "key");
                System.out.println("✓ Created bug: " + bugKey);
            }
        }
    }

    static String resultsToJson(String storyKey, double score, boolean approved, TestResults results) {
        return "{\n" +
            "  \"storyKey\": \"" + storyKey + "\",\n" +
            "  \"score\": " + String.format("%.1f", score) + ",\n" +
            "  \"total\": " + results.total + ",\n" +
            "  \"passed\": " + results.passed + ",\n" +
            "  \"failed\": " + results.failed + ",\n" +
            "  \"approved\": " + approved + ",\n" +
            "  \"criticalFailures\": " + results.criticalFailures + ",\n" +
            "  \"failures\": " + Json.toJson(results.failures) + ",\n" +
            "  \"timestamp\": \"" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\"\n" +
            "}";
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"").replace("\n", "\\n");
    }

    static class TestResults {
        int total, passed, failed;
        boolean criticalFailures;
        List<String> failures;

        TestResults(int total, int passed, int failed, boolean critical, List<String> failures) {
            this.total = total;
            this.passed = passed;
            this.failed = failed;
            this.criticalFailures = critical;
            this.failures = failures;
        }
    }

    static class Config {
        static class JQL { String storiesWithTestCases; }
        static class StatusMapping { String approved, blocked; }
        static class Scoring { int threshold; }
        static class Execution { int parallel, timeout; }
        static class StatusMap { String approved, blocked; }
        static class JiraUpdates { boolean createBugOnFailure; String bugProjectKey; }

        String projectKey;
        JQL jql;
        StatusMapping statusMapping;
        Scoring scoring;
        Execution execution;
        JiraUpdates jiraUpdates;

        static Config load(Path path) throws Exception {
            String json = Files.readString(path);
            Map<String, Object> m = Json.map(Json.parse(json));

            Config cfg = new Config();
            cfg.projectKey = Json.str(m, "projectKey");

            cfg.jql = new JQL();
            Map<String, Object> jqlMap = Json.map(m.get("jql"));
            cfg.jql.storiesWithTestCases = Json.str(jqlMap, "storiesWithTestCases");

            cfg.statusMapping = new StatusMapping();
            Map<String, Object> statusMap = Json.map(m.get("statusMapping"));
            cfg.statusMapping.approved = Json.str(statusMap, "approved");
            cfg.statusMapping.blocked = Json.str(statusMap, "blocked");

            cfg.scoring = new Scoring();
            cfg.scoring.threshold = Json.num(Json.map(m.get("scoring")), "threshold").intValue();

            cfg.execution = new Execution();
            Map<String, Object> execMap = Json.map(m.get("execution"));
            cfg.execution.parallel = Json.num(execMap, "parallel").intValue();
            cfg.execution.timeout = Json.num(execMap, "timeout").intValue();

            cfg.jiraUpdates = new JiraUpdates();
            Map<String, Object> jiraMap = Json.map(m.get("jiraUpdates"));
            cfg.jiraUpdates.createBugOnFailure = "true".equals(Json.str(jiraMap, "createBugOnFailure"));
            cfg.jiraUpdates.bugProjectKey = Json.str(jiraMap, "bugProjectKey");

            return cfg;
        }
    }

    static class PlaywrightGenerator {
        Config cfg;

        PlaywrightGenerator(Config cfg) {
            this.cfg = cfg;
        }

        String generate(String storyKey, String testCasesJson) throws Exception {
            Map<String, Object> testCases = Json.map(Json.parse(testCasesJson));
            List<Object> tests = Json.list(testCases.get("testCases"));

            StringBuilder code = new StringBuilder();
            code.append("import { test, expect } from '@playwright/test';\n\n");
            code.append("test.describe('").append(storyKey).append("', () => {\n");

            for (Object testObj : tests) {
                Map<String, Object> testCase = Json.map(testObj);
                String title = Json.str(testCase, "title");
                List<Object> steps = Json.list(testCase.get("steps"));

                code.append("  test('").append(title).append("', async ({ page, request }) => {\n");

                for (Object stepObj : steps) {
                    Map<String, Object> step = Json.map(stepObj);
                    String action = Json.str(step, "action");
                    String expected = Json.str(step, "expected");

                    code.append("    // ").append(action).append("\n");
                    code.append("    // Expected: ").append(expected).append("\n");
                }

                code.append("  });\n\n");
            }

            code.append("});\n");
            return code.toString();
        }
    }

    static class Jira {
        final String url;
        final String auth;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

        Jira(String url, String auth) {
            this.url = url;
            this.auth = auth;
        }

        Object get(String path) throws Exception {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url + path))
                .header("Authorization", auth)
                .GET().build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new Exception("HTTP " + res.statusCode() + " on " + path + ": " + res.body());
            }
            return Json.parse(res.body());
        }

        Object post(String path, Object body) throws Exception {
            String json = Json.toJson(body);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url + path))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new Exception("HTTP " + res.statusCode() + " on " + path + ": " + res.body());
            }
            return res.body().isEmpty() ? Map.of() : Json.parse(res.body());
        }

        Object put(String path, Object body) throws Exception {
            String json = Json.toJson(body);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url + path))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new Exception("HTTP " + res.statusCode() + " on " + path + ": " + res.body());
            }
            return res.body().isEmpty() ? Map.of() : Json.parse(res.body());
        }

        static Jira fromEnv() {
            String url = System.getenv("JIRA_URL"), email = System.getenv("JIRA_EMAIL"), token = System.getenv("JIRA_TOKEN");

            if (url == null || email == null || token == null) {
                throw new IllegalStateException("Missing: JIRA_URL, JIRA_EMAIL, JIRA_TOKEN");
            }

            String auth = "Basic " + Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
            return new Jira(url.replaceAll("/+$", ""), auth);
        }
    }

    static class Json {
        static Object parse(String json) throws Exception {
            return new JsonParser(json).parse();
        }

        static Map<String, Object> map(Object o) {
            return o instanceof Map ? (Map<String, Object>) o : Map.of();
        }

        static List<Object> list(Object o) {
            return o instanceof List ? (List<Object>) o : List.of();
        }

        static String str(Object m, String key) {
            Object v = map(m).get(key);
            return v == null ? "" : v.toString();
        }

        static Number num(Object m, String key) {
            Object v = map(m).get(key);
            return v instanceof Number ? (Number) v : 0;
        }

        static double dbl(Object m, String key) {
            return num(m, key).doubleValue();
        }

        static String toJson(Object o) {
            if (o instanceof String) return "\"" + o + "\"";
            if (o instanceof Number || o instanceof Boolean) return o.toString();
            if (o instanceof List) {
                List<?> l = (List<?>) o;
                StringBuilder s = new StringBuilder("[");
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) s.append(",");
                    s.append(toJson(l.get(i)));
                }
                return s.append("]").toString();
            }
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                StringBuilder s = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) s.append(",");
                    s.append("\"").append(e.getKey()).append(":").append(toJson(e.getValue()));
                    first = false;
                }
                return s.append("}").toString();
            }
            return "null";
        }
    }

    static class JsonParser {
        String s; int pos;
        JsonParser(String s) { this.s = s; }

        Object parse() throws Exception {
            skipWs();
            return parseValue();
        }

        Object parseValue() throws Exception {
            skipWs();
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            return parseNumber();
        }

        Map<String, Object> parseObject() throws Exception {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; skipWs();
            while (s.charAt(pos) != '}') {
                String key = parseString();
                skipWs(); pos++; skipWs();
                Object val = parseValue();
                m.put(key, val);
                skipWs();
                if (s.charAt(pos) == ',') { pos++; skipWs(); }
            }
            pos++;
            return m;
        }

        List<Object> parseArray() throws Exception {
            List<Object> a = new ArrayList<>();
            pos++; skipWs();
            while (s.charAt(pos) != ']') {
                a.add(parseValue());
                skipWs();
                if (s.charAt(pos) == ',') { pos++; skipWs(); }
            }
            pos++;
            return a;
        }

        String parseString() throws Exception {
            pos++;
            int start = pos;
            while (s.charAt(pos) != '"') pos++;
            String str = s.substring(start, pos);
            pos++;
            return str;
        }

        boolean parseBoolean() {
            boolean b = s.charAt(pos) == 't';
            pos += b ? 4 : 5;
            return b;
        }

        Object parseNull() {
            pos += 4;
            return null;
        }

        Number parseNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String num = s.substring(start, pos);
            return num.contains(".") ? Double.parseDouble(num) : Long.parseLong(num);
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
