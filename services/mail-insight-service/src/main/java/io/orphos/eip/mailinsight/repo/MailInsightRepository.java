package io.orphos.eip.mailinsight.repo;

import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.arcade.MailArcadeClient;
import io.orphos.eip.mailinsight.model.MailInsightDto;
import io.orphos.eip.mailinsight.model.SourceChannel;
import io.orphos.eip.mailinsight.security.CurrentUser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CRUD for the MailInsight document type. Every query filters by
 * the current tenant (resolved from {@link TenantContextHolder}); re-scans are
 * idempotent via the unique (tenantId, dedupeKey) index.
 *
 * <p>Re-scan policy: when an item already exists we refresh the inferred fields
 * (title/summary/priority/criticality/score/rationale/dueDate) but never clobber
 * user actions — {@code status}, {@code userPriorityOverride}, {@code reminderAt}
 * are left untouched.</p>
 */
@Repository
public class MailInsightRepository {

    private static final Logger log = LogManager.getLogger(MailInsightRepository.class);

    // Ordinal maps for in-memory sorting (ArcadeDB ORDER BY on these strings
    // would sort alphabetically, not by severity).
    private static final Map<String, Integer> PRIORITY_RANK = Map.of(
            "CRITICAL", 0, "HIGH", 1, "MEDIUM", 2, "LOW", 3);
    private static final Map<String, Integer> CRITICALITY_RANK = Map.of(
            "ALERT", 0, "WARNING", 1, "INFORMATION", 2);

    private final MailArcadeClient arcade;
    private final AccessGrantSource grants;

    public MailInsightRepository(MailArcadeClient arcade, AccessGrantSource grants) {
        this.arcade = arcade;
        this.grants = grants;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    private static String currentSub() {
        return CurrentUser.sub().orElse("");
    }

    // Visibility: an item is shown to a user if it is tenant-wide (ownerSub blank)
    // or owned by that user. Personal mailbox items never leak across users.
    private static final String OWNER_CLAUSE =
            " AND (ownerSub IS NULL OR ownerSub = '' OR ownerSub = :me)";

    // Snooze: an item with a reminder set in the future is hidden until its
    // reminder comes due (ISO-8601 strings compare correctly lexicographically).
    private static final String SNOOZE_CLAUSE =
            " AND (reminderAt IS NULL OR reminderAt = '' OR reminderAt <= :now)";

    /**
     * Insert a freshly inferred item, or refresh the inference of an existing one.
     * Returns true if a new row was created, false if an existing row was updated.
     */
    public boolean upsert(Map<String, Object> fields) {
        String tenantId = tenantId();
        String dedupeKey = (String) fields.get("dedupeKey");
        String now = Instant.now().toString();

        List<Map<String, Object>> existing = arcade.query(
                "SELECT id FROM MailInsight WHERE tenantId = :tenantId AND dedupeKey = :dedupeKey LIMIT 1",
                Map.of("tenantId", tenantId, "dedupeKey", dedupeKey));

        Map<String, Object> p = new HashMap<>(fields);
        p.put("tenantId", tenantId);
        p.put("updatedAt", now);
        // Backward-compat: rows produced before the "source" column existed (or
        // callers that forget to set it) still get a valid channel.
        p.putIfAbsent("source", SourceChannel.EMAIL);

        if (!existing.isEmpty()) {
            // Refresh inference only — preserve status / override / reminder.
            // "source" is NOT refreshed here: if a mailbox's provider changes
            // channel after the item was first scanned, the original channel it
            // was captured on stays authoritative for that row.
            arcade.commandNoResult(
                    "UPDATE MailInsight SET title = :title, summary = :summary, " +
                    "priority = :priority, criticality = :criticality, score = :score, " +
                    "scoreBreakdown = :scoreBreakdown, rationale = :rationale, dueDate = :dueDate, " +
                    "kind = :kind, inferenceSource = :inferenceSource, updatedAt = :updatedAt " +
                    "WHERE tenantId = :tenantId AND dedupeKey = :dedupeKey",
                    p);
            return false;
        }

        p.put("id", "mi-" + UUID.randomUUID());
        p.put("status", "OPEN");
        p.put("createdAt", now);
        p.putIfAbsent("ownerSub", "");
        arcade.commandNoResult(
                "INSERT INTO MailInsight SET id = :id, tenantId = :tenantId, mailboxId = :mailboxId, " +
                "ownerSub = :ownerSub, " +
                "kind = :kind, source = :source, sourceType = :sourceType, sourceRef = :sourceRef, dedupeKey = :dedupeKey, " +
                "title = :title, summary = :summary, senderDisplay = :senderDisplay, " +
                "priority = :priority, criticality = :criticality, score = :score, " +
                "scoreBreakdown = :scoreBreakdown, rationale = :rationale, dueDate = :dueDate, " +
                "receivedAt = :receivedAt, status = 'OPEN', inferenceSource = :inferenceSource, " +
                "createdAt = :createdAt, updatedAt = :updatedAt",
                p);
        return true;
    }

    /** Tasks visible to the current user, optionally filtered by status, sorted in-memory. */
    public List<MailInsightDto> listTasks(String sort, String status) {
        String sql = "SELECT * FROM MailInsight WHERE tenantId = :tenantId AND kind = 'TASK'"
                + OWNER_CLAUSE + SNOOZE_CLAUSE;
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId());
        params.put("me", currentSub());
        params.put("now", Instant.now().toString());
        if (status != null && !status.isBlank()) {
            sql += " AND status = :status";
            params.put("status", status);
        } else {
            // Default view hides completed/dismissed items.
            // NOTE: ArcadeDB's `NOT IN [..]` (square-bracket list literal) evaluates
            // to no matches even for values absent from the list; the parenthesised
            // form negates correctly. See listNotifications/summary for the same fix.
            sql += " AND status NOT IN ('DONE', 'DISMISSED')";
        }
        List<MailInsightDto> rows = arcade.query(sql, params).stream()
                .map(MailInsightDto::fromRow).toList();
        return sortTasks(rows, sort);
    }

    /**
     * Calendar items (MEETING / EVENT) visible to the current user whose start
     * falls inside {@code [fromIso, toIso)}, earliest first.
     *
     * <p>The window is filtered in Java rather than SQL because the start time is
     * an ISO-8601 STRING, and lexical comparison is only sound when every row uses
     * the same offset and precision — which is not guaranteed once rows arrive
     * from more than one connector. Volumes here are a single user's calendar for
     * a day, so parsing them is cheap.</p>
     *
     * <p>Start time comes from {@code dueDate}: {@code GraphConnector} maps a
     * Microsoft Graph event's {@code start} onto both {@code dueDate} and
     * {@code receivedAt}. {@code receivedAt} is the fallback so a row written by
     * some other connector, or before that mapping existed, still places.</p>
     */
    public List<MailInsightDto> listMeetings(String fromIso, String toIso) {
        String sql = "SELECT * FROM MailInsight WHERE tenantId = :tenantId "
                + "AND sourceType IN ('MEETING', 'EVENT') "
                + "AND status NOT IN ('DONE', 'DISMISSED')" + OWNER_CLAUSE;
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId());
        params.put("me", currentSub());

        Instant from = parseInstant(fromIso);
        Instant to = parseInstant(toIso);

        return arcade.query(sql, params).stream()
                .map(MailInsightDto::fromRow)
                .map(d -> Map.entry(d, startOf(d)))
                .filter(e -> e.getValue() != null)
                .filter(e -> (from == null || !e.getValue().isBefore(from))
                          && (to == null || e.getValue().isBefore(to)))
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Event start: dueDate, else receivedAt. Null when neither parses. */
    private static Instant startOf(MailInsightDto d) {
        Instant i = parseInstant(d.dueDate());
        return i != null ? i : parseInstant(d.receivedAt());
    }

    /** Lenient ISO-8601 parse — a malformed stored value must not fail the request. */
    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso.trim());
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(iso.trim()).toInstant();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** Notifications visible to the current user, optionally filtered by criticality level. */
    public List<MailInsightDto> listNotifications(String level) {
        String sql = "SELECT * FROM MailInsight WHERE tenantId = :tenantId AND kind = 'NOTIFICATION' " +
                     "AND status NOT IN ('DONE', 'DISMISSED')" + OWNER_CLAUSE + SNOOZE_CLAUSE;
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId());
        params.put("me", currentSub());
        params.put("now", Instant.now().toString());
        if (level != null && !level.isBlank()) {
            sql += " AND criticality = :level";
            params.put("level", level);
        }
        return arcade.query(sql, params).stream()
                .map(MailInsightDto::fromRow)
                .sorted(Comparator
                        .comparingInt((MailInsightDto d) -> rank(CRITICALITY_RANK, d.criticality()))
                        .thenComparing(Comparator.comparing(MailInsightDto::receivedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))))
                .toList();
    }

    /** Counts per kind / criticality for the tab badges. */
    public Map<String, Object> summary() {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT kind, criticality, count(*) AS c FROM MailInsight " +
                "WHERE tenantId = :tenantId AND status NOT IN ('DONE', 'DISMISSED')" + OWNER_CLAUSE + SNOOZE_CLAUSE +
                " GROUP BY kind, criticality",
                Map.of("tenantId", tenantId(), "me", currentSub(), "now", Instant.now().toString()));
        int tasks = 0, alerts = 0, warnings = 0, info = 0;
        for (Map<String, Object> r : rows) {
            int c = r.get("c") instanceof Number n ? n.intValue() : 0;
            String kind = String.valueOf(r.get("kind"));
            String crit = String.valueOf(r.get("criticality"));
            if ("TASK".equals(kind)) {
                tasks += c;
            } else if ("NOTIFICATION".equals(kind)) {
                switch (crit) {
                    case "ALERT" -> alerts += c;
                    case "WARNING" -> warnings += c;
                    default -> info += c;
                }
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("tasks", tasks);
        out.put("alerts", alerts);
        out.put("warnings", warnings);
        out.put("information", info);
        out.put("notifications", alerts + warnings + info);
        return out;
    }

    /**
     * One item, or null when it doesn't exist, belongs to someone else, or lies outside the
     * caller's persona domains ({@link PersonaDomainPolicy}). Every per-item action — status,
     * priority, reminder, the reply/meeting agents — resolves through here, so an item a
     * persona can't see can't be acted on either. Background work (no user) is unrestricted.
     */
    public MailInsightDto findById(String id) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM MailInsight WHERE tenantId = :tenantId AND id = :id" + OWNER_CLAUSE + " LIMIT 1",
                Map.of("tenantId", tenantId(), "id", id, "me", currentSub()));
        if (rows.isEmpty()) return null;
        MailInsightDto item = MailInsightDto.fromRow(rows.get(0));
        PersonaDomainPolicy.Scope scope = PersonaDomainPolicy.current(grants);
        boolean critical = PersonaDomainPolicy.isCritical(item.priority(), item.criticality());
        return scope.allows(item.domain(), critical, item.personal()) ? item : null;
    }

    public boolean updatePriority(String id, String priority) {
        return mutate(id,
                "UPDATE MailInsight SET userPriorityOverride = :priority, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("priority", priority));
    }

    public boolean updateCriticality(String id, String criticality) {
        return mutate(id,
                "UPDATE MailInsight SET userCriticalityOverride = :criticality, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("criticality", criticality));
    }

    /** Stamps {@code completedAt} on DONE and clears it on any other status (e.g. reopen). */
    public boolean updateStatus(String id, String status) {
        return mutate(id,
                "UPDATE MailInsight SET status = :status, completedAt = :completedAt, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("status", status,
                        "completedAt", "DONE".equals(status) ? Instant.now().toString() : ""));
    }

    /**
     * Tasks the current user completed at or after {@code sinceIso} — the "done today" half
     * of the mobile progress ring. Items completed before completedAt existed have none, so
     * they never count as today's. ISO-8601 UTC strings compare correctly lexicographically.
     */
    public List<MailInsightDto> listCompletedTasksSince(String sinceIso) {
        return arcade.query(
                "SELECT * FROM MailInsight WHERE tenantId = :tenantId AND kind = 'TASK'" + OWNER_CLAUSE +
                " AND status = 'DONE' AND completedAt IS NOT NULL AND completedAt >= :since",
                Map.of("tenantId", tenantId(), "me", currentSub(), "since", sinceIso))
                .stream().map(MailInsightDto::fromRow).toList();
    }

    public boolean updateReminder(String id, String reminderAtIso) {
        return mutate(id,
                "UPDATE MailInsight SET reminderAt = :reminderAt, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                // ArcadeDB treats an empty string as "clear".
                Map.of("reminderAt", reminderAtIso == null ? "" : reminderAtIso));
    }

    /** Items whose reminder has come due — re-surfaced by the reminder scheduler. */
    public List<MailInsightDto> findDueReminders(String nowIso) {
        return arcade.query(
                "SELECT * FROM MailInsight WHERE tenantId = :tenantId " +
                "AND reminderAt IS NOT NULL AND reminderAt <> '' AND reminderAt <= :now",
                Map.of("tenantId", tenantId(), "now", nowIso))
                .stream().map(MailInsightDto::fromRow).toList();
    }

    /**
     * Clear a due reminder when it fires (the snooze window has ended, so the
     * item is no longer hidden by the read filter). Status is left untouched —
     * a reminder never resurrects an item the user already closed.
     */
    public void clearReminder(String id) {
        arcade.commandNoResult(
                "UPDATE MailInsight SET reminderAt = '', updatedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "now", Instant.now().toString()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean mutate(String id, String sql, Map<String, Object> extra) {
        if (findById(id) == null) return false;
        Map<String, Object> params = new HashMap<>(extra);
        params.put("tenantId", tenantId());
        params.put("id", id);
        params.put("now", Instant.now().toString());
        try {
            arcade.commandNoResult(sql, params);
            return true;
        } catch (Exception e) {
            log.warn("Failed to mutate MailInsight {}: {}", id, e.getMessage());
            return false;
        }
    }

    private static List<MailInsightDto> sortTasks(List<MailInsightDto> rows, String sort) {
        Comparator<MailInsightDto> cmp = switch (sort == null ? "priority" : sort) {
            case "dueDate" -> Comparator.comparing(MailInsightDto::dueDate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "criticality" -> Comparator.comparingInt(d -> rank(CRITICALITY_RANK, d.criticality()));
            default -> Comparator.comparingInt((MailInsightDto d) -> rank(PRIORITY_RANK, d.priority()))
                    .thenComparing(Comparator.comparingInt(MailInsightDto::score).reversed());
        };
        return rows.stream().sorted(cmp).toList();
    }

    private static int rank(Map<String, Integer> ranks, String value) {
        if (value == null) return Integer.MAX_VALUE;
        return ranks.getOrDefault(value.toUpperCase(), Integer.MAX_VALUE);
    }
}
