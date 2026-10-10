package io.orphos.eip.dataingestion.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.dataingestion.vo.MappingDtos.ColumnMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.EdgeMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import io.orphos.eip.dataingestion.vo.MappingDtos.SaveMappingRequest;
import org.springframework.stereotype.Repository;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CRUD for {@code IngestMapping} — saved source→target mapping
 * configurations, reusable across jobs (requirement 3.8). The column-mapping
 * list is persisted as a JSON string ({@code mappingsJson}).
 */
@Repository
public class MappingRepository {

    private final ArcadeDbTemplate arcade;
    private final ObjectMapper json = new ObjectMapper();

    public MappingRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    public List<Map<String, Object>> list(String dataSourceId) {
        if (dataSourceId == null || dataSourceId.isBlank()) {
            return arcade.query(
                    "SELECT * FROM IngestMapping WHERE tenantId = :tenantId ORDER BY name",
                    Map.of("tenantId", tenantId()));
        }
        return arcade.query(
                "SELECT * FROM IngestMapping WHERE tenantId = :tenantId AND dataSourceId = :ds ORDER BY name",
                Map.of("tenantId", tenantId(), "ds", dataSourceId));
    }

    public Map<String, Object> findById(String id) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM IngestMapping WHERE tenantId = :tenantId AND id = :id LIMIT 1",
                Map.of("tenantId", tenantId(), "id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findByName(String name) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM IngestMapping WHERE tenantId = :tenantId AND name = :name LIMIT 1",
                Map.of("tenantId", tenantId(), "name", name));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String insert(SaveMappingRequest req, List<String> warnings) {
        String id = "map-" + UUID.randomUUID();
        String now = Instant.now().toString();
        Map<String, Object> p = params(req, warnings);
        p.put("id", id);
        p.put("tenantId", tenantId());
        p.put("createdAt", now);
        p.put("updatedAt", now);
        arcade.commandNoResult(
                "INSERT INTO IngestMapping SET id = :id, tenantId = :tenantId, name = :name, " +
                "dataSourceId = :dataSourceId, sourceSchema = :sourceSchema, sourceTable = :sourceTable, " +
                "targetClass = :targetClass, targetKind = :targetKind, keyTarget = :keyTarget, " +
                "incrementalColumn = :incrementalColumn, " +
                "mappingsJson = :mappingsJson, edgesJson = :edgesJson, warningsJson = :warningsJson, " +
                "enabled = :enabled, createdAt = :createdAt, updatedAt = :updatedAt", p);
        return id;
    }

    public void update(String id, SaveMappingRequest req, List<String> warnings) {
        Map<String, Object> p = params(req, warnings);
        p.put("tenantId", tenantId());
        p.put("id", id);
        p.put("updatedAt", Instant.now().toString());
        arcade.commandNoResult(
                "UPDATE IngestMapping SET name = :name, dataSourceId = :dataSourceId, " +
                "sourceSchema = :sourceSchema, sourceTable = :sourceTable, targetClass = :targetClass, " +
                "targetKind = :targetKind, keyTarget = :keyTarget, incrementalColumn = :incrementalColumn, " +
                "mappingsJson = :mappingsJson, edgesJson = :edgesJson, warningsJson = :warningsJson, " +
                "enabled = :enabled, updatedAt = :updatedAt WHERE tenantId = :tenantId AND id = :id", p);
    }

    public void delete(String id) {
        arcade.commandNoResult(
                "DELETE FROM IngestMapping WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id));
    }

    private Map<String, Object> params(SaveMappingRequest req, List<String> warnings) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", req.name().trim());
        p.put("dataSourceId", req.dataSourceId());
        p.put("sourceSchema", req.sourceSchema() == null ? "" : req.sourceSchema());
        p.put("sourceTable", req.sourceTable());
        p.put("targetClass", req.targetClass());
        p.put("targetKind", (req.targetKind() == null || req.targetKind().isBlank())
                ? "DOCUMENT" : req.targetKind().trim().toUpperCase());
        p.put("keyTarget", req.keyTarget() == null ? "" : req.keyTarget());
        p.put("incrementalColumn", req.incrementalColumn() == null ? "" : req.incrementalColumn());
        p.put("mappingsJson", write(req.mappings()));
        p.put("edgesJson", write(req.edges() == null ? List.of() : req.edges()));
        p.put("warningsJson", write(warnings));
        p.put("enabled", req.enabled());
        return p;
    }

    public MappingView toView(Map<String, Object> row) {
        return new MappingView(
                str(row, "id"),
                str(row, "name"),
                str(row, "dataSourceId"),
                str(row, "sourceSchema"),
                str(row, "sourceTable"),
                str(row, "targetClass"),
                targetKindOrDefault(str(row, "targetKind")),
                str(row, "keyTarget"),
                str(row, "incrementalColumn"),
                readMappings(str(row, "mappingsJson")),
                readEdges(str(row, "edgesJson")),
                Boolean.TRUE.equals(row.get("enabled")),
                readStrings(str(row, "warningsJson")),
                str(row, "createdAt"),
                str(row, "updatedAt"));
    }

    public List<ColumnMapping> readMappings(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new TypeReference<List<ColumnMapping>>() {});
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException("corrupt mappingsJson", e));
        }
    }

    /** Edge definitions; absent/blank (mappings predating edges) → none. */
    public List<EdgeMapping> readEdges(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new TypeReference<List<EdgeMapping>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readStrings(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException("mapping serialisation failed", e));
        }
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }

    /** Older mappings predate targetKind — treat a blank value as DOCUMENT. */
    private static String targetKindOrDefault(String raw) {
        return (raw == null || raw.isBlank()) ? "DOCUMENT" : raw;
    }
}
