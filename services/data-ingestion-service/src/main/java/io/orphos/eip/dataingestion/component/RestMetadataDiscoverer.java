package io.orphos.eip.dataingestion.component;

import io.orphos.eip.dataingestion.vo.ErpConfig;
import io.orphos.eip.dataingestion.vo.ErpConnection;
import io.orphos.eip.dataingestion.component.ErpHttpClient;
import io.orphos.eip.dataingestion.vo.MetadataDtos.ColumnInfo;
import io.orphos.eip.dataingestion.vo.MetadataDtos.SchemaSnapshot;
import io.orphos.eip.dataingestion.vo.MetadataDtos.TableInfo;
import io.orphos.eip.dataingestion.component.SecretRefService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an enterprise-system source's configured objects (endpoints) into the
 * same {@link SchemaSnapshot} shape the JDBC discoverer produces, so the mapping
 * UI treats ERP objects exactly like database tables — objects become "tables",
 * response fields become "columns".
 *
 * <p>When an object has no field list configured (typical for Zoho modules,
 * whose fields are tenant-customisable), one page is <b>live-sampled</b> and the
 * field names are inferred from the returned records. Sampling failures degrade
 * to a warning + empty column list rather than failing the whole discovery.</p>
 */
@Component
public class RestMetadataDiscoverer {

    private static final Logger log = LogManager.getLogger(RestMetadataDiscoverer.class);

    private final ErpHttpClient http;
    private final SecretRefService secrets;

    public RestMetadataDiscoverer(ErpHttpClient http, SecretRefService secrets) {
        this.http = http;
        this.secrets = secrets;
    }

    public SchemaSnapshot discover(Map<String, Object> row) {
        String dataSourceId = str(row, "id");
        ErpConfig cfg = ErpConfig.parse(str(row, "configJson"));
        String resolvedSecret = secrets.resolve(str(row, "credentialRef")).orElse(null);
        ErpConnection conn = ErpConnection.from(row, resolvedSecret);

        List<String> warnings = new ArrayList<>();
        List<TableInfo> tables = new ArrayList<>();
        for (ErpConfig.ErpObject obj : cfg.objectsOrEmpty()) {
            List<ColumnInfo> columns = new ArrayList<>();
            if (!obj.fieldsOrEmpty().isEmpty()) {
                for (ErpConfig.ErpField f : obj.fieldsOrEmpty()) columns.add(column(f.name(), f.typeOrString()));
            } else {
                columns.addAll(sampleColumns(conn, obj, warnings));
            }
            tables.add(new TableInfo("", obj.name(), "OBJECT", -1, columns, List.of(), List.of()));
        }
        if (tables.isEmpty()) {
            warnings.add("This source has no configured objects — add or import endpoints in the source dialog");
        }
        String product = ErpConfig.VENDOR_ZOHO.equalsIgnoreCase(cfg.erpVendor())
                ? "Zoho (" + (cfg.dataCenter() == null ? "com" : cfg.dataCenter()) + ")"
                : "REST/OpenAPI";
        return new SchemaSnapshot(dataSourceId, Instant.now().toString(), false, product, tables, warnings);
    }

    /** Fetch one page and infer columns from the union of keys across the sampled records. */
    private List<ColumnInfo> sampleColumns(ErpConnection conn, ErpConfig.ErpObject obj, List<String> warnings) {
        List<ColumnInfo> columns = new ArrayList<>();
        try {
            ErpHttpClient.Page page = http.fetch(conn, obj,
                    conn.config().paginationOrNone().startPageOrDefault(), null, Map.of());
            Set<String> names = new LinkedHashSet<>();
            for (Map<String, Object> record : page.records()) names.addAll(record.keySet());
            for (String name : names) columns.add(column(name, "STRING"));
            if (columns.isEmpty()) {
                warnings.add(obj.name() + ": endpoint returned no records to infer fields from");
            }
        } catch (Exception e) {
            warnings.add(obj.name() + ": could not sample fields (" + e.getMessage() + ")");
            log.debug("ERP field sampling failed for {}: {}", obj.name(), e.getMessage());
        }
        return columns;
    }

    /** An ERP field as a ColumnInfo. A field named "id" is flagged as the primary key. */
    private static ColumnInfo column(String name, String type) {
        boolean pk = "id".equalsIgnoreCase(name);
        return new ColumnInfo(name, type, jdbcType(type), 0, true, false, pk);
    }

    /** Rough java.sql.Types mapping so the mapping UI's type hints line up. */
    private static int jdbcType(String type) {
        return switch (type == null ? "STRING" : type.toUpperCase()) {
            case "INTEGER" -> Types.BIGINT;
            case "NUMBER" -> Types.NUMERIC;
            case "BOOLEAN" -> Types.BOOLEAN;
            case "DATE" -> Types.TIMESTAMP;
            default -> Types.VARCHAR;
        };
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }
}
