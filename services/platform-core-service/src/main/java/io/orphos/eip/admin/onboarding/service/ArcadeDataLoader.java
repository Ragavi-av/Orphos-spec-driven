package io.orphos.eip.admin.onboarding.service;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.ColumnInfo;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.ColumnType;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.EntityModel;
import io.orphos.eip.admin.onboarding.parse.ParsedTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads a parsed table's rows into a per-entity ArcadeDB vertex type inside the
 * tenant's own (DEDICATED) database.
 *
 * <p>Re-upload semantics are UPSERT keyed by the entity's id column: existing
 * records (same key) are updated, new ones inserted, and rows not in the file
 * are left untouched. Entities without an inferred key fall back to replace
 * (wipe this tenant's rows of that type, then insert) so re-uploads don't
 * duplicate.</p>
 */
@Service
public class ArcadeDataLoader {

    private static final Logger log = LogManager.getLogger(ArcadeDataLoader.class);
    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /**
     * @param arcade the template routed to the tenant's chosen ArcadeDB server
     *               (from {@code ArcadeConnectionResolver}); all DDL/DML runs on it.
     * @return number of rows upserted/inserted.
     */
    public int load(EntityModel entity, ParsedTable table, String tenantId, String database,
                    ArcadeDbTemplate arcade) {
        String cls = entity.className();
        if (!IDENT.matcher(cls).matches()) {
            throw new IllegalArgumentException("Unsafe entity class name: " + cls);
        }

        arcade.commandNoResultOn(database, "CREATE VERTEX TYPE " + cls + " IF NOT EXISTS", Map.of());
        arcade.commandNoResultOn(database, "CREATE PROPERTY " + cls + ".tenantId IF NOT EXISTS STRING", Map.of());
        arcade.commandNoResultOn(database, "CREATE INDEX IF NOT EXISTS ON " + cls + " (tenantId) NOTUNIQUE", Map.of());

        String key = entity.idProperty();
        boolean upsert = key != null && IDENT.matcher(key).matches();
        if (upsert) {
            // UPSERT requires an index on the WHERE column.
            arcade.commandNoResultOn(database, "CREATE PROPERTY " + cls + "." + key + " IF NOT EXISTS STRING", Map.of());
            try {
                arcade.commandNoResultOn(database, "CREATE INDEX IF NOT EXISTS ON " + cls + " (" + key + ") UNIQUE", Map.of());
            } catch (RuntimeException e) {
                // Duplicate keys in the data → fall back to a non-unique index.
                arcade.commandNoResultOn(database, "CREATE INDEX IF NOT EXISTS ON " + cls + " (" + key + ") NOTUNIQUE", Map.of());
            }
        } else {
            // No key to match on → replace this tenant's rows so re-upload doesn't duplicate.
            arcade.commandNoResultOn(database, "DELETE FROM " + cls + " WHERE tenantId = :t", Map.of("t", tenantId));
        }

        List<String> headers = table.headers();
        List<ColumnInfo> cols = entity.columns().stream()
                .filter(c -> IDENT.matcher(c.property()).matches())
                .toList();

        int loaded = 0;
        for (List<String> row : table.rows()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (ColumnInfo col : cols) {
                int idx = headers.indexOf(col.name());
                if (idx < 0 || idx >= row.size()) continue;
                Object val = convert(row.get(idx), col.type());
                if (val != null) fields.put(col.property(), val);
            }
            if (fields.isEmpty()) continue;
            fields.put("tenantId", tenantId);

            if (upsert && fields.get(key) != null) {
                upsertRow(arcade, database, cls, key, fields);
            } else {
                insertRow(arcade, database, cls, fields);
            }
            loaded++;
        }
        log.info("{} {} rows into {}.{} (tenant {})",
                upsert ? "Upserted" : "Loaded", loaded, database, cls, tenantId);
        return loaded;
    }

    /** UPDATE … SET … UPSERT WHERE key = :__key — updates an existing record or inserts a new one. */
    private void upsertRow(ArcadeDbTemplate arcade, String database, String cls, String key, Map<String, Object> fields) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(cls).append(" SET ");
        Map<String, Object> params = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (i++ > 0) sql.append(", ");
            sql.append(e.getKey()).append(" = :").append(e.getKey());
            params.put(e.getKey(), e.getValue());
        }
        sql.append(" UPSERT WHERE ").append(key).append(" = :__key");
        params.put("__key", fields.get(key));
        try {
            arcade.commandNoResultOn(database, sql.toString(), params);
        } catch (RuntimeException ex) {
            log.warn("Skipped a {} upsert: {}", cls, ex.getMessage());
        }
    }

    private void insertRow(ArcadeDbTemplate arcade, String database, String cls, Map<String, Object> fields) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(cls).append(" SET ");
        Map<String, Object> params = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (i++ > 0) sql.append(", ");
            sql.append(e.getKey()).append(" = :").append(e.getKey());
            params.put(e.getKey(), e.getValue());
        }
        try {
            arcade.commandNoResultOn(database, sql.toString(), params);
        } catch (RuntimeException ex) {
            log.warn("Skipped a {} row: {}", cls, ex.getMessage());
        }
    }

    private Object convert(String raw, ColumnType type) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            return switch (type) {
                case INTEGER -> Long.parseLong(s.replace(",", ""));
                case NUMBER  -> Double.parseDouble(s.replace(",", ""));
                case BOOLEAN -> s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
                case DATE, STRING -> s;
            };
        } catch (NumberFormatException e) {
            return s;
        }
    }
}
