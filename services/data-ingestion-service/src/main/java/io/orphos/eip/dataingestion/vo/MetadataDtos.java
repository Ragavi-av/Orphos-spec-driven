package io.orphos.eip.dataingestion.vo;

import java.util.List;

/** Response shapes for source metadata discovery (requirement 2). */
public final class MetadataDtos {

    private MetadataDtos() {}

    public record ColumnInfo(
            String name,
            String typeName,      // driver-reported SQL type name (VARCHAR, int8, ...)
            int jdbcType,         // java.sql.Types constant
            int size,
            boolean nullable,
            boolean autoIncrement,
            boolean primaryKey) {}

    public record ForeignKeyInfo(
            String column,        // FK column in this table
            String refTable,      // referenced (PK) table
            String refColumn) {}  // referenced (PK) column

    public record IndexInfo(
            String name,
            boolean unique,
            List<String> columns) {}

    public record TableInfo(
            String schema,
            String name,
            String type,          // TABLE | VIEW
            long rowCount,        // -1 when count failed/skipped (see warnings)
            List<ColumnInfo> columns,
            List<ForeignKeyInfo> foreignKeys,
            List<IndexInfo> indexes) {}

    /**
     * Full snapshot of a source's schema. {@code warnings} carries per-object
     * discovery failures — requirement 2.6: log the error, continue with
     * partial results instead of failing the whole discovery.
     */
    public record SchemaSnapshot(
            String dataSourceId,
            String capturedAt,
            boolean fromCache,
            String databaseProduct,
            List<TableInfo> tables,
            List<String> warnings) {}
}
