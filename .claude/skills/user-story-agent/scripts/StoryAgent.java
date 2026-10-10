import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * User Story agent helper for the {@code user-story-agent} Claude skill.
 *
 * <p>Single-file, dependency-free (JDK 21 source launcher):
 * {@code java StoryAgent.java <command> ...}. Talks to Jira Cloud REST v3 with
 * API-token Basic Auth from {@code JIRA_URL}, {@code JIRA_EMAIL}, {@code JIRA_TOKEN}.
 * Credentials are never printed.</p>
 *
 * <p>Same fail-closed approval gate as the epic-reader helper, applied to a user
 * story, plus an optional check that the story's parent epic is still approved.
 * {@code create} re-runs the gate right before writing and validates every task
 * (target module, tests/criteria) before creating any, so a run never leaves a
 * half-created task set behind because of bad input.</p>
 *
 * <p>Output never carries e-mail addresses or display names — people are
 * identified by Jira accountId only.</p>
 */
public class StoryAgent {

    static final String USAGE = String.join("\n",
            "usage: java StoryAgent.java <command> [options]",
            "  scan   [--epic KEY] [--project KEY] [--jql \"...\"]  approved stories, each with its gate verdict",
            "  read   <STORY-KEY>                                gate verdict + story content + existing tasks (JSON)",
            "  create <STORY-KEY> <tasks.json> [--dry-run]       validate, re-check the gate, create the tasks",
            "options: --config <path>  (default .claude/skills/user-story-agent/user-story-agent.config.json)",
            "         --repo <path>    repo root used to validate task target modules (default .)",
            "env:     JIRA_URL, JIRA_EMAIL, JIRA_TOKEN",
            "exit:    0 ok | 2 gate rejected | 3 task validation failed | 1 error | 64 usage");

    static final int EXIT_OK = 0, EXIT_ERROR = 1, EXIT_REJECTED = 2, EXIT_INVALID = 3, EXIT_USAGE = 64;

    /** Target kinds a task may declare; "new-*" kinds must not exist yet, the rest must. */
    static final Set<String> EXISTING_KINDS = Set.of("service", "agent", "mcp", "library", "ui", "mobile",
            "ontology", "infra", "config");
    static final Set<String> NEW_KINDS = Set.of("new-service", "new-agent", "new-mcp", "new-library", "new-ui");

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
                ".claude/skills/user-story-agent/user-story-agent.config.json")));

        switch (pos.get(0)) {
            case "scan":
                return scan(Jira.fromEnv(), cfg, opt.get("epic"), opt.get("project"), opt.get("jql"));
            case "read":
                if (pos.size() != 2) throw new UsageException("read takes exactly one story key");
                return read(Jira.fromEnv(), cfg, pos.get(1));
            case "create":
                if (pos.size() != 3) throw new UsageException("create takes <STORY-KEY> <tasks.json>");
                return create(cfg, pos.get(1), Path.of(pos.get(2)), Path.of(opt.getOrDefault("repo", ".")),
                        opt.containsKey("dry-run"));
            default:
                throw new UsageException("unknown command " + pos.get(0));
        }
    }

    // ------------------------------------------------------------------ commands

    static int scan(Jira jira, Config cfg, String epic, String project, String jqlOverride) throws Exception {
        String jql = jqlOverride;
        if (jql == null) {
            StringBuilder sb = new StringBuilder("issuetype in (").append(quoted(cfg.storyIssueTypes))
                    .append(") AND status in (").append(quoted(cfg.approvedStatuses)).append(")");
            if (epic != null) sb.append(" AND parent = ").append(epic);
            String p = project != null ? project : cfg.projectKey;
            if (p != null && epic == null) sb.append(" AND project = \"").append(p).append('"');
            sb.append(" ORDER BY rank ASC");
            jql = sb.toString();
        }
        List<Map<String, Object>> stories = jira.search(jql, List.of("summary", "status"), cfg.scanLimit);
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> s : stories) {
            String key = Json.str(s, "key");
            Gate g = Gate.evaluate(jira, cfg, key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("summary", Json.str(s, "fields", "summary"));
            row.put("status", Json.str(s, "fields", "status", "name"));
            row.put("epic", g.approval.get("epicKey"));
            row.put("gate", g.approved ? "APPROVED" : "REJECTED");
            row.put("failedChecks", g.failedCheckNames());
            row.put("existingTasks", existingTasks(jira, cfg, key).size());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jql", jql);
        out.put("stories", rows);
        System.out.println(Json.write(out));
        return EXIT_OK;
    }

    static int read(Jira jira, Config cfg, String key) throws Exception {
        Gate g = Gate.evaluate(jira, cfg, key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storyKey", key);
        out.put("gate", g.toJson());
        if (g.approved) {
            // Content is only released for an approved story, so nothing downstream can plan from an unapproved one.
            Map<String, Object> f = Json.map(g.issue.get("fields"));
            Map<String, Object> story = new LinkedHashMap<>();
            story.put("summary", Json.str(f, "summary"));
            story.put("description", Adf.toText(f.get("description")));
            story.put("project", Json.str(f, "project", "key"));
            story.put("epic", g.approval.get("epicKey"));
            story.put("priority", Json.str(f, "priority", "name"));
            story.put("labels", f.getOrDefault("labels", List.of()));
            story.put("components", names(f.get("components")));
            story.put("fixVersions", names(f.get("fixVersions")));
            if (cfg.storyPointsField != null) story.put("storyPoints", f.get(cfg.storyPointsField));
            out.put("story", story);

            List<Object> tasks = new ArrayList<>();
            for (Map<String, Object> t : existingTasks(jira, cfg, key)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", Json.str(t, "key"));
                row.put("type", Json.str(t, "fields", "issuetype", "name"));
                row.put("status", Json.str(t, "fields", "status", "name"));
                row.put("summary", Json.str(t, "fields", "summary"));
                tasks.add(row);
            }
            out.put("existingTasks", tasks);
        }
        System.out.println(Json.write(out));
        return g.approved ? EXIT_OK : EXIT_REJECTED;
    }

    static int create(Config cfg, String key, Path tasksFile, Path repo, boolean dryRun) throws Exception {
        Map<String, Object> doc = Json.map(Json.parse(Files.readString(tasksFile, StandardCharsets.UTF_8)));
        String fileStory = Json.str(doc, "storyKey");
        if (fileStory != null && !fileStory.equalsIgnoreCase(key)) {
            throw new IllegalArgumentException("tasks file is for " + fileStory + ", not " + key);
        }
        List<Object> tasks = Json.list(doc.get("tasks"));
        if (tasks.isEmpty()) throw new IllegalArgumentException("tasks file has no tasks");
        if (tasks.size() > cfg.maxTasksPerRun) {
            throw new IllegalArgumentException(tasks.size() + " tasks exceeds maxTasksPerRun=" + cfg.maxTasksPerRun);
        }

        // Validate everything before touching Jira: all tasks are created, or none.
        List<Object> invalid = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            List<String> problems = validateTask(Json.map(tasks.get(i)), repo, cfg);
            if (!problems.isEmpty()) {
                invalid.add(Map.of("index", i, "summary", orDefault(Json.str(tasks.get(i), "summary"), ""),
                        "problems", problems));
            }
        }
        if (!invalid.isEmpty()) {
            System.out.println(Json.write(Map.of("storyKey", key, "invalidTasks", invalid, "created", List.of())));
            return EXIT_INVALID;
        }

        Jira jira = Jira.fromEnv();
        // Re-check immediately before writing: approval may have been revoked since `read`.
        Gate g = Gate.evaluate(jira, cfg, key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storyKey", key);
        out.put("dryRun", dryRun);
        out.put("gate", g.toJson());
        if (!g.approved) {
            out.put("created", List.of());
            System.out.println(Json.write(out));
            return EXIT_REJECTED;
        }

        String projectKey = cfg.projectKey != null ? cfg.projectKey : Json.str(g.issue, "fields", "project", "key");
        Set<String> existing = new HashSet<>();
        for (Map<String, Object> t : existingTasks(jira, cfg, key)) existing.add(norm(Json.str(t, "fields", "summary")));

        List<Object> created = new ArrayList<>(), skipped = new ArrayList<>(), failed = new ArrayList<>();
        for (Object o : tasks) {
            Map<String, Object> t = Json.map(o);
            String summary = Json.str(t, "summary").trim();
            if (summary.length() > 255) summary = summary.substring(0, 255);
            if (!existing.add(norm(summary))) {
                skipped.add(Map.of("summary", summary, "reason", "a task with this summary already exists for the story"));
                continue;
            }
            Map<String, Object> fields = taskFields(cfg, projectKey, key, summary, t);
            if (dryRun) {
                created.add(Map.of("summary", summary, "target", targetLabel(t), "payload", Map.of("fields", fields)));
                continue;
            }
            try {
                String taskKey = Json.str(jira.post("/rest/api/3/issue", Map.of("fields", fields)), "key");
                created.add(Map.of("key", taskKey, "summary", summary, "target", targetLabel(t)));
                if (!"parent".equals(cfg.taskLinkMode)) {
                    try {
                        Map<String, Object> link = new LinkedHashMap<>();
                        link.put("type", Map.of("name", cfg.issueLinkType));
                        link.put("inwardIssue", Map.of("key", taskKey));
                        link.put("outwardIssue", Map.of("key", key));
                        jira.post("/rest/api/3/issueLink", link);
                    } catch (Exception linkEx) {
                    }
                }
            } catch (Jira.JiraException e) {
                failed.add(Map.of("summary", summary, "error", e.getMessage()));
            }
        }

        if (!dryRun && cfg.commentOnStory && !created.isEmpty()) {
            List<Object> lines = new ArrayList<>();
            for (Object c : created) lines.add(Json.str(c, "key") + " - " + Json.str(c, "target"));
            List<Object> content = new ArrayList<>();
            content.add(Adf.para("Story Agent created " + created.size() + " task" + (created.size() == 1 ? "" : "s")
                    + " from this approved story:"));
            content.add(Adf.bullets(lines));
            String impact = Json.str(doc, "impact", "summary");
            if (impact != null && !impact.isBlank()) content.add(Adf.para("Impact: " + impact));
            try {
                jira.post("/rest/api/3/issue/" + enc(key) + "/comment", Map.of("body", Adf.doc(content)));
            } catch (Jira.JiraException e) {
                out.put("commentError", e.getMessage());
            }
        }

        out.put("created", created);
        out.put("skipped", skipped);
        out.put("failed", failed);
        System.out.println(Json.write(out));
        return failed.isEmpty() ? EXIT_OK : EXIT_ERROR;
    }

    static List<String> validateTask(Map<String, Object> t, Path repo, Config cfg) {
        List<String> p = new ArrayList<>();
        String summary = Json.str(t, "summary");
        if (summary == null || summary.isBlank()) p.add("missing summary");
        // Note: target fields (kind, module, layer) are no longer required. Tasks are created from story content only.
        List<Object> tests = Json.list(t.get("unitTests")), ac = Json.list(t.get("acceptanceCriteria"));
        if (tests.isEmpty() && ac.isEmpty()) p.add("needs unitTests or acceptanceCriteria");
        for (Object u : tests) {
            if (Json.str(u, "name") == null || Json.str(u, "then") == null) {
                p.add("each unit test needs at least 'name' and 'then'");
                break;
            }
        }
        return p;
    }

    static String targetLabel(Map<String, Object> t) {
        String kind = Json.str(t, "target", "kind"), module = Json.str(t, "target", "module");
        String domain = Json.str(t, "target", "domain");
        return kind + " " + module + (domain != null ? " (domain: " + domain + ")" : "");
    }

    static Map<String, Object> taskFields(Config cfg, String projectKey, String storyKey, String summary,
                                          Map<String, Object> t) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("issuetype", Map.of("name", cfg.taskIssueType));
        fields.put("summary", summary);
        fields.put("description", Adf.task(t, storyKey));
        if ("parent".equals(cfg.taskLinkMode)) fields.put("parent", Map.of("key", storyKey));

        List<Object> labels = new ArrayList<>(cfg.defaultLabels);
        String module = Json.str(t, "target", "module");
        addLabel(labels, "kind-" + Json.str(t, "target", "kind"));
        addLabel(labels, "layer-" + Json.str(t, "target", "layer"));
        if (module != null) addLabel(labels, "module-" + module.substring(module.lastIndexOf('/') + 1));
        String domain = Json.str(t, "target", "domain");
        if (domain != null) addLabel(labels, "domain-" + domain);
        for (Object l : Json.list(t.get("labels"))) addLabel(labels, String.valueOf(l));
        fields.put("labels", labels);

        String priority = Json.str(t, "priority");
        if (priority != null && !priority.isBlank()) fields.put("priority", Map.of("name", priority));
        Object points = t.get("storyPoints");
        if (cfg.storyPointsField != null && points instanceof Number) fields.put(cfg.storyPointsField, points);
        return fields;
    }

    static void addLabel(List<Object> labels, String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        if (!v.isEmpty() && !v.endsWith("-null") && !labels.contains(v)) labels.add(v);
    }

    static List<Map<String, Object>> existingTasks(Jira jira, Config cfg, String storyKey) throws Exception {
        String jql = "parent".equals(cfg.taskLinkMode) ? "parent = " + storyKey
                : "issue in linkedIssues(\"" + storyKey + "\") AND issuetype = \"" + cfg.taskIssueType + "\"";
        return jira.search(jql, List.of("summary", "status", "issuetype"), 500);
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
                    "project", "priority", "labels", "components", "fixVersions", "reporter", "creator", "parent"));
            if (cfg.approverField != null) fieldList.add(cfg.approverField);
            if (cfg.storyPointsField != null) fieldList.add(cfg.storyPointsField);
            g.issue = Json.map(jira.get("/rest/api/3/issue/" + enc(key) + "?fields=" + enc(String.join(",", fieldList))));
            Map<String, Object> f = Json.map(g.issue.get("fields"));

            // 1. It is a user story.
            String type = Json.str(f, "issuetype", "name");
            boolean isStory = false;
            for (String s : cfg.storyIssueTypes) isStory |= s.equalsIgnoreCase(type);
            g.check("isUserStory", isStory, "issue type is '" + type + "'; accepted " + cfg.storyIssueTypes);

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

            // 5. Segregation of duties: the author of the story cannot approve it.
            if (cfg.forbidSelfApproval) {
                String reporter = Json.str(f, "reporter", "accountId"), creator = Json.str(f, "creator", "accountId");
                boolean self = approverId != null && (approverId.equals(reporter) || approverId.equals(creator));
                g.check("notSelfApproved", approverId != null && !self,
                        approverId == null ? "no approver to check"
                                : self ? "approver is the story's reporter/creator" : "approver differs from reporter/creator");
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

            // 8. Optional: the parent epic must still be in an approved status.
            String epicKey = Json.str(f, "parent", "key");
            g.approval.put("epicKey", epicKey);
            if (cfg.requireApprovedEpic) {
                if (epicKey == null) {
                    g.check("parentEpicApproved", false, "story has no parent epic");
                } else {
                    Map<String, Object> epic = Json.map(jira.get("/rest/api/3/issue/" + enc(epicKey) + "?fields=status,issuetype"));
                    String epicStatus = Json.str(epic, "fields", "status", "name");
                    boolean ok = cfg.isEpicApprovedStatus(epicStatus);
                    g.check("parentEpicApproved", ok, "epic " + epicKey + " status '" + epicStatus + "'; accepted "
                            + cfg.epicApprovedStatuses);
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
        String projectKey, taskIssueType, taskLinkMode, issueLinkType, approverField, storyPointsField;
        List<String> storyIssueTypes, approvedStatuses, epicApprovedStatuses, approverAccountIds, approverGroups,
                protectedFields;
        List<Object> defaultLabels;
        boolean forbidSelfApproval, rejectIfEditedAfterApproval, approverFieldMustMatchTransition, requireApprovedEpic,
                requireUnitTestsForCode, commentOnStory;
        int maxTasksPerRun, scanLimit;
        final Map<String, Set<String>> groupCache = new HashMap<>();

        static Config load(Path p) throws Exception {
            if (!Files.exists(p)) {
                throw new IllegalStateException("config not found at " + p
                        + " - copy user-story-agent.config.example.json to user-story-agent.config.json and fill it in");
            }
            Map<String, Object> m = Json.map(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
            Config c = new Config();
            c.projectKey = blankToNull(Json.str(m, "projectKey"));
            c.storyIssueTypes = strings(m.get("storyIssueTypes"));
            if (c.storyIssueTypes.isEmpty()) c.storyIssueTypes = List.of("Story");
            c.taskIssueType = orDefault(Json.str(m, "taskIssueType"), "Sub-task");
            c.taskLinkMode = orDefault(Json.str(m, "taskLinkMode"), "parent");
            c.issueLinkType = orDefault(Json.str(m, "issueLinkType"), "Relates");
            c.approverField = blankToNull(Json.str(m, "approverField"));
            c.storyPointsField = blankToNull(Json.str(m, "storyPointsField"));
            c.approvedStatuses = strings(m.get("approvedStatuses"));
            if (c.approvedStatuses.isEmpty()) c.approvedStatuses = List.of("Approved");
            c.epicApprovedStatuses = strings(m.get("epicApprovedStatuses"));
            if (c.epicApprovedStatuses.isEmpty()) c.epicApprovedStatuses = List.of("Approved", "In Progress");
            c.approverAccountIds = strings(Json.at(m, "authorizedApprovers", "accountIds"));
            c.approverGroups = strings(Json.at(m, "authorizedApprovers", "groups"));
            c.protectedFields = strings(m.get("protectedFields"));
            if (c.protectedFields.isEmpty()) c.protectedFields = List.of("summary", "description");
            c.defaultLabels = new ArrayList<>(strings(m.get("defaultLabels")));
            c.forbidSelfApproval = bool(m.get("forbidSelfApproval"), true);
            c.rejectIfEditedAfterApproval = bool(m.get("rejectIfEditedAfterApproval"), true);
            c.approverFieldMustMatchTransition = bool(m.get("approverFieldMustMatchTransition"), true);
            c.requireApprovedEpic = bool(m.get("requireApprovedEpic"), true);
            c.requireUnitTestsForCode = bool(m.get("requireUnitTestsForCode"), true);
            c.commentOnStory = bool(m.get("commentOnStory"), true);
            c.maxTasksPerRun = m.get("maxTasksPerRun") instanceof Number n ? n.intValue() : 20;
            c.scanLimit = m.get("scanLimit") instanceof Number n ? n.intValue() : 50;
            if (!c.taskLinkMode.equals("parent") && !c.taskLinkMode.equals("link")) {
                throw new IllegalStateException("taskLinkMode must be 'parent' (sub-tasks) or 'link' (issue link)");
            }
            return c;
        }

        boolean isApprovedStatus(String s) {
            return matchesAny(approvedStatuses, s);
        }

        boolean isEpicApprovedStatus(String s) {
            return matchesAny(epicApprovedStatuses, s);
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

            // If env vars not set, try to load from .env file
            if ((url == null || url.isBlank()) || (email == null || email.isBlank()) || (token == null || token.isBlank())) {
                Map<String, String> env = new HashMap<>();
                // Try .env in current directory first
                try {
                    env.putAll(loadEnvFile(Path.of(".env")));
                } catch (Exception e) {
                    // ignore
                }
                // Then try in scripts folder
                if (env.isEmpty()) {
                    try {
                        env.putAll(loadEnvFile(Path.of(".claude/skills/user-story-agent/scripts/.env")));
                    } catch (Exception e) {
                        // ignore
                    }
                }
                if (url == null || url.isBlank()) url = env.get("JIRA_URL");
                if (email == null || email.isBlank()) email = env.get("JIRA_EMAIL");
                if (token == null || token.isBlank()) token = env.get("JIRA_TOKEN");
            }

            List<String> missing = new ArrayList<>();
            if (url == null || url.isBlank()) missing.add("JIRA_URL");
            if (email == null || email.isBlank()) missing.add("JIRA_EMAIL");
            if (token == null || token.isBlank()) missing.add("JIRA_TOKEN");
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

                int eqIdx = line.indexOf('=');
                if (eqIdx <= 0) continue;

                String key = line.substring(0, eqIdx).trim();
                String value = line.substring(eqIdx + 1).trim();

                // Remove surrounding quotes if present
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }

                env.put(key, value);
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

        static Map<String, Object> task(Map<String, Object> t, String storyKey) {
            List<Object> c = new ArrayList<>();
            String objective = Json.str(t, "objective");
            if (objective != null && !objective.isBlank()) c.add(para(objective));

            c.add(heading("Target"));
            List<Object> target = new ArrayList<>();
            target.add(paraOf(List.of(text("Kind: "), code(orDefault(Json.str(t, "target", "kind"), "?")))));
            target.add(paraOf(List.of(text("Module: "), code(orDefault(Json.str(t, "target", "module"), "?")))));
            target.add(para("Layer: " + orDefault(Json.str(t, "target", "layer"), "?")));
            String domain = Json.str(t, "target", "domain");
            if (domain != null) target.add(para("Domain: " + domain));
            String agent = Json.str(t, "target", "agent");
            if (agent != null) target.add(para("Agent: " + agent));
            List<Object> files = Json.list(Json.at(t, "target", "files"));
            if (!files.isEmpty()) {
                List<Object> inline = new ArrayList<>();
                inline.add(text("Files: "));
                for (int i = 0; i < files.size(); i++) {
                    if (i > 0) inline.add(text(", "));
                    inline.add(code(String.valueOf(files.get(i))));
                }
                target.add(paraOf(inline));
            }
            c.add(bullets(target));

            List<Object> notes = Json.list(t.get("implementationNotes"));
            if (!notes.isEmpty()) {
                c.add(heading("Implementation notes"));
                c.add(bullets(notes));
            }

            List<Object> tests = Json.list(t.get("unitTests"));
            if (!tests.isEmpty()) {
                c.add(heading("Unit test cases"));
                List<Object> items = new ArrayList<>();
                for (Object u : tests) {
                    List<Object> inline = new ArrayList<>();
                    inline.add(code(Json.str(u, "name")));
                    StringBuilder sb = new StringBuilder();
                    if (Json.str(u, "given") != null) sb.append(" - Given ").append(Json.str(u, "given"));
                    if (Json.str(u, "when") != null) sb.append(sb.length() == 0 ? " - When " : ", when ").append(Json.str(u, "when"));
                    sb.append(sb.length() == 0 ? " - Then " : ", then ").append(Json.str(u, "then"));
                    inline.add(text(sb.toString()));
                    items.add(paraOf(inline));
                }
                c.add(bullets(items));
            }

            List<Object> ac = Json.list(t.get("acceptanceCriteria"));
            if (!ac.isEmpty()) {
                c.add(heading("Acceptance criteria"));
                c.add(bullets(ac));
            }

            List<Object> deps = Json.list(t.get("dependsOn"));
            if (!deps.isEmpty()) {
                c.add(heading("Depends on"));
                c.add(bullets(deps));
            }
            c.add(para("Generated by Story Agent from approved story " + storyKey + "."));
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
                this.s = s;
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

    static List<Object> names(Object v) {
        List<Object> out = new ArrayList<>();
        for (Object o : Json.list(v)) out.add(Json.str(o, "name"));
        return out;
    }

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.list(v)) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim());
        return out;
    }

    static boolean bool(Object v, boolean dflt) {
        return v instanceof Boolean b ? b : dflt;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String orDefault(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
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
