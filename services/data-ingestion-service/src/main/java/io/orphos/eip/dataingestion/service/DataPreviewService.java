package io.orphos.eip.dataingestion.service;

import io.orphos.eip.dataingestion.component.SensitiveDataMasker;
import io.orphos.eip.dataingestion.dao.DataSourcePools;
import io.orphos.eip.dataingestion.service.DataSourceService;
import io.orphos.eip.dataingestion.service.MetadataDiscoveryService;
import io.orphos.eip.dataingestion.vo.MetadataDtos.SchemaSnapshot;
import io.orphos.eip.dataingestion.vo.MetadataDtos.TableInfo;
import io.orphos.eip.dataingestion.vo.PreviewDtos.ClassificationLevel;
import io.orphos.eip.dataingestion.vo.PreviewDtos.PreviewColumn;
import io.orphos.eip.dataingestion.vo.PreviewDtos.PreviewResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Masked row preview of a source table (requirement 8.5).
 *
 * <p>Injection safety: the requested schema/table pair is resolved against the
 * discovered metadata snapshot ({@link MetadataDiscoveryService}) and the SQL
 * is built from the <em>discovered</em> identifiers, never the caller's input.
 * A name the snapshot doesn't know is a 404 — arbitrary SQL cannot reach the
 * source. Identifiers are additionally quoted with the driver's own quote
 * string.</p>
 *
 * <p>Masking fails closed: any column whose name matches a sensitive pattern
 * is masked, and a data source classified {@code RESTRICTED} (req 8.6) has
 * <em>every</em> column masked regardless of name.</p>
 */
@Service
public class DataPreviewService {

    /** Hard ceiling on preview rows regardless of the requested limit. */
    static final int MAX_ROWS = 50;
    static final int DEFAULT_ROWS = 10;

    private static final Logger log = LogManager.getLogger(DataPreviewService.class);

    private final DataSourceService dataSources;
    private final DataSourcePools pools;
    private final MetadataDiscoveryService metadata;
    private final SensitiveDataMasker masker;

    public DataPreviewService(DataSourceService dataSources, DataSourcePools pools,
                              MetadataDiscoveryService metadata, SensitiveDataMasker masker) {
        this.dataSources = dataSources;
        this.pools = pools;
        this.metadata = metadata;
        this.masker = masker;
    }

    public PreviewResponse preview(String dataSourceId, String schema, String table, int limit) {
        Map<String, Object> row = dataSources.require(dataSourceId);
        if ("REST".equals(String.valueOf(row.get("kind")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Preview applies to JDBC sources; '" + row.get("name") + "' is a REST source");
        }
        if (table == null || table.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "table is required");
        }
        int rowLimit = limit <= 0 ? DEFAULT_ROWS : Math.min(limit, MAX_ROWS);

        // Resolve against discovered metadata — unknown names are 404, and the
        // SQL below uses the snapshot's identifiers, not the caller's strings.
        TableInfo resolved = resolveTable(metadata.getSnapshot(dataSourceId, false), schema, table);

        boolean restricted = ClassificationLevel.RESTRICTED.name()
                .equalsIgnoreCase(str(row, "classification"));

        List<PreviewColumn> columns = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        DataSource ds = pools.forConnection(row);
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            String qualified = (resolved.schema().isBlank() ? "" : quote(conn, resolved.schema()) + ".")
                    + quote(conn, resolved.name());
            // setMaxRows instead of a LIMIT/FETCH FIRST clause — enforced by the
            // driver on every dialect the GENERIC_JDBC kind may bring in.
            st.setMaxRows(rowLimit);
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + qualified)) {
                ResultSetMetaData md = rs.getMetaData();
                boolean[] masked = new boolean[md.getColumnCount()];
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String name = md.getColumnLabel(i);
                    masked[i - 1] = restricted || masker.isSensitive(name);
                    columns.add(new PreviewColumn(name, masked[i - 1]));
                }
                while (rs.next()) {
                    List<String> values = new ArrayList<>(masked.length);
                    for (int i = 1; i <= masked.length; i++) {
                        Object value = rs.getObject(i);
                        values.add(masked[i - 1]
                                ? masker.mask(columns.get(i - 1).name(), value)
                                : (value == null ? null : String.valueOf(value)));
                    }
                    rows.add(values);
                }
            }
        } catch (SQLException e) {
            log.warn("Preview failed for {} table {}: {}", dataSourceId, resolved.name(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Preview query failed: " + e.getMessage());
        }
        String qualifiedName = resolved.schema().isBlank()
                ? resolved.name() : resolved.schema() + "." + resolved.name();
        return new PreviewResponse(qualifiedName, columns, rows, rowLimit,
                str(row, "classification"), tags(row));
    }

    /**
     * The discovered table matching the requested (schema, table) pair —
     * case-insensitive; blank schema matches any. 404 when the snapshot has
     * never seen the name (also the injection guard — see class doc).
     */
    private static TableInfo resolveTable(SchemaSnapshot snapshot, String schema, String table) {
        return snapshot.tables().stream()
                .filter(t -> t.name().equalsIgnoreCase(table.trim()))
                .filter(t -> schema == null || schema.isBlank() || t.schema().equalsIgnoreCase(schema.trim()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Table '" + table + "' not found in the discovered source metadata"));
    }

    private static String quote(Connection conn, String identifier) {
        String q;
        try {
            q = conn.getMetaData().getIdentifierQuoteString();
        } catch (SQLException e) {
            q = "\"";
        }
        if (q == null || q.isBlank()) q = "\"";
        return q + identifier.replace(q, q + q) + q;
    }

    private static List<String> tags(Map<String, Object> row) {
        Object v = row.get("classificationTags");
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }
}
