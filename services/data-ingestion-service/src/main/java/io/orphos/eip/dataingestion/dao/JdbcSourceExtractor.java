package io.orphos.eip.dataingestion.dao;

import io.orphos.eip.dataingestion.component.JobExecutionEngine;
import io.orphos.eip.dataingestion.dao.DataSourcePools;
import io.orphos.eip.dataingestion.vo.DataSourceDtos;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SourceKind;
import io.orphos.eip.dataingestion.vo.ExtractRequest;
import io.orphos.eip.dataingestion.vo.MappingDtos.ColumnMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.EdgeMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Streams rows out of a JDBC source. This is the extraction logic that previously
 * lived inline in {@code JobExecutionEngine}, moved behind {@link SourceExtractor}
 * unchanged: distinct-column SELECT, server-side cursor streaming
 * ({@code autoCommit=false} + {@code fetchSize}), an in-query watermark filter for
 * incremental runs, and identifier-safety validation on everything concatenated
 * into SQL.
 */
@Component
public class JdbcSourceExtractor implements SourceExtractor {

    private static final Logger log = LogManager.getLogger(JdbcSourceExtractor.class);
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final DataSourcePools pools;
    private final int fetchSize;

    public JdbcSourceExtractor(DataSourcePools pools,
                               @Value("${ingestion.jobs.fetch-size:500}") int fetchSize) {
        this.pools = pools;
        this.fetchSize = fetchSize;
    }

    @Override
    public boolean supports(SourceKind kind) {
        return !DataSourceDtos.isHttpKind(kind);   // POSTGRESQL / SQLSERVER / GENERIC_JDBC
    }

    @Override
    public RowCursor open(ExtractRequest req) {
        MappingView mapping = req.mapping();
        boolean incremental = req.incremental();
        String watermark = req.watermark() == null ? "" : req.watermark();
        String incrementalColumn = mapping.incrementalColumn();

        requireSafeSourceIdentifiers(mapping, incremental);

        String from = (mapping.sourceSchema().isBlank() ? "" : mapping.sourceSchema() + ".")
                + mapping.sourceTable();
        boolean filtered = incremental && !watermark.isBlank();
        String where = filtered ? " WHERE " + incrementalColumn + " > ?" : "";
        String order = incremental ? " ORDER BY " + incrementalColumn : "";
        List<String> columns = selectColumns(mapping, incremental);

        DataSource ds = pools.forConnection(req.source());
        Connection conn = null;
        try {
            conn = ds.getConnection();
            conn.setAutoCommit(false);                                    // cursor streaming
            long total = countRows(conn, from, where, filtered ? watermark : null);
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + String.join(", ", columns) + " FROM " + from + where + order);
            ps.setFetchSize(fetchSize);
            if (filtered) bindWatermark(ps, 1, watermark);
            ResultSet rs = ps.executeQuery();
            return new JdbcCursor(conn, ps, rs, columns, incremental ? incrementalColumn : null, total);
        } catch (Exception e) {
            closeQuietly(conn);
            throw new IllegalStateException("Could not open source query: " + e.getMessage(), e);
        }
    }

    /**
     * Distinct source columns to select: those feeding column mappings, the
     * watermark column when incremental, and every edge's key column (its value
     * is the related vertex's externalId, read from the row).
     */
    private static List<String> selectColumns(MappingView mapping, boolean incremental) {
        Set<String> columns = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnMapping m : mapping.mappings()) {
            for (String src : m.sources()) {
                if (seen.add(src.toLowerCase(Locale.ROOT))) columns.add(src);
            }
        }
        for (EdgeMapping e : edgesOf(mapping)) {
            if (e.sourceKeyColumn() != null && seen.add(e.sourceKeyColumn().toLowerCase(Locale.ROOT))) {
                columns.add(e.sourceKeyColumn());
            }
        }
        if (incremental && seen.add(mapping.incrementalColumn().toLowerCase(Locale.ROOT))) {
            columns.add(mapping.incrementalColumn());
        }
        return new ArrayList<>(columns);
    }

    private static List<EdgeMapping> edgesOf(MappingView mapping) {
        return mapping.edges() == null ? List.of() : mapping.edges();
    }

    private static long countRows(Connection conn, String from, String where, String watermark) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + from + where)) {
            if (watermark != null) bindWatermark(ps, 1, watermark);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The watermark is carried as a string but must be bound in the source
     * column's own type. Numeric-looking values bind as long, ISO timestamps as
     * timestamp, anything else as string.
     */
    private static void bindWatermark(PreparedStatement ps, int index, String watermark)
            throws java.sql.SQLException {
        String w = watermark.trim();
        try {
            ps.setLong(index, Long.parseLong(w));
            return;
        } catch (NumberFormatException ignored) { }
        try {
            ps.setTimestamp(index, java.sql.Timestamp.valueOf(
                    java.time.LocalDateTime.parse(w.replace(' ', 'T'))));
            return;
        } catch (Exception ignored) { }
        ps.setString(index, w);
    }

    private static void requireSafeSourceIdentifiers(MappingView mapping, boolean incremental) {
        requireIdent(mapping.sourceTable(), "source table");
        if (!mapping.sourceSchema().isBlank()) requireIdent(mapping.sourceSchema(), "source schema");
        for (ColumnMapping m : mapping.mappings()) {
            for (String src : m.sources()) requireIdent(src, "source column");
        }
        for (EdgeMapping e : edgesOf(mapping)) {
            if (e.sourceKeyColumn() != null) requireIdent(e.sourceKeyColumn(), "relationship key column");
        }
        if (incremental) requireIdent(mapping.incrementalColumn(), "incremental column");
    }

    private static void requireIdent(String name, String what) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalStateException("Unsafe " + what + " identifier: '" + name + "'");
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) try { c.close(); } catch (Exception ignore) { }
    }

    /** Streams the open ResultSet; tracks the last incremental value (ORDER BY ASC → last = max). */
    private static final class JdbcCursor implements RowCursor {
        private final Connection conn;
        private final PreparedStatement ps;
        private final ResultSet rs;
        private final List<String> columns;
        private final String incrementalColumn;    // null when not incremental
        private final long total;
        private String maxWatermark;

        JdbcCursor(Connection conn, PreparedStatement ps, ResultSet rs, List<String> columns,
                   String incrementalColumn, long total) {
            this.conn = conn;
            this.ps = ps;
            this.rs = rs;
            this.columns = columns;
            this.incrementalColumn = incrementalColumn;
            this.total = total;
        }

        @Override public long total() { return total; }
        @Override public String maxWatermark() { return maxWatermark; }

        @Override
        public Map<String, String> next() {
            try {
                if (!rs.next()) return null;
                Map<String, String> row = new LinkedHashMap<>();
                for (String col : columns) row.put(col, rs.getString(col));
                if (incrementalColumn != null) {
                    String w = rs.getString(incrementalColumn);
                    if (w != null) maxWatermark = w;
                }
                return row;
            } catch (Exception e) {
                throw new IllegalStateException("Source read failed: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            try { rs.close(); } catch (Exception ignore) { }
            try { ps.close(); } catch (Exception ignore) { }
            try { conn.close(); } catch (Exception ignore) { }
        }
    }
}
