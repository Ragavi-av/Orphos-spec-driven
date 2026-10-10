import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Test Case agent helper for the {@code test-case-agent} Claude skill.
 *
 * <p>Single-file, dependency-free (JDK 21 source launcher):
 * {@code java TestCaseAgent.java <command> ...}. Talks to Jira Cloud REST v3 with
 * API-token Basic Auth from {@code JIRA_URL}, {@code JIRA_EMAIL}, {@code JIRA_TOKEN}.
 * Credentials are never printed.</p>
 *
 * <p>Same fail-closed approval gate as the story-agent helper, applied to a Jira
 * <em>task</em>: test cases are released and created only for an approved task whose
 * approval is genuine. {@code create} validates every test case first (all or nothing),
 * enforces scenario coverage and acceptance-criteria traceability, drops duplicates, and
 * re-runs the gate immediately before writing.</p>
 *
 * <p>Output never carries e-mail addresses or display names - people are identified by
 * Jira accountId only.</p>
 */
public class TestCaseAgent {

    static final String USAGE = String.join("\n",
            "usage: java TestCaseAgent.java <command> [options]",
            "  scan     [--story KEY] [--project KEY] [--jql \"...\"]   approved tasks, gate verdict + existing test case count",
            "  read     <TASK-KEY>                                   gate verdict + task content + AC list + existing test cases (JSON)",
            "  validate <TASK-KEY> <testcases.json>                  offline structure/coverage/duplicate check (no Jira)",
            "  create   <TASK-KEY> <testcases.json> [--dry-run]      validate, re-check gate, drop duplicates, create + link",
            "options: --config <path>  (default .claude/skills/test-case-agent/test-case-agent.config.json)",
            "env:     JIRA_URL, JIRA_EMAIL, JIRA_TOKEN",
            "exit:    0 ok | 2 gate rejected | 3 test case validation failed | 1 error | 64 usage");

    static final int EXIT_OK = 0, EXIT_ERROR = 1, EXIT_REJECTED = 2, EXIT_INVALID = 3, EXIT_USAGE = 64;

    /** Scenario types a test case may declare. */
    static final List<String> TYPES = List.of("positive", "negative", "edge", "validation", "tenant-isolation");
    /** Types that must either be present or explicitly declared not applicable (with a reason). */
    static final List<String> COVERAGE_TYPES = List.of("negative", "edge", "validation", "tenant-isolation");
    static final Set<String> AUTOMATION = Set.of("unit", "integration", "e2e", "manual");

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (UsageException e) {
            System.err.println("usage error: " + e.getMessage());
            System.err.println(USAGE);
            code = EXIT_USAGE;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            code = EXIT_ERROR;
        }
        System.exit(code);
    }

    static int run(String[] argv) throws Exception {
        List<String> pos = new ArrayList<>();
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.equals("--dry-run")) opt.put("dry-run", "true");
            else if (a.startsWith("--")) {
                if (i + 1 >= argv.length) throw new UsageException(a + " needs a value");
                opt.put(a.substring(2), argv[++i]);
            } else pos.add(a);
        }
        if (pos.isEmpty()) throw new UsageException("missing command");

        Config cfg = Config.load(Path.of(opt.getOrDefault("config",
                ".claude/skills/test-case-agent/test-case-agent.config.json")));

        switch (pos.get(0)) {
            case "scan":
                return scan(Jira.fromEnv(), cfg, opt.get("story"), opt.get("project"), opt.get("jql"));
            case "read":
                if (pos.size() != 2) throw new UsageException("read takes exactly one task key");
                return read(Jira.fromEnv(), cfg, pos.get(1));
            case "validate":
                if (pos.size() != 3) throw new UsageException("validate takes <TASK-KEY> <testcases.json>");
                return validateOffline(cfg, pos.get(1), Path.of(pos.get(2)));
            case "create":
                if (pos.size() != 3) throw new UsageException("create takes <TASK-KEY> <testcases.json>");
                return create(cfg, pos.get(1), Path.of(pos.get(2)), opt.containsKey("dry-run"));
            default:
                throw new UsageException("unknown command " + pos.get(0));
        }
    }

    // ------------------------------------------------------------------ commands

    static int scan(Jira jira, Config cfg, String story, String project, String jqlOverride) throws Exception {
        String jql = jqlOverride;
        if (jql == null) {
            StringBuilder sb = new StringBuilder("issuetype in (").append(quoted(cfg.taskIssueTypes))
                    .append(") AND status in (").append(quoted(cfg.approvedStatuses)).append(")");
            if (story != null) sb.append(" AND parent = ").append(story);
            String p = project != null ? project : cfg.projectKey;
            if (p != null && story == null) sb.append(" AND project = \"").append(p).append('"');
            sb.append(" ORDER BY rank ASC");
            jql = sb.toString();
        }
        List<Map<String, Object>> tasks = jira.search(jql, List.of("summary", "status"), cfg.scanLimit);
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> t : tasks) {
            String key = Json.str(t, "key");
            Gate g = Gate.evaluate(jira, cfg, key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("summary", Json.str(t, "fields", "summary"));
            row.put("status", Json.str(t, "fields", "status", "name"));
            row.put("story", g.approval.get("storyKey"));
            row.put("gate", g.approved ? "APPROVED" : "REJECTED");
            row.put("failedChecks", g.failedCheckNames());
            row.put("existingTestCases", g.approved ? existingTests(jira, cfg, key).size() : null);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jql", jql);
        out.put("tasks", rows);
        System.out.println(Json.write(out));
        return EXIT_OK;
    }

    static int read(Jira jira, Config cfg, String key) throws Exception {
        Gate g = Gate.evaluate(jira, cfg, key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskKey", key);
        out.put("gate", g.toJson());
        if (g.approved) {
            // Content is only released for an approved task, so nothing downstream can plan from an unapproved one.
            Map<String, Object> f = Json.map(g.issue.get("fields"));
            String text = Adf.toText(f.get("description"));
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("summary", Json.str(f, "summary"));
            task.put("description", text);
            task.put("project", Json.str(f, "project", "key"));
            task.put("story", g.approval.get("storyKey"));
            task.put("priority", Json.str(f, "priority", "name"));
            task.put("labels", f.getOrDefault("labels", List.of()));
            out.put("task", task);

            // Numbered so test cases can cite them in "covers" (AC1, AC2, ...).
            Map<String, Object> ac = new LinkedHashMap<>();
            List<String> criteria = extractCriteria(text);
            for (int i = 0; i < criteria.size(); i++) ac.put("AC" + (i + 1), criteria.get(i));
            out.put("acceptanceCriteria", ac);

            List<Object> tests = new ArrayList<>();
            for (Map<String, Object> t : existingTests(jira, cfg, key)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", Json.str(t, "key"));
                row.put("status", Json.str(t, "fields", "status", "name"));
                row.put("summary", Json.str(t, "fields", "summary"));
                tests.add(row);
            }
            out.put("existingTestCases", tests);
        }
        System.out.println(Json.write(out));
        return g.approved ? EXIT_OK : EXIT_REJECTED;
    }

    static int validateOffline(Config cfg, String key, Path file) throws Exception {
        Map<String, Object> doc = Json.map(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        List<Object> problems = validateDoc(cfg, key, doc, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskKey", key);
        out.put("valid", problems.isEmpty());
        out.put("problems", problems);
        out.put("matrix", matrix(doc));
        System.out.println(Json.write(out));
        return problems.isEmpty() ? EXIT_OK : EXIT_INVALID;
    }

    static int create(Config cfg, String key, Path file, boolean dryRun) throws Exception {
        Map<String, Object> doc = Json.map(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        Jira jira = Jira.fromEnv();

        // Gate first: no content is even examined for an unapproved task.
        Gate g = Gate.evaluate(jira, cfg, key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskKey", key);
        out.put("dryRun", dryRun);
        out.put("gate", g.toJson());
        if (!g.approved) {
            out.put("created", List.of());
            System.out.println(Json.write(out));
            return EXIT_REJECTED;
        }

        // Validate everything before touching Jira: all test cases are created, or none.
        List<String> criteriaIds = new ArrayList<>();
        List<String> criteria = extractCriteria(Adf.toText(Json.map(g.issue.get("fields")).get("description")));
        for (int i = 0; i < criteria.size(); i++) criteriaIds.add("AC" + (i + 1));
        List<Object> problems = validateDoc(cfg, key, doc, criteriaIds);
        if (!problems.isEmpty()) {
            out.put("invalid", problems);
            out.put("created", List.of());
            System.out.println(Json.write(out));
            return EXIT_INVALID;
        }

        String projectKey = cfg.projectKey != null ? cfg.projectKey : Json.str(g.issue, "fields", "project", "key");
        String storyKey = Json.str(g.approval, "storyKey");

        // Duplicate prevention against what is already linked to this task.
        List<Dedupe.Known> known = new ArrayList<>();
        for (Map<String, Object> t : existingTests(jira, cfg, key)) {
            List<String> labels = new ArrayList<>();
            for (Object l : Json.list(Json.at(t, "fields", "labels"))) labels.add(String.valueOf(l));
            known.add(new Dedupe.Known(Json.str(t, "key"), Json.str(t, "fields", "summary"), labels));
        }

        List<Object> created = new ArrayList<>(), skipped = new ArrayList<>(), failed = new ArrayList<>();
        for (Object o : Json.list(doc.get("testCases"))) {
            Map<String, Object> tc = Json.map(o);
            String title = Json.str(tc, "title").trim();
            if (title.length() > 255) title = title.substring(0, 255);
            String fp = Dedupe.fingerprint(tc);
            String dupOf = Dedupe.match(known, title, fp, cfg.similarityThreshold);
            if (dupOf != null) {
                skipped.add(Map.of("title", title, "reason", "duplicate of " + dupOf));
                continue;
            }
            Map<String, Object> fields = testFields(cfg, projectKey, key, storyKey, title, fp, tc, criteria);
            if (dryRun) {
                created.add(Map.of("title", title, "type", Json.str(tc, "type"), "fingerprint", fp));
                known.add(new Dedupe.Known("(this run)", title, List.of("tc-fp-" + fp)));
                continue;
            }
            try {
                String testKey = Json.str(jira.post("/rest/api/3/issue", Map.of("fields", fields)), "key");
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("type", Map.of("name", cfg.issueLinkType));
                link.put("inwardIssue", Map.of("key", testKey));
                link.put("outwardIssue", Map.of("key", key));
                try {
                    jira.post("/rest/api/3/issueLink", link);
                } catch (Jira.JiraException e) {
                    // The test exists but is not linked: report it loudly, traceability is the point.
                    failed.add(Map.of("title", title, "key", testKey, "error", "created but link to " + key
                            + " failed: " + e.getMessage()));
                    continue;
                }
                created.add(Map.of("key", testKey, "title", title, "type", Json.str(tc, "type")));
                known.add(new Dedupe.Known(testKey, title, List.of("tc-fp-" + fp)));
            } catch (Jira.JiraException e) {
                failed.add(Map.of("title", title, "error", e.getMessage()));
            }
        }

        if (!dryRun && cfg.commentOnTask && !created.isEmpty()) {
            List<Object> lines = new ArrayList<>();
            for (Object c : created) lines.add(Json.str(c, "key") + " [" + Json.str(c, "type") + "] " + Json.str(c, "title"));
            List<Object> content = new ArrayList<>();
            content.add(Adf.para("Test Case Agent created " + created.size() + " test case"
                    + (created.size() == 1 ? "" : "s") + " for this approved task:"));
            content.add(Adf.bullets(lines));
            try {
                jira.post("/rest/api/3/issue/" + enc(key) + "/comment", Map.of("body", Adf.doc(content)));
            } catch (Jira.JiraException e) {
                out.put("commentError", e.getMessage());
            }
        }

        out.put("matrix", matrix(doc));
        out.put("created", created);
        out.put("skipped", skipped);
        out.put("failed", failed);
        System.out.println(Json.write(out));
        return failed.isEmpty() ? EXIT_OK : EXIT_ERROR;
    }

    // ------------------------------------------------------------------ validation

    /**
     * Validates the whole document. {@code knownCriteria} is the task's AC ids (AC1..ACn), or
     * null offline, in which case "covers" ids are only checked for shape.
     */
    static List<Object> validateDoc(Config cfg, String key, Map<String, Object> doc, List<String> knownCriteria) {
        List<Object> problems = new ArrayList<>();
        String fileTask = Json.str(doc, "taskKey");
        if (fileTask == null || !fileTask.equalsIgnoreCase(key)) {
            problems.add("testcases file is for " + fileTask + ", not " + key);
        }
        List<Object> cases = Json.list(doc.get("testCases"));
        if (cases.isEmpty()) {
            problems.add("testcases file has no testCases");
            return problems;
        }
        if (cases.size() > cfg.maxTestCasesPerRun) {
            problems.add(cases.size() + " test cases exceeds maxTestCasesPerRun=" + cfg.maxTestCasesPerRun);
        }

        Set<String> present = new HashSet<>();
        Set<String> covered = new HashSet<>();
        List<Dedupe.Known> seen = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> tc = Json.map(cases.get(i));
            String where = "testCases[" + i + "] '" + orDefault(Json.str(tc, "title"), "?") + "'";
            String title = Json.str(tc, "title");
            if (title == null || title.isBlank()) problems.add(where + ": missing title");
            String type = Json.str(tc, "type");
            if (type == null || !TYPES.contains(type)) problems.add(where + ": type must be one of " + TYPES);
            else present.add(type);
            String auto = Json.str(tc, "automation");
            if (auto != null && !AUTOMATION.contains(auto)) problems.add(where + ": automation must be one of " + AUTOMATION);

            List<Object> steps = Json.list(tc.get("steps"));
            if (steps.isEmpty()) problems.add(where + ": needs at least one step");
            for (Object s : steps) {
                if (blank(Json.str(s, "action")) || blank(Json.str(s, "expected"))) {
                    problems.add(where + ": every step needs 'action' and 'expected'");
                    break;
                }
            }

            // Traceability: every test case must say which acceptance criteria it verifies.
            List<Object> covers = Json.list(tc.get("covers"));
            boolean objectiveOnly = Boolean.TRUE.equals(tc.get("coversObjective"));
            if (covers.isEmpty() && !objectiveOnly) {
                problems.add(where + ": needs 'covers' (AC ids like AC1) or \"coversObjective\": true");
            }
            for (Object c : covers) {
                String id = String.valueOf(c).trim().toUpperCase(Locale.ROOT);
                if (!id.matches("AC\\d+")) problems.add(where + ": covers entry '" + c + "' is not an AC id (AC1, AC2, ...)");
                else if (knownCriteria != null && !knownCriteria.contains(id)) {
                    problems.add(where + ": covers " + id + " but the task has only " + knownCriteria);
                } else covered.add(id);
            }

            // Duplicates inside the file itself.
            if (title != null && !title.isBlank()) {
                String fp = Dedupe.fingerprint(tc);
                String dup = Dedupe.match(seen, title, fp, cfg.similarityThreshold);
                if (dup != null) problems.add(where + ": duplicate of another test case in this file (" + dup + ")");
                seen.add(new Dedupe.Known(title, title, List.of("tc-fp-" + fp)));
            }
        }

        // Scenario coverage: positive is mandatory; the rest present or justified.
        if (!present.contains("positive")) problems.add("coverage: at least one positive test case is required");
        Map<String, Object> na = Json.map(Json.at(doc, "coverage", "notApplicable"));
        for (String t : COVERAGE_TYPES) {
            if (present.contains(t)) {
                if (na.containsKey(t)) problems.add("coverage: '" + t + "' is both present and declared notApplicable");
            } else if (blank(Json.str(na, t))) {
                problems.add("coverage: no '" + t + "' test case and no coverage.notApplicable." + t
                        + " reason (justify it or add the test)");
            }
        }
        for (String t : na.keySet()) {
            if (!COVERAGE_TYPES.contains(t)) problems.add("coverage: notApplicable key '" + t + "' is not one of " + COVERAGE_TYPES);
        }

        // Every acceptance criterion is verified by at least one test case.
        if (knownCriteria != null) {
            for (String id : knownCriteria) {
                if (!covered.contains(id)) problems.add("traceability: " + id + " is not covered by any test case");
            }
        }
        return problems;
    }

    static Map<String, Object> matrix(Map<String, Object> doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, List<Object>> byAc = new LinkedHashMap<>();
        for (Object o : Json.list(doc.get("testCases"))) {
            String type = orDefault(Json.str(o, "type"), "?");
            byType.merge(type, 1, Integer::sum);
            for (Object c : Json.list(Json.at(o, "covers"))) {
                byAc.computeIfAbsent(String.valueOf(c).toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(Json.str(o, "title"));
            }
        }
        m.put("byType", byType);
        m.put("byAcceptanceCriterion", byAc);
        m.put("notApplicable", Json.map(Json.at(doc, "coverage", "notApplicable")));
        return m;
    }

    static Map<String, Object> testFields(Config cfg, String projectKey, String taskKey, String storyKey, String title,
                                          String fingerprint, Map<String, Object> tc, List<String> criteria) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("issuetype", Map.of("name", cfg.testCaseIssueType));
        fields.put("summary", title);
        fields.put("description", Adf.testCase(tc, taskKey, storyKey, criteria));

        List<Object> labels = new ArrayList<>(cfg.defaultLabels);
        addLabel(labels, "task-" + taskKey);
        if (storyKey != null) addLabel(labels, "story-" + storyKey);
        addLabel(labels, "type-" + Json.str(tc, "type"));
        addLabel(labels, "tc-fp-" + fingerprint);
        String auto = Json.str(tc, "automation");
        if (auto != null) addLabel(labels, "automation-" + auto);
        for (Object l : Json.list(tc.get("labels"))) addLabel(labels, String.valueOf(l));
        fields.put("labels", labels);

        String priority = Json.str(tc, "priority");
        if (priority != null && !priority.isBlank()) fields.put("priority", Map.of("name", priority));
        return fields;
    }

    static void addLabel(List<Object> labels, String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        if (!v.isEmpty() && !v.endsWith("-null") && !labels.contains(v)) labels.add(v);
    }

    static List<Map<String, Object>> existingTests(Jira jira, Config cfg, String taskKey) throws Exception {
        String jql = "issue in linkedIssues(\"" + taskKey + "\") AND issuetype = \"" + cfg.testCaseIssueType + "\"";
        return jira.search(jql, List.of("summary", "status", "labels"), 500);
    }

    /** Pulls the bullet/numbered lines under an "Acceptance criteria" heading out of flattened task text. */
    static List<String> extractCriteria(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        boolean in = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#")) {
                in = line.toLowerCase(Locale.ROOT).contains("acceptance criteria");
                continue;
            }
            if (!in || line.isEmpty()) continue;
            String item = line.replaceFirst("^(-|\\*|\\d+[.)])\\s+", "");
            if (!item.equals(line) || !out.isEmpty()) {
                if (item.equals(line) && !out.isEmpty()) out.set(out.size() - 1, out.get(out.size() - 1) + " " + item);
                else out.add(item);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ duplicate prevention

    static final class Dedupe {
        record Known(String key, String title, List<String> labels) {}

        /** Stable hash of what a test actually does: scenario type, step actions and expected results. */
        static String fingerprint(Map<String, Object> tc) {
            StringBuilder sb = new StringBuilder(norm(Json.str(tc, "type")));
            for (Object s : Json.list(tc.get("steps"))) {
                sb.append('|').append(norm(Json.str(s, "action"))).append('>').append(norm(Json.str(s, "expected")));
            }
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 5; i++) hex.append(String.format("%02x", d[i]));
                return hex.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** Returns the key/title of the duplicate, or null. Same normalised title, same fingerprint, or near-identical title. */
        static String match(List<Known> known, String title, String fp, double threshold) {
            String nt = norm(title);
            for (Known k : known) {
                if (norm(k.title()).equals(nt)) return k.key() + " (same title)";
                if (k.labels().contains("tc-fp-" + fp)) return k.key() + " (same steps and expected results)";
                if (jaccard(nt, norm(k.title())) >= threshold) return k.key() + " (near-identical title)";
            }
            return null;
        }

        static double jaccard(String a, String b) {
            Set<String> x = new HashSet<>(List.of(a.split(" "))), y = new HashSet<>(List.of(b.split(" ")));
            if (x.isEmpty() || y.isEmpty()) return 0;
            Set<String> inter = new HashSet<>(x);
            inter.retainAll(y);
            Set<String> union = new HashSet<>(x);
            union.addAll(y);
            return (double) inter.size() / union.size();
        }
    }

    // ------------------------------------------------------------------ approval gate

    static final class Gate {
        boolean approved;
        Map<String, Object> issue;
        final List<Map<String, Object>> checks = new ArrayList<>();
        final Map<String, Object> approval = new LinkedHashMap<>();

        void check(String name, boolean pass, String detail) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", name);
            c.put("pass", pass);
            c.put("detail", detail);
            checks.add(c);
        }

        List<Object> failedCheckNames() {
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> c : checks) if (!Boolean.TRUE.equals(c.get("pass"))) out.add(c.get("name"));
            return out;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("verdict", approved ? "APPROVED" : "REJECTED");
            m.put("approval", approval);
            m.put("checks", checks);
            return m;
        }

        static Gate evaluate(Jira jira, Config cfg, String key) throws Exception {
            Gate g = new Gate();
            List<String> fieldList = new ArrayList<>(List.of("summary", "description", "status", "issuetype",
                    "project", "priority", "labels", "reporter", "creator", "parent"));
            if (cfg.approverField != null) fieldList.add(cfg.approverField);
            g.issue = Json.map(jira.get("/rest/api/3/issue/" + enc(key) + "?fields=" + enc(String.join(",", fieldList))));
            Map<String, Object> f = Json.map(g.issue.get("fields"));

            // 1. It is a task (not an epic, story or bug).
            String type = Json.str(f, "issuetype", "name");
            boolean isTask = false;
            for (String s : cfg.taskIssueTypes) isTask |= s.equalsIgnoreCase(type);
            g.check("isTask", isTask, "issue type is '" + type + "'; accepted " + cfg.taskIssueTypes);

            // 2. It is currently in an approved status.
            String status = Json.str(f, "status", "name");
            g.check("currentStatusApproved", cfg.isApprovedStatus(status),
                    "current status '" + status + "'; approved statuses " + cfg.approvedStatuses);

            // 3. The changelog shows who moved it into that status, and when.
            List<Map<String, Object>> history = jira.changelog(key);
            history.sort((a, b) -> parseTime(Json.str(a, "created")).compareTo(parseTime(Json.str(b, "created"))));
            Map<String, Object> approvalEvent = null;
            int approvalIdx = -1;
            for (int i = 0; i < history.size(); i++) {
                for (Object it : Json.list(history.get(i).get("items"))) {
                    if ("status".equals(Json.str(it, "field")) && cfg.isApprovedStatus(Json.str(it, "toString"))) {
                        approvalEvent = history.get(i);
                        approvalIdx = i;
                    }
                }
            }
            String approverId = approvalEvent == null ? null : Json.str(approvalEvent, "author", "accountId");
            g.check("approvalTransitionFound", approvalEvent != null && approverId != null,
                    approvalEvent == null ? "no status change into an approved status in the changelog"
                            : approverId == null ? "approval transition has no author (anonymous or deleted user)"
                            : "approved at " + Json.str(approvalEvent, "created"));
            if (approvalEvent != null) {
                g.approval.put("approvedAt", Json.str(approvalEvent, "created"));
                g.approval.put("approverAccountId", approverId);
            }

            // 4. Whoever made that transition is an authorized approver.
            boolean approverAuthorized = approverId != null && cfg.isAuthorized(jira, approverId);
            g.check("approverAuthorized", approverAuthorized, cfg.hasAnyApprover()
                    ? (approverId == null ? "no approver to check"
                    : approverAuthorized ? "approver is in authorizedApprovers"
                    : "approver is not in authorizedApprovers.accountIds or any of groups " + cfg.approverGroups)
                    : "authorizedApprovers is empty in config - nobody is authorized (fail closed)");

            // 5. Segregation of duties: the author of the task cannot approve it.
            if (cfg.forbidSelfApproval) {
                String reporter = Json.str(f, "reporter", "accountId"), creator = Json.str(f, "creator", "accountId");
                boolean self = approverId != null && (approverId.equals(reporter) || approverId.equals(creator));
                g.check("notSelfApproved", approverId != null && !self,
                        approverId == null ? "no approver to check"
                                : self ? "approver is the task's reporter/creator" : "approver differs from reporter/creator");
            }

            // 6. Optional approval-record field must name an authorized person (and, by default, the same one).
            if (cfg.approverField != null) {
                List<String> recorded = accountIds(f.get(cfg.approverField));
                boolean anyAuthorized = false;
                for (String id : recorded) anyAuthorized |= cfg.isAuthorized(jira, id);
                boolean matches = !cfg.approverFieldMustMatchTransition || (approverId != null && recorded.contains(approverId));
                g.check("approverFieldValid", !recorded.isEmpty() && anyAuthorized && matches,
                        recorded.isEmpty() ? "approval field " + cfg.approverField + " is empty"
                                : !anyAuthorized ? "no authorized approver in " + cfg.approverField
                                : !matches ? "person in " + cfg.approverField + " is not the one who made the approval transition"
                                : cfg.approverField + " names an authorized approver");
                g.approval.put("approverFieldAccountIds", recorded);
            }

            // 7. What was approved is what we read: no edits to protected fields after approval.
            if (cfg.rejectIfEditedAfterApproval && approvalIdx >= 0) {
                List<String> edits = new ArrayList<>();
                for (int i = approvalIdx + 1; i < history.size(); i++) {
                    for (Object it : Json.list(history.get(i).get("items"))) {
                        String field = Json.str(it, "field");
                        if (cfg.protectedFields.contains(field)) edits.add(field + " @ " + Json.str(history.get(i), "created"));
                    }
                }
                g.check("unchangedSinceApproval", edits.isEmpty(),
                        edits.isEmpty() ? "no edits to " + cfg.protectedFields + " after approval"
                                : "edited after approval (re-approval needed): " + edits);
            }

            // 8. The chain Story -> Tasks -> Test Cases: the parent story must still be approved.
            String storyKey = Json.str(f, "parent", "key");
            g.approval.put("storyKey", storyKey);
            if (cfg.requireApprovedStory) {
                if (storyKey == null) {
                    g.check("parentStoryApproved", false, "task has no parent story");
                } else {
                    Map<String, Object> story = Json.map(jira.get("/rest/api/3/issue/" + enc(storyKey) + "?fields=status"));
                    String storyStatus = Json.str(story, "fields", "status", "name");
                    g.check("parentStoryApproved", Config.matchesAny(cfg.storyApprovedStatuses, storyStatus),
                            "story " + storyKey + " status '" + storyStatus + "'; accepted " + cfg.storyApprovedStatuses);
                }
            }

            boolean all = true;
            for (Map<String, Object> c : g.checks) all &= Boolean.TRUE.equals(c.get("pass"));
            g.approved = all;
            return g;
        }
    }

    static List<String> accountIds(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof Map) {
            String id = Json.str(v, "accountId");
            if (id != null) out.add(id);
        } else if (v instanceof List) {
            for (Object o : (List<?>) v) out.addAll(accountIds(o));
        }
        return out;
    }

    // ------------------------------------------------------------------ config

    static final class Config {
        String projectKey, testCaseIssueType, issueLinkType, approverField;
        List<String> taskIssueTypes, approvedStatuses, storyApprovedStatuses, approverAccountIds, approverGroups,
                protectedFields;
        List<Object> defaultLabels;
        boolean forbidSelfApproval, rejectIfEditedAfterApproval, approverFieldMustMatchTransition, requireApprovedStory,
                commentOnTask;
        double similarityThreshold;
        int maxTestCasesPerRun, scanLimit;
        final Map<String, Set<String>> groupCache = new HashMap<>();

        static Config load(Path p) throws Exception {
            if (!Files.exists(p)) {
                throw new IllegalStateException("config not found at " + p
                        + " - copy test-case-agent.config.example.json to test-case-agent.config.json and fill it in");
            }
            Map<String, Object> m = Json.map(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
            Config c = new Config();
            c.projectKey = blankToNull(Json.str(m, "projectKey"));
            c.taskIssueTypes = strings(m.get("taskIssueTypes"));
            if (c.taskIssueTypes.isEmpty()) c.taskIssueTypes = List.of("Task", "Sub-task");
            c.testCaseIssueType = orDefault(Json.str(m, "testCaseIssueType"), "Test");
            c.issueLinkType = orDefault(Json.str(m, "issueLinkType"), "Relates");
            c.approverField = blankToNull(Json.str(m, "approverField"));
            c.approvedStatuses = strings(m.get("approvedStatuses"));
            if (c.approvedStatuses.isEmpty()) c.approvedStatuses = List.of("Approved");
            c.storyApprovedStatuses = strings(m.get("storyApprovedStatuses"));
            if (c.storyApprovedStatuses.isEmpty()) c.storyApprovedStatuses = List.of("Approved", "In Progress");
            c.approverAccountIds = strings(Json.at(m, "authorizedApprovers", "accountIds"));
            c.approverGroups = strings(Json.at(m, "authorizedApprovers", "groups"));
            c.protectedFields = strings(m.get("protectedFields"));
            if (c.protectedFields.isEmpty()) c.protectedFields = List.of("summary", "description");
            c.defaultLabels = new ArrayList<>(strings(m.get("defaultLabels")));
            c.forbidSelfApproval = bool(m.get("forbidSelfApproval"), true);
            c.rejectIfEditedAfterApproval = bool(m.get("rejectIfEditedAfterApproval"), true);
            c.approverFieldMustMatchTransition = bool(m.get("approverFieldMustMatchTransition"), true);
            c.requireApprovedStory = bool(m.get("requireApprovedStory"), true);
            c.commentOnTask = bool(m.get("commentOnTask"), true);
            c.similarityThreshold = m.get("similarityThreshold") instanceof Number n ? n.doubleValue() : 0.85;
            c.maxTestCasesPerRun = m.get("maxTestCasesPerRun") instanceof Number n ? n.intValue() : 40;
            c.scanLimit = m.get("scanLimit") instanceof Number n ? n.intValue() : 50;
            return c;
        }

        boolean isApprovedStatus(String s) {
            return matchesAny(approvedStatuses, s);
        }

        static boolean matchesAny(List<String> list, String s) {
            if (s == null) return false;
            for (String a : list) if (a.equalsIgnoreCase(s.trim())) return true;
            return false;
        }

        boolean hasAnyApprover() {
            return !approverAccountIds.isEmpty() || !approverGroups.isEmpty();
        }

        boolean isAuthorized(Jira jira, String accountId) throws Exception {
            if (approverAccountIds.contains(accountId)) return true;
            if (approverGroups.isEmpty()) return false;
            Set<String> groups = groupCache.get(accountId);
            if (groups == null) {
                groups = new HashSet<>();
                for (Object g : Json.list(jira.get("/rest/api/3/user/groups?accountId=" + enc(accountId)))) {
                    String name = Json.str(g, "name");
                    if (name != null) groups.add(name.toLowerCase(Locale.ROOT));
                }
                groupCache.put(accountId, groups);
            }
            for (String g : approverGroups) if (groups.contains(g.toLowerCase(Locale.ROOT))) return true;
            return false;
        }
    }

    // ------------------------------------------------------------------ Jira REST client

    static final class Jira {
        final String base, auth;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

        Jira(String base, String auth) {
            this.base = base;
            this.auth = auth;
        }

        static Jira fromEnv() {
            String url = System.getenv("JIRA_URL"), email = System.getenv("JIRA_EMAIL"), token = System.getenv("JIRA_TOKEN");

            // Not in the environment: fall back to a .env file (own scripts folder, then the sibling story-agent's).
            if (blankStr(url) || blankStr(email) || blankStr(token)) {
                Map<String, String> env = new HashMap<>();
                for (String p : List.of(".env", ".claude/skills/test-case-agent/scripts/.env",
                        ".claude/skills/user-story-agent/scripts/.env")) {
                    try {
                        for (Map.Entry<String, String> e : loadEnvFile(Path.of(p)).entrySet()) env.putIfAbsent(e.getKey(), e.getValue());
                    } catch (Exception e) {
                        // ignore unreadable file, try the next
                    }
                }
                if (blankStr(url)) url = env.get("JIRA_URL");
                if (blankStr(email)) email = env.get("JIRA_EMAIL");
                if (blankStr(token)) token = env.get("JIRA_TOKEN");
            }

            List<String> missing = new ArrayList<>();
            if (blankStr(url)) missing.add("JIRA_URL");
            if (blankStr(email)) missing.add("JIRA_EMAIL");
            if (blankStr(token)) missing.add("JIRA_TOKEN");
            if (!missing.isEmpty()) throw new IllegalStateException("missing environment variables or .env file: " + missing);
            String raw = email.trim() + ":" + token.trim();
            return new Jira(url.trim().replaceAll("/+$", ""),
                    "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }

        static Map<String, String> loadEnvFile(Path path) throws Exception {
            Map<String, String> env = new HashMap<>();
            if (!Files.exists(path)) return env;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                env.put(line.substring(0, eq).trim(), value);
            }
            return env;
        }

        Object get(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), path);
        }

        Object post(String path, Object body) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)), path);
        }

        Object send(HttpRequest.Builder b, String path) throws Exception {
            HttpRequest req = b.header("Authorization", auth).header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int sc = res.statusCode();
            String shortPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            if (sc == 401) throw new JiraException("Jira authentication failed (HTTP 401) - check JIRA_EMAIL / JIRA_TOKEN");
            if (sc == 403) throw new JiraException("Jira permission denied (HTTP 403) on " + shortPath);
            if (sc == 404) throw new JiraException("not found, or no permission to see it (HTTP 404): " + shortPath);
            if (sc / 100 != 2) throw new JiraException("HTTP " + sc + " on " + shortPath + ": " + truncate(res.body()));
            return res.body() == null || res.body().isBlank() ? Map.of() : Json.parse(res.body());
        }

        List<Map<String, Object>> changelog(String key) throws Exception {
            List<Map<String, Object>> out = new ArrayList<>();
            int startAt = 0;
            while (true) {
                Map<String, Object> page = Json.map(get("/rest/api/3/issue/" + enc(key)
                        + "/changelog?startAt=" + startAt + "&maxResults=100"));
                List<Object> values = Json.list(page.get("values"));
                for (Object v : values) out.add(Json.map(v));
                if (values.isEmpty() || Boolean.TRUE.equals(page.get("isLast"))) break;
                startAt += values.size();
            }
            return out;
        }

        List<Map<String, Object>> search(String jql, List<String> fields, int limit) throws Exception {
            List<Map<String, Object>> out = new ArrayList<>();
            String token = null;
            while (out.size() < limit) {
                String path = "/rest/api/3/search/jql?jql=" + enc(jql) + "&fields=" + enc(String.join(",", fields))
                        + "&maxResults=" + Math.min(100, limit - out.size())
                        + (token != null ? "&nextPageToken=" + enc(token) : "");
                Map<String, Object> page = Json.map(get(path));
                for (Object i : Json.list(page.get("issues"))) out.add(Json.map(i));
                token = Json.str(page, "nextPageToken");
                if (token == null || Boolean.TRUE.equals(page.get("isLast"))) break;
            }
            return out;
        }

        static final class JiraException extends Exception {
            JiraException(String m) {
                super(m);
            }
        }
    }

    // ------------------------------------------------------------------ Atlassian Document Format

    static final class Adf {
        static Map<String, Object> doc(List<Object> content) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", "doc");
            d.put("version", 1);
            d.put("content", content);
            return d;
        }

        static Map<String, Object> text(String t) {
            return new LinkedHashMap<>(Map.of("type", "text", "text", t));
        }

        static Map<String, Object> code(String t) {
            Map<String, Object> m = text(t);
            m.put("marks", List.of(Map.of("type", "code")));
            return m;
        }

        static Map<String, Object> para(String t) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "paragraph");
            p.put("content", t == null || t.isEmpty() ? List.of() : List.of(text(t)));
            return p;
        }

        static Map<String, Object> paraOf(List<Object> inline) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "paragraph");
            p.put("content", inline);
            return p;
        }

        static Map<String, Object> heading(String t) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("type", "heading");
            h.put("attrs", Map.of("level", 3));
            h.put("content", List.of(text(t)));
            return h;
        }

        static Map<String, Object> bullets(List<Object> items) {
            List<Object> li = new ArrayList<>();
            for (Object i : items) {
                Object p = i instanceof Map ? i : para(String.valueOf(i));
                li.add(Map.of("type", "listItem", "content", List.of(p)));
            }
            return Map.of("type", "bulletList", "content", li);
        }

        static Map<String, Object> numbered(List<Object> items) {
            List<Object> li = new ArrayList<>();
            for (Object i : items) li.add(Map.of("type", "listItem", "content", List.of(i)));
            return Map.of("type", "orderedList", "content", li);
        }

        /** Description of one test case; the first lines carry the traceability back to task and story. */
        static Map<String, Object> testCase(Map<String, Object> tc, String taskKey, String storyKey, List<String> criteria) {
            List<Object> c = new ArrayList<>();
            List<Object> trace = new ArrayList<>();
            trace.add(paraOf(List.of(text("Verifies task: "), code(taskKey))));
            if (storyKey != null) trace.add(paraOf(List.of(text("Story: "), code(storyKey))));
            trace.add(para("Scenario type: " + Json.str(tc, "type")
                    + (Json.str(tc, "automation") != null ? " | Automation: " + Json.str(tc, "automation") : "")));
            List<Object> covers = Json.list(tc.get("covers"));
            if (!covers.isEmpty()) {
                for (Object o : covers) {
                    String id = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
                    int idx = id.matches("AC\\d+") ? Integer.parseInt(id.substring(2)) - 1 : -1;
                    String ac = idx >= 0 && idx < criteria.size() ? criteria.get(idx) : "";
                    trace.add(paraOf(List.of(text("Covers: "), code(id), text(ac.isEmpty() ? "" : " - " + ac))));
                }
            } else {
                trace.add(para("Covers: task objective"));
            }
            c.add(heading("Traceability"));
            c.add(bullets(trace));

            String objective = Json.str(tc, "objective");
            if (!blank(objective)) c.add(para(objective));

            List<Object> pre = Json.list(tc.get("preconditions"));
            if (!pre.isEmpty()) {
                c.add(heading("Preconditions"));
                c.add(bullets(pre));
            }
            List<Object> steps = new ArrayList<>();
            for (Object s : Json.list(tc.get("steps"))) {
                steps.add(paraOf(List.of(text(Json.str(s, "action")), text("  ->  Expected: " + Json.str(s, "expected")))));
            }
            c.add(heading("Steps"));
            c.add(numbered(steps));

            String expected = Json.str(tc, "expected");
            if (!blank(expected)) {
                c.add(heading("Expected result"));
                c.add(para(expected));
            }
            List<Object> data = Json.list(tc.get("testData"));
            if (!data.isEmpty()) {
                c.add(heading("Test data"));
                c.add(bullets(data));
            }
            c.add(para("Generated by Test Case Agent from approved task " + taskKey + "."));
            return doc(c);
        }

        /** Flattens ADF to readable plain text (paragraphs, headings, lists, tables). */
        static String toText(Object node) {
            StringBuilder sb = new StringBuilder();
            walk(node, sb, "");
            return sb.toString().replaceAll("\\n{3,}", "\n\n").trim();
        }

        private static void walk(Object node, StringBuilder sb, String indent) {
            if (node instanceof String s) {
                sb.append(s);
                return;
            }
            if (!(node instanceof Map)) return;
            String type = Json.str(node, "type");
            if (type == null) type = "";
            List<Object> kids = Json.list(Json.at(node, "content"));
            switch (type) {
                case "text" -> sb.append(Json.str(node, "text"));
                case "hardBreak" -> sb.append('\n').append(indent);
                case "mention" -> sb.append(orDefault(Json.str(node, "attrs", "text"), "@user"));
                case "emoji" -> sb.append(orDefault(Json.str(node, "attrs", "text"), ""));
                case "inlineCard" -> sb.append(orDefault(Json.str(node, "attrs", "url"), ""));
                case "heading" -> {
                    int level = Json.at(node, "attrs", "level") instanceof Number n ? n.intValue() : 2;
                    sb.append("\n").append("#".repeat(Math.max(1, level))).append(' ');
                    for (Object k : kids) walk(k, sb, indent);
                    sb.append("\n");
                }
                case "paragraph" -> {
                    for (Object k : kids) walk(k, sb, indent);
                    sb.append("\n");
                }
                case "bulletList", "orderedList" -> {
                    int n = 1;
                    for (Object k : kids) {
                        sb.append(indent).append(type.equals("bulletList") ? "- " : (n++) + ". ");
                        StringBuilder item = new StringBuilder();
                        for (Object kk : Json.list(Json.at(k, "content"))) walk(kk, item, indent + "  ");
                        sb.append(item.toString().trim().replace("\n", "\n" + indent + "  ")).append("\n");
                    }
                }
                case "codeBlock" -> {
                    sb.append("```\n");
                    for (Object k : kids) walk(k, sb, indent);
                    sb.append("\n```\n");
                }
                case "tableRow" -> {
                    List<String> cells = new ArrayList<>();
                    for (Object k : kids) {
                        StringBuilder cell = new StringBuilder();
                        walk(k, cell, "");
                        cells.add(cell.toString().trim().replace("\n", " "));
                    }
                    sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
                }
                default -> {
                    for (Object k : kids) walk(k, sb, indent);
                    if (type.equals("blockquote") || type.equals("panel") || type.equals("table")) sb.append("\n");
                }
            }
        }
    }

    // ------------------------------------------------------------------ minimal JSON

    static final class Json {
        static Object parse(String s) {
            Parser p = new Parser(s);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != s.length()) throw new IllegalArgumentException("trailing characters in JSON at " + p.i);
            return v;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> map(Object o) {
            return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
        }

        @SuppressWarnings("unchecked")
        static List<Object> list(Object o) {
            return o instanceof List ? (List<Object>) o : List.of();
        }

        static Object at(Object root, String... path) {
            Object cur = root;
            for (String k : path) {
                if (!(cur instanceof Map)) return null;
                cur = ((Map<?, ?>) cur).get(k);
            }
            return cur;
        }

        static String str(Object root, String... path) {
            Object v = at(root, path);
            return v == null ? null : v instanceof String s ? s : String.valueOf(v);
        }

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb, 0);
            return sb.toString();
        }

        private static void write(Object o, StringBuilder sb, int depth) {
            String pad = "  ".repeat(depth + 1), end = "  ".repeat(depth);
            if (o == null) sb.append("null");
            else if (o instanceof String s) quote(s, sb);
            else if (o instanceof Number || o instanceof Boolean) {
                if (o instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) sb.append(d.longValue());
                else sb.append(o);
            } else if (o instanceof Map<?, ?> m) {
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    sb.append(pad);
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(": ");
                    write(e.getValue(), sb, depth + 1);
                    sb.append(++i < m.size() ? ",\n" : "\n");
                }
                sb.append(end).append('}');
            } else if (o instanceof List<?> l) {
                if (l.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append("[\n");
                for (int i = 0; i < l.size(); i++) {
                    sb.append(pad);
                    write(l.get(i), sb, depth + 1);
                    sb.append(i + 1 < l.size() ? ",\n" : "\n");
                }
                sb.append(end).append(']');
            } else quote(String.valueOf(o), sb);
        }

        private static void quote(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }

        private static final class Parser {
            final String s;
            int i;

            Parser(String s) {
                // Tolerate a UTF-8 BOM (PowerShell writes one).
                this.s = s.startsWith("﻿") ? s.substring(1) : s;
            }

            void ws() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            }

            char peek() {
                if (i >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
                return s.charAt(i);
            }

            Object value() {
                ws();
                char c = peek();
                switch (c) {
                    case '{':
                        return object();
                    case '[':
                        return array();
                    case '"':
                        return string();
                    case 't':
                        literal("true");
                        return Boolean.TRUE;
                    case 'f':
                        literal("false");
                        return Boolean.FALSE;
                    case 'n':
                        literal("null");
                        return null;
                    default:
                        return number();
                }
            }

            void literal(String w) {
                if (!s.startsWith(w, i)) throw new IllegalArgumentException("bad JSON literal at " + i);
                i += w.length();
            }

            Map<String, Object> object() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                ws();
                if (peek() == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    if (peek() != ':') throw new IllegalArgumentException("expected ':' at " + i);
                    i++;
                    m.put(k, value());
                    ws();
                    char c = peek();
                    i++;
                    if (c == '}') return m;
                    if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at " + (i - 1));
                }
            }

            List<Object> array() {
                List<Object> l = new ArrayList<>();
                i++;
                ws();
                if (peek() == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    char c = peek();
                    i++;
                    if (c == ']') return l;
                    if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at " + (i - 1));
                }
            }

            String string() {
                if (peek() != '"') throw new IllegalArgumentException("expected string at " + i);
                i++;
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = peek();
                    i++;
                    if (c == '"') return sb.toString();
                    if (c != '\\') {
                        sb.append(c);
                        continue;
                    }
                    char e = peek();
                    i++;
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                }
            }

            Number number() {
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                String n = s.substring(start, i);
                if (n.isEmpty()) throw new IllegalArgumentException("unexpected character '" + s.charAt(start) + "' at " + start);
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                return Long.parseLong(n);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Jira timestamps look like 2026-10-01T09:15:42.123+0000 (no colon in the offset). */
    static final DateTimeFormatter JIRA_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd().toFormatter();

    static OffsetDateTime parseTime(String s) {
        if (s == null) return OffsetDateTime.MIN;
        try {
            return OffsetDateTime.parse(s, JIRA_TIME);
        } catch (Exception e) {
            return OffsetDateTime.MIN;
        }
    }

    static String quoted(List<String> values) {
        List<String> q = new ArrayList<>();
        for (String v : values) q.add('"' + v.replace("\"", "\\\"") + '"');
        return String.join(", ", q);
    }

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.list(v)) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim());
        return out;
    }

    static boolean bool(Object v, boolean dflt) {
        return v instanceof Boolean b ? b : dflt;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static boolean blankStr(String s) {
        return blank(s);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String orDefault(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    static final class UsageException extends Exception {
        UsageException(String m) {
            super(m);
        }
    }
}
