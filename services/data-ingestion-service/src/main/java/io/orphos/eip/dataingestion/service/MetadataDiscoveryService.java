package io.orphos.eip.dataingestion.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.dataingestion.component.RestMetadataDiscoverer;
import io.orphos.eip.dataingestion.dao.DataSourcePools;
import io.orphos.eip.dataingestion.service.DataSourceService;
import io.orphos.eip.dataingestion.vo.MetadataDtos.ColumnInfo;
import io.orphos.eip.dataingestion.vo.MetadataDtos.ForeignKeyInfo;
import io.orphos.eip.dataingestion.vo.MetadataDtos.IndexInfo;
import io.orphos.eip.dataingestion.vo.MetadataDtos.SchemaSnapshot;
import io.orphos.eip.dataingestion.vo.MetadataDtos.TableInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers schema metadata from a connected JDBC source via
 * {@link DatabaseMetaData} — tables, columns and types (req 2.2), primary keys,
 * foreign keys and indexes (req 2.3), and row-count statistics (req 2.4).
 *
 * <p>Snapshots are cached for 24 h per (tenant, dataSource) — req 2.5 — and can
 * be refreshed on demand, either fully or one table at a time (incremental
 * refresh, req 2.7). Per-object failures are logged and surfaced as warnings
 * while discovery continues with partial results (req 2.6).</p>
 */
@Service
public class MetadataDiscoveryService {

    private static final Logger log = LogManager.getLogger(MetadataDiscoveryService.class);

    /** System schemas that are never business data — skipped during discovery. */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "pg_toast", "sys", "systables",
            "INFORMATION_SCHEMA", "SYS");

    private final DataSourceService dataSources;
    private final DataSourcePools pools;
    private final RestMetadataDiscoverer restDiscoverer;
    private final Cache<String, SchemaSnapshot> cache;
    private final int maxRowCountTables;

    public MetadataDiscoveryService(DataSourceService dataSources, DataSourcePools pools,
                                    RestMetadataDiscoverer restDiscoverer,
                                    @Value("${ingestion.metadata.cache-hours:24}") long cacheHours,
                                    @Value("${ingestion.metadata.max-row-count-tables:200}") int maxRowCountTables) {
        this.dataSources = dataSources;
        this.pools = pools;
        this.restDiscoverer = restDiscoverer;
        this.maxRowCountTables = maxRowCountTables;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(cacheHours))
                .maximumSize(500)
                .build();
    }

    /** Cached snapshot for the data source, discovering on first access (req 2.1, 2.5). */
    public SchemaSnapshot getSnapshot(String dataSourceId, boolean refresh) {
        String key = cacheKey(dataSourceId);
        if (refresh) cache.invalidate(key);
        SchemaSnapshot cached = cache.getIfPresent(key);
        if (cached != null) {
            return new SchemaSnapshot(cached.dataSourceId(), cached.capturedAt(), true,
                    cached.databaseProduct(), cached.tables(), cached.warnings());
        }
        SchemaSnapshot fresh = discover(dataSourceId, null);
        cache.put(key, fresh);
        return fresh;
    }

    /**
     * Incremental refresh (req 2.7): re-discover a single table and splice it
     * into the cached snapshot, leaving every other table's metadata untouched —
     * avoids a full re-scan on large sources.
     */
    public SchemaSnapshot refreshTable(String dataSourceId, String tableName) {
        SchemaSnapshot single = discover(dataSourceId, tableName);
        if (single.tables().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Table '" + tableName + "' not found in source");
        }
        String key = cacheKey(dataSourceId);
        SchemaSnapshot base = cache.getIfPresent(key);
        if (base == null) {
            cache.put(key, single);
            return single;
        }
        Map<String, TableInfo> byName = new LinkedHashMap<>();
        base.tables().forEach(t -> byName.put(t.schema() + "." + t.name(), t));
        single.tables().forEach(t -> byName.put(t.schema() + "." + t.name(), t));
        SchemaSnapshot merged = new SchemaSnapshot(dataSourceId, Instant.now().toString(), false,
                base.databaseProduct(), List.copyOf(byName.values()), single.warnings());
        cache.put(key, merged);
        return merged;
    }

    public void invalidate(String dataSourceId) {
        cache.invalidate(cacheKey(dataSourceId));
    }

    /** Full (or single-table) discovery straight from the source. */
    private SchemaSnapshot discover(String dataSourceId, String onlyTable) {
        Map<String, Object> row = dataSources.require(dataSourceId);
        String kind = String.valueOf(row.get("kind"));
        // HTTP sources (plain REST + enterprise systems) describe their objects
        // in configJson (endpoints), discovered/sampled over HTTP rather than via
        // JDBC DatabaseMetaData. A REST source with no objects yet yields an empty
        // snapshot with a "define endpoints" warning instead of a hard error.
        if ("REST".equals(kind) || "REST_OPENAPI".equals(kind) || "ZOHO_ERP".equals(kind)) {
            return restDiscoverer.discover(row);
        }
        List<String> warnings = new ArrayList<>();
        List<TableInfo> tables = new ArrayList<>();
        String product = "";

        DataSource ds = pools.forConnection(row);
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            product = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();

            try (ResultSet rs = md.getTables(null, null,
                    onlyTable == null ? "%" : onlyTable, new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String schema = ns(rs.getString("TABLE_SCHEM"));
                    String name = rs.getString("TABLE_NAME");
                    if (SYSTEM_SCHEMAS.contains(schema)) continue;
                    String type = ns(rs.getString("TABLE_TYPE"));
                    tables.add(describeTable(conn, md, schema, name, type,
                            tables.size() < maxRowCountTables, warnings));
                }
            }
        } catch (SQLException e) {
            // Connection-level failure with nothing gathered yet — that IS an error.
            if (tables.isEmpty()) {
                log.warn("Metadata discovery failed for {}: {}", dataSourceId, e.getMessage());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Metadata discovery failed: " + e.getMessage());
            }
            warnings.add("Discovery interrupted, results are partial: " + e.getMessage());
            log.warn("Metadata discovery partial for {}: {}", dataSourceId, e.getMessage());
        }
        if (tables.size() > maxRowCountTables) {
            warnings.add("Row counts were computed for the first " + maxRowCountTables
                    + " tables only (" + tables.size() + " discovered)");
        }
        return new SchemaSnapshot(dataSourceId, Instant.now().toString(), false,
                product, tables, warnings);
    }

    private TableInfo describeTable(Connection conn, DatabaseMetaData md, String schema,
                                    String name, String type, boolean countRows,
                                    List<String> warnings) {
        List<ColumnInfo> columns = new ArrayList<>();
        List<ForeignKeyInfo> fks = new ArrayList<>();
        List<IndexInfo> indexes = new ArrayList<>();
        Set<String> pkColumns = new java.util.HashSet<>();

        // Primary keys first, so columns can be flagged (req 2.3).
        try (ResultSet rs = md.getPrimaryKeys(null, emptyToNull(schema), name)) {
            while (rs.next()) pkColumns.add(rs.getString("COLUMN_NAME"));
        } catch (SQLException e) {
            warn(warnings, schema, name, "primary keys", e);
        }

        try (ResultSet rs = md.getColumns(null, emptyToNull(schema), name, "%")) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                columns.add(new ColumnInfo(
                        col,
                        ns(rs.getString("TYPE_NAME")),
                        rs.getInt("DATA_TYPE"),
                        rs.getInt("COLUMN_SIZE"),
                        !"NO".equalsIgnoreCase(ns(rs.getString("IS_NULLABLE"))),
                        "YES".equalsIgnoreCase(ns(rs.getString("IS_AUTOINCREMENT"))),
                        pkColumns.contains(col)));
            }
        } catch (SQLException e) {
            warn(warnings, schema, name, "columns", e);
        }

        try (ResultSet rs = md.getImportedKeys(null, emptyToNull(schema), name)) {
            while (rs.next()) {
                fks.add(new ForeignKeyInfo(
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME")));
            }
        } catch (SQLException e) {
            warn(warnings, schema, name, "foreign keys", e);
        }

        // approximate=true lets the driver answer from statistics instead of scanning.
        try (ResultSet rs = md.getIndexInfo(null, emptyToNull(schema), name, false, true)) {
            Map<String, IndexAccumulator> acc = new LinkedHashMap<>();
            while (rs.next()) {
                String idxName = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idxName == null || col == null) continue;
                boolean unique = !rs.getBoolean("NON_UNIQUE");
                acc.computeIfAbsent(idxName, k -> new IndexAccumulator(unique)).columns.add(col);
            }
            acc.forEach((idxName, a) -> indexes.add(new IndexInfo(idxName, a.unique, a.columns)));
        } catch (SQLException e) {
            warn(warnings, schema, name, "indexes", e);
        }

        long rowCount = -1;
        // Drivers disagree on the type label ("TABLE" vs H2's "BASE TABLE") —
        // count anything table-ish, skip views.
        if (countRows && type.toUpperCase().contains("TABLE")) {
            rowCount = countRows(conn, schema, name, warnings);
        }
        return new TableInfo(schema, name, type, rowCount, columns, fks, indexes);
    }

    /** Row-count statistic (req 2.4). Identifier-quoted; failures degrade to -1 + warning. */
    private long countRows(Connection conn, String schema, String name, List<String> warnings) {
        String qualified = (schema.isBlank() ? "" : quote(conn, schema) + ".") + quote(conn, name);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + qualified)) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException e) {
            warn(warnings, schema, name, "row count", e);
            return -1;
        }
    }

    private static String quote(Connection conn, String identifier) {
        String q;
        try {
            q = conn.getMetaData().getIdentifierQuoteString();
        } catch (SQLException e) {
            q = "\"";
        }
        if (q == null || q.isBlank()) q = "\"";
        // Escape embedded quote chars — discovery-supplied names go into SQL text.
        return q + identifier.replace(q, q + q) + q;
    }

    private static void warn(List<String> warnings, String schema, String table,
                             String what, SQLException e) {
        String msg = (schema.isBlank() ? table : schema + "." + table)
                + ": could not read " + what + " (" + e.getMessage() + ")";
        warnings.add(msg);
        log.debug("Metadata discovery warning: {}", msg);
    }

    private static String cacheKey(String dataSourceId) {
        return TenantContextHolder.requireTenantId() + ":" + dataSourceId;
    }

    private static String ns(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static final class IndexAccumulator {
        final boolean unique;
        final List<String> columns = new ArrayList<>();
        IndexAccumulator(boolean unique) { this.unique = unique; }
    }
}
