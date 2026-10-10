package io.orphos.eip.mobilebff.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.orphos.eip.mobilebff.component.DownstreamClient;
import io.orphos.eip.mobilebff.repository.WorkspaceStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-inferred, persistent Approvals. Items requiring an executive decision are
 * detected from the same task list the web dashboard's "Tasks & Approvals" panel shows
 * (mail-insight {@code /api/mail-insight/tasks}: email/channel items plus live pending
 * decisions, incidents, …) using the tenant's AI-Admin inference rules
 * ({@link MobileConfigService}), stored once (idempotent by sourceId), and then owned by
 * the exec: the ONLY user-entered part is the decision, captured with its timestamp.
 *
 * <p>Each approval's {@code sourceRef} is the mail-insight task id, which is how web and
 * mobile map a task to its approval. Deciding closes that task (and transitions the
 * underlying Decision for data-source decisions), so it leaves every list at once.</p>
 *
 * <p>Each item carries an expiry; once past it a still-pending item becomes EXPIRED and
 * can no longer be decided.</p>
 */
@Service
public class ApprovalService {

    private static final Logger log = LogManager.getLogger(ApprovalService.class);
    private static final String TYPE = "Approval";

    private static final Set<String> AFFIRMATIVE = Set.of("APPROVE", "APPROVED", "YES", "GO", "GO_AHEAD", "GOAHEAD");
    private static final Set<String> NEGATIVE = Set.of("REJECT", "REJECTED", "NO", "HOLD", "DECLINE", "DECLINED");

    private final DownstreamClient ds;
    private final WorkspaceStore workspace;
    private final MobileConfigService config;
    private final String decisionLogUrl;
    private final String mailInsightUrl;
    private final boolean demoSeedEnabled;
    /** Demo rows are only ever seeded into this tenant — never into a real customer tenant. */
    private final String demoTenantId;

    /** sourceId prefix of approvals linked to a mail-insight task. */
    private static final String TASK_PREFIX = "task-";
    /** sourceId prefix of the pre-unification approvals inferred straight from /api/decisions. */
    private static final String LEGACY_DECISION_PREFIX = "decision-";
    /** mail-insight's synthetic id prefix for a live pending Decision. */
    private static final String DATA_DECISION_PREFIX = "ds-decision-";

    public ApprovalService(DownstreamClient ds, WorkspaceStore workspace, MobileConfigService config,
                           @Value("${eip.downstream.decision-log-url:http://eip-decision-log:8086}") String decisionLogUrl,
                           @Value("${eip.downstream.mail-insight-url:http://eip-mail-insight:8092}") String mailInsightUrl,
                           @Value("${mobile.demo.seed-enabled:false}") boolean demoSeedEnabled,
                           @Value("${mobile.demo.tenant-id:}") String demoTenantId) {
        this.ds = ds;
        this.workspace = workspace;
        this.config = config;
        this.decisionLogUrl = decisionLogUrl;
        this.mailInsightUrl = mailInsightUrl;
        this.demoSeedEnabled = demoSeedEnabled;
        this.demoTenantId = demoTenantId == null ? "" : demoTenantId.trim();
    }

    /** Infer approvals from downstream, persist new ones, then return the owner's list
     *  with expiry applied. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listAndSync(String tenantId, String ownerSub, String auth) {
        Map<String, Object> cfg = config.get(tenantId);
        Map<String, Object> ac = (Map<String, Object>) cfg.getOrDefault("approval", Map.of());
        Set<String> openTaskIds = null;
        try {
            openTaskIds = infer(tenantId, ownerSub, auth, ac);
        } catch (Exception e) {
            log.warn("Approval inference skipped for tenant {}: {}", tenantId, e.getMessage());
        }
        List<Map<String, Object>> rows = workspace.list(TYPE, tenantId, ownerSub);
        if (rows.isEmpty() && demoSeedEnabled && demoTenantId.equals(tenantId) && !demoTenantId.isEmpty()
                && ownerSub != null && !ownerSub.isBlank()) {
            seedDemo(tenantId, ownerSub);
            rows = workspace.list(TYPE, tenantId, ownerSub);
        }
        return applyExpiry(tenantId, ownerSub, visible(rows, openTaskIds));
    }

    /**
     * Keep the list in step with the task list. A still-pending approval is hidden when its
     * task has left the list (done / dismissed on the web), and pending approvals inferred
     * by the old decision-log path are hidden because the task-linked ones replace them.
     * Decided rows stay, so the Approvals screen keeps its history. {@code openTaskIds} is
     * null when the task list could not be read — then nothing is hidden on its account.
     */
    private static List<Map<String, Object>> visible(List<Map<String, Object>> rows, Set<String> openTaskIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if ("PENDING".equals(row.get("status"))) {
                String sourceId = String.valueOf(row.get("sourceId"));
                if (sourceId.startsWith(LEGACY_DECISION_PREFIX)) continue;
                if (openTaskIds != null && sourceId.startsWith(TASK_PREFIX)
                        && !openTaskIds.contains(String.valueOf(row.get("sourceRef")))) continue;
            }
            out.add(row);
        }
        return out;
    }

    /** Seed a spread of demo approvals (one per type, one already decided, one near expiry). */
    private void seedDemo(String tenantId, String ownerSub) {
        long now = Instant.now().toEpochMilli();
        long day = 86_400_000L;
        add(tenantId, ownerSub, "demo-appr-1", "EMAIL", "Renew Customer Alpha contract (#4928-B)",
                "Renewal needs your approval before end of day to avoid a service lapse; pricing terms are favourable versus the prior cycle.",
                "APPROVE_REJECT", now + day);
        add(tenantId, ownerSub, "demo-appr-2", "MS_TEAMS", "Go-ahead: migrate to the new data-centre vendor",
                "The infrastructure team needs your go-ahead to migrate before the renewal deadline.",
                "GO_AHEAD", now + 3 * day);
        add(tenantId, ownerSub, "demo-appr-3", "EMAIL", "Publish the press statement today?",
                "Legal has cleared the draft. Do you approve publishing today? A yes/no response is needed before 2 PM.",
                "YES_NO", now + (day / 2));
        add(tenantId, ownerSub, "demo-appr-4", "EMAIL", "Q3 capex budget — data-centre upgrade ($250K)",
                "Finance requests approval of the Q3 capital expenditure for the data-centre upgrade.",
                "APPROVE_REJECT", now + 5 * day);
        // One already decided, for the "actioned" state.
        Map<String, Object> decided = baseApproval("demo-appr-5", "SLACK",
                "Executive offsite venue booking", "Approve the proposed venue for the leadership offsite.",
                "APPROVE_REJECT", now + 7 * day);
        decided.put("status", "APPROVED");
        decided.put("decision", "APPROVE");
        decided.put("decidedAt", now - day);
        workspace.add(TYPE, tenantId, ownerSub, decided);
        log.info("Seeded {} demo approvals for owner in tenant {}", 5, tenantId);
    }

    private void add(String tenantId, String ownerSub, String sourceId, String source,
                     String subject, String summary, String type, long expiresAt) {
        workspace.add(TYPE, tenantId, ownerSub, baseApproval(sourceId, source, subject, summary, type, expiresAt));
    }

    private Map<String, Object> baseApproval(String sourceId, String source, String subject,
                                             String summary, String type, long expiresAt) {
        long now = Instant.now().toEpochMilli();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("sourceId", sourceId);
        b.put("source", source);
        b.put("subject", subject);
        b.put("summary", summary);
        b.put("approvalType", type);
        b.put("status", "PENDING");
        b.put("decision", null);
        b.put("decidedAt", null);
        b.put("inferredAt", now);
        b.put("expiresAt", expiresAt);
        return b;
    }

    /**
     * Infer approvals from the open task list. Returns the ids of every task in that list
     * (so {@link #visible} can drop approvals whose task has closed), or null when the list
     * could not be read.
     */
    @SuppressWarnings("unchecked")
    private Set<String> infer(String tenantId, String ownerSub, String auth, Map<String, Object> ac) {
        JsonNode resp = ds.get(mailInsightUrl, "/api/mail-insight/tasks", auth, tenantId);
        JsonNode tasks = resp == null ? null : resp.get("result");
        if (tasks == null || !tasks.isArray()) return null;

        List<String> keywords = ((List<Object>) ac.getOrDefault("keywords", List.of()))
                .stream().map(String::valueOf).map(String::toLowerCase).toList();
        Map<String, Object> typeRules = (Map<String, Object>) ac.getOrDefault("typeRules", Map.of());
        String defaultType = String.valueOf(ac.getOrDefault("defaultType", "APPROVE_REJECT"));
        long expiryMs = asLong(ac.get("defaultExpiryHours"), 72L) * 3600_000L;

        Set<String> openTaskIds = new HashSet<>();
        for (JsonNode d : tasks) {
            String id = text(d, "id");
            if (id.isEmpty()) continue;
            openTaskIds.add(id);
            String title = text(d, "title");
            String haystack = (title + " " + text(d, "summary") + " " + text(d, "rationale")).toLowerCase();

            String matched = keywords.stream().filter(k -> !k.isEmpty() && haystack.contains(k)).findFirst().orElse(null);
            if (matched == null) continue;

            String approvalType = defaultType;
            for (Map.Entry<String, Object> r : typeRules.entrySet()) {
                if (haystack.contains(r.getKey().toLowerCase())) { approvalType = String.valueOf(r.getValue()); break; }
            }

            long now = Instant.now().toEpochMilli();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sourceId", TASK_PREFIX + id);
            body.put("source", text(d, "source").isEmpty() ? "DATA" : text(d, "source"));
            body.put("sourceRef", id);
            body.put("subject", title.isEmpty() ? "Approval required" : title);
            body.put("summary", firstNonBlank(text(d, "summary"), text(d, "rationale")));
            body.put("approvalType", approvalType);
            body.put("status", "PENDING");
            body.put("decision", null);
            body.put("decidedAt", null);
            body.put("inferredAt", now);
            body.put("expiresAt", now + expiryMs);
            Map<String, Object> existing = workspace.upsertBySource(TYPE, tenantId, ownerSub, body);
            refreshPendingText(tenantId, ownerSub, existing, body);
        }
        return openTaskIds;
    }

    /**
     * An approval copies its task's title and summary when inferred. While it is still
     * pending, keep that text in step with the task, so an edit upstream shows on both apps
     * rather than only on the web's task list. Decided/expired rows keep what was decided on.
     */
    private void refreshPendingText(String tenantId, String ownerSub,
                                    Map<String, Object> existing, Map<String, Object> fresh) {
        if (existing == null || existing.get("id") == null || !"PENDING".equals(existing.get("status"))) return;
        Map<String, Object> patch = new LinkedHashMap<>();
        for (String k : List.of("subject", "summary")) {
            Object v = fresh.get(k);
            if (v != null && !v.equals(existing.get(k))) patch.put(k, v);
        }
        if (!patch.isEmpty()) {
            workspace.updateById(TYPE, tenantId, ownerSub, String.valueOf(existing.get("id")), patch);
        }
    }

    /** Flip still-pending items whose expiry has passed to EXPIRED (persisted). */
    private List<Map<String, Object>> applyExpiry(String tenantId, String ownerSub, List<Map<String, Object>> rows) {
        long now = Instant.now().toEpochMilli();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if ("PENDING".equals(row.get("status"))) {
                long exp = asLong(row.get("expiresAt"), Long.MAX_VALUE);
                if (now > exp) {
                    Map<String, Object> updated = workspace.updateById(TYPE, tenantId, ownerSub,
                            String.valueOf(row.get("id")), Map.of("status", "EXPIRED"));
                    out.add(updated != null ? updated : row);
                    continue;
                }
            }
            out.add(row);
        }
        return out;
    }

    /**
     * Record the executive's decision (the only user-entered part). Validated against the
     * item's approvalType; refused if the item is missing or already expired.
     */
    public Map<String, Object> decide(String tenantId, String ownerSub, String id, String rawDecision, String auth) {
        Map<String, Object> item = workspace.findById(TYPE, tenantId, ownerSub, id);
        if (item == null) return null;
        long now = Instant.now().toEpochMilli();
        boolean expired = "EXPIRED".equals(item.get("status")) || now > asLong(item.get("expiresAt"), Long.MAX_VALUE);
        if (expired) {
            if (!"EXPIRED".equals(item.get("status"))) {
                workspace.updateById(TYPE, tenantId, ownerSub, id, Map.of("status", "EXPIRED"));
            }
            throw new IllegalStateException("This approval has expired and can no longer be actioned.");
        }
        String decision = rawDecision == null ? "" : rawDecision.trim().toUpperCase();
        String status;
        if (AFFIRMATIVE.contains(decision)) status = "APPROVED";
        else if (NEGATIVE.contains(decision)) status = "REJECTED";
        else throw new IllegalArgumentException("Unrecognised decision '" + rawDecision + "'. "
                + "Use APPROVE/REJECT, YES/NO, or GO/HOLD.");

        // Close the linked task / decision FIRST and only then record the decision here, so
        // the three stores never disagree: a failed close leaves the approval pending (the
        // caller gets an error and can retry) instead of "decided" beside an open task.
        closeLinkedTask(item, status, tenantId, auth);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("decision", decision);
        patch.put("status", status);
        patch.put("decidedAt", now);
        return workspace.updateById(TYPE, tenantId, ownerSub, id, patch);
    }

    /**
     * A decided approval is finished work: for a live pending Decision record the outcome in
     * the decision log (which is what removes it from every executive's task list), and mark
     * the mail-insight task DONE so it drops out of the Tasks list on web and mobile.
     *
     * @throws LinkedTaskUpdateException when the authoritative close did not succeed
     */
    private void closeLinkedTask(Map<String, Object> item, String status, String tenantId, String auth) {
        String sourceId = String.valueOf(item.get("sourceId"));
        String ref = item.get("sourceRef") == null ? "" : String.valueOf(item.get("sourceRef"));
        if (!sourceId.startsWith(TASK_PREFIX) || ref.isEmpty()) return;
        String taskPath = "/api/mail-insight/items/" + ref + "/status";
        if (ref.startsWith(DATA_DECISION_PREFIX)) {
            // The Decision row is the source of truth; mail-insight's DONE for a ds-* item is
            // only a per-user override, so it is secondary once the Decision is closed.
            String decisionId = ref.substring(DATA_DECISION_PREFIX.length());
            if (!ds.patch(decisionLogUrl, "/api/decisions/" + decisionId + "/status", auth, tenantId,
                    Map.of("status", status, "source", "UI"))) {
                throw new LinkedTaskUpdateException("The decision log could not be updated.");
            }
            if (!ds.patch(mailInsightUrl, taskPath, auth, tenantId, Map.of("status", "DONE"))) {
                log.warn("Decision {} closed but mail-insight item {} not marked DONE", decisionId, ref);
            }
            return;
        }
        if (!ds.patch(mailInsightUrl, taskPath, auth, tenantId, Map.of("status", "DONE"))) {
            throw new LinkedTaskUpdateException("The linked task could not be closed.");
        }
    }

    /** The approval's task/decision could not be closed, so the decision was not recorded. */
    public static class LinkedTaskUpdateException extends RuntimeException {
        public LinkedTaskUpdateException(String message) { super(message); }
    }

    // --- small json/number helpers ---
    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }
    private static long asLong(Object v, long dflt) {
        if (v instanceof Number n) return n.longValue();
        try { return v == null ? dflt : Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return dflt; }
    }
}
