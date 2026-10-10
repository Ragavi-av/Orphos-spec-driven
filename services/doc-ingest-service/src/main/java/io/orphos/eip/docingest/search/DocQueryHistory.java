package io.orphos.eip.docingest.search;

import io.orphos.eip.docingest.arcade.DocArcadeClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ArcadeDB-backed log of document search queries.
 * Persists to the DocSearchHistory document type (created by DocIngestSchemaBootstrap).
 */
@Component
public class DocQueryHistory {

    private static final Logger log = LogManager.getLogger(DocQueryHistory.class);
    private static final int MAX_LIST = 100;

    public record Entry(
        String searchId,
        String tenantId,
        String userSub,
        String query,
        int resultCount,
        long latencyMs,
        Instant timestamp
    ) {}

    private final DocArcadeClient arcade;

    public DocQueryHistory(DocArcadeClient arcade) {
        this.arcade = arcade;
    }

    public void record(String tenantId, String userSub, String query, int resultCount, long latencyMs) {
        Map<String, Object> params = new HashMap<>();
        params.put("searchId",    UUID.randomUUID().toString());
        params.put("tenantId",    tenantId != null ? tenantId : "");
        params.put("userSub",     userSub  != null ? userSub  : "");
        params.put("query",       query    != null ? query    : "");
        params.put("resultCount", resultCount);
        params.put("latencyMs",   latencyMs);
        params.put("timestamp",   Instant.now().toString());

        try {
            arcade.commandNoResult(
                "INSERT INTO DocSearchHistory SET " +
                "searchId = :searchId, tenantId = :tenantId, userSub = :userSub, query = :query, " +
                "resultCount = :resultCount, latencyMs = :latencyMs, timestamp = :timestamp",
                params);
        } catch (Exception e) {
            log.warn("Failed to persist DocSearchHistory entry: {}", e.getMessage());
        }
    }

    public List<Entry> list(String tenantId, int limit) {
        int cap = Math.min(limit, MAX_LIST);
        try {
            Map<String, Object> params = new HashMap<>();
            String sql;
            if (tenantId != null) {
                params.put("tenantId", tenantId);
                params.put("cap",      cap);
                sql = "SELECT * FROM DocSearchHistory WHERE tenantId = :tenantId " +
                      "ORDER BY timestamp DESC LIMIT :cap";
            } else {
                params.put("cap", cap);
                sql = "SELECT * FROM DocSearchHistory ORDER BY timestamp DESC LIMIT :cap";
            }
            return arcade.query(sql, params).stream()
                .map(DocQueryHistory::rowToEntry)
                .toList();
        } catch (Exception e) {
            log.warn("Failed to list DocSearchHistory for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    /**
     * One user's own search history, newest first.
     *
     * Distinct from {@link #list(String, int)}, which is the tenant-wide admin
     * view: this is what the AI Co-pilot's history panel reads, so it must
     * never return a colleague's queries. Both the tenant AND the subject are
     * bound in the WHERE clause rather than filtered after the fetch, so the
     * LIMIT applies to the caller's own rows instead of trimming the tenant's.
     */
    public List<Entry> listForUser(String tenantId, String userSub, int limit) {
        if (userSub == null || userSub.isBlank()) return List.of();
        int cap = Math.min(limit, MAX_LIST);
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("tenantId", tenantId != null ? tenantId : "");
            params.put("userSub",  userSub);
            params.put("cap",      cap);
            return arcade.query(
                    "SELECT * FROM DocSearchHistory WHERE tenantId = :tenantId "
                  + "AND userSub = :userSub ORDER BY timestamp DESC LIMIT :cap", params)
                .stream()
                .map(DocQueryHistory::rowToEntry)
                .toList();
        } catch (Exception e) {
            log.warn("Failed to list DocSearchHistory for user: {}", e.getMessage());
            return List.of();
        }
    }

    /** Delete one of the caller's own history entries. False when nothing matched. */
    public boolean deleteForUser(String tenantId, String userSub, String searchId) {
        if (userSub == null || userSub.isBlank() || searchId == null || searchId.isBlank()) return false;
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("tenantId", tenantId != null ? tenantId : "");
            params.put("userSub",  userSub);
            params.put("searchId", searchId);
            // userSub is part of the predicate, so a caller cannot delete an
            // entry belonging to someone else by guessing its id.
            arcade.commandNoResult(
                    "DELETE FROM DocSearchHistory WHERE tenantId = :tenantId "
                  + "AND userSub = :userSub AND searchId = :searchId", params);
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete DocSearchHistory entry: {}", e.getMessage());
            return false;
        }
    }

    /** Clear the caller's own history. */
    public boolean clearForUser(String tenantId, String userSub) {
        if (userSub == null || userSub.isBlank()) return false;
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("tenantId", tenantId != null ? tenantId : "");
            params.put("userSub",  userSub);
            arcade.commandNoResult(
                    "DELETE FROM DocSearchHistory WHERE tenantId = :tenantId AND userSub = :userSub",
                    params);
            return true;
        } catch (Exception e) {
            log.warn("Failed to clear DocSearchHistory: {}", e.getMessage());
            return false;
        }
    }

    private static Entry rowToEntry(Map<String, Object> row) {
        String id        = str(row, "searchId");
        String tenantId  = str(row, "tenantId");
        String userSub   = str(row, "userSub");
        String query     = str(row, "query");
        int resultCount  = toInt(row.get("resultCount"));
        long latencyMs   = toLong(row.get("latencyMs"));
        Instant ts       = parseInstant(str(row, "timestamp"));
        return new Entry(id, tenantId, userSub, query, resultCount, latencyMs, ts);
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : null;
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }
}
