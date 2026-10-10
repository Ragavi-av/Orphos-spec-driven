package io.orphos.eip.dataingestion.component;

import io.orphos.eip.dataingestion.util.TransformFunctions;
import io.orphos.eip.dataingestion.vo.MappingDtos.ColumnMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.EdgeMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.SaveMappingRequest;
import io.orphos.eip.dataingestion.vo.MappingDtos.TargetType;
import io.orphos.eip.dataingestion.vo.MappingDtos.ValidationReport;
import io.orphos.eip.dataingestion.service.MetadataDiscoveryService;
import io.orphos.eip.dataingestion.vo.MetadataDtos.ColumnInfo;
import io.orphos.eip.dataingestion.vo.MetadataDtos.SchemaSnapshot;
import io.orphos.eip.dataingestion.vo.MetadataDtos.TableInfo;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates a mapping configuration against the source's discovered metadata.
 *
 * <p>Errors (block the save): unknown source table/columns, duplicate or
 * missing targets, unknown transform names, unmapped upsert key — the
 * requirement-3.7 "required target columns" check. Warnings (saved, shown in
 * the UI): questionable type conversions (req 3.5) such as VARCHAR→INTEGER or
 * DATE→BOOLEAN.</p>
 */
@Component
public class MappingValidator {

    /** Identifier shape for graph type names (edge + vertex types are concatenated into DDL server-side). */
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final MetadataDiscoveryService metadata;

    public MappingValidator(MetadataDiscoveryService metadata) {
        this.metadata = metadata;
    }

    public ValidationReport validate(SaveMappingRequest req) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // --- structural checks (no source access needed) -------------------
        Set<String> seenTargets = new HashSet<>();
        for (ColumnMapping m : req.mappings()) {
            if (!seenTargets.add(m.target().toLowerCase(Locale.ROOT))) {
                errors.add("Target property '" + m.target() + "' is mapped more than once");
            }
            String tName = TransformFunctions.name(m.transform());
            if (!TransformFunctions.NAMES.contains(tName)) {
                errors.add("Unknown transform '" + tName + "' on target '" + m.target()
                        + "' — supported: " + String.join(", ", TransformFunctions.NAMES));
            }
            targetTypeOf(m, errors);
        }
        if (req.keyTarget() != null && !req.keyTarget().isBlank()
                && req.mappings().stream().noneMatch(m -> m.target().equalsIgnoreCase(req.keyTarget()))) {
            errors.add("Key target '" + req.keyTarget()
                    + "' has no column mapping — the upsert key must be populated (required target)");
        }
        // Graph vertex targets are keyed by (tenantId, externalId): the mapping
        // MUST designate which property is the externalId (its keyTarget).
        boolean vertexTarget = req.targetKind() != null && "VERTEX".equalsIgnoreCase(req.targetKind().trim());
        if (vertexTarget && (req.keyTarget() == null || req.keyTarget().isBlank())) {
            errors.add("A graph vertex mapping must set a key target — it becomes the vertex externalId");
        }
        // Edge (relationship) structural checks — graph-only.
        List<EdgeMapping> edges = req.edges() == null ? List.of() : req.edges();
        if (!edges.isEmpty() && !vertexTarget) {
            errors.add("Relationships can only be defined on a graph vertex mapping");
        }
        for (EdgeMapping e : edges) {
            if (blank(e.edgeType()) || !IDENT.matcher(e.edgeType().trim()).matches()) {
                errors.add("Relationship type '" + e.edgeType() + "' is not a valid name "
                        + "(letters, digits, underscore; e.g. HAS_CLIENT)");
            }
            if (blank(e.targetVertexType()) || !IDENT.matcher(e.targetVertexType().trim()).matches()) {
                errors.add("Relationship '" + e.edgeType() + "' has an invalid target vertex type '"
                        + e.targetVertexType() + "'");
            }
            if (!blank(e.direction()) && !"OUT".equalsIgnoreCase(e.direction()) && !"IN".equalsIgnoreCase(e.direction())) {
                errors.add("Relationship '" + e.edgeType() + "' has an unknown direction '"
                        + e.direction() + "' — expected OUT or IN");
            }
        }

        // --- source-side checks against discovered metadata (req 3.5) ------
        TableInfo table = findTable(req);
        if (table == null) {
            errors.add("Source table '" + qualified(req) + "' was not found in the data source's schema");
            return report(errors, warnings);
        }
        Map<String, ColumnInfo> sourceCols = table.columns().stream()
                .collect(Collectors.toMap(c -> c.name().toLowerCase(Locale.ROOT), Function.identity(),
                        (a, b) -> a));
        for (ColumnMapping m : req.mappings()) {
            TargetType targetType = targetTypeOf(m, new ArrayList<>());
            boolean manyToOne = m.sources().size() > 1;
            for (String src : m.sources()) {
                ColumnInfo col = sourceCols.get(src.toLowerCase(Locale.ROOT));
                if (col == null) {
                    errors.add("Source column '" + src + "' (target '" + m.target()
                            + "') does not exist in " + qualified(req));
                    continue;
                }
                if (targetType != null && !manyToOne) {
                    compatibilityWarning(col, targetType, m.target(), warnings);
                }
            }
            if (manyToOne && targetType != null && targetType != TargetType.STRING) {
                warnings.add("Target '" + m.target() + "' combines " + m.sources().size()
                        + " source columns but is typed " + targetType
                        + " — combined values usually only convert cleanly to STRING");
            }
        }
        // Each relationship's key column must exist in the source table — its
        // value is the related vertex's externalId, read straight from the row.
        for (EdgeMapping e : edges) {
            if (!blank(e.sourceKeyColumn()) && !sourceCols.containsKey(e.sourceKeyColumn().toLowerCase(Locale.ROOT))) {
                errors.add("Relationship '" + e.edgeType() + "' references source column '"
                        + e.sourceKeyColumn() + "', which does not exist in " + qualified(req));
            }
        }
        return report(errors, warnings);
    }

    /** Requirement 3.5 — warn on conversions that are likely lossy or failure-prone. */
    private static void compatibilityWarning(ColumnInfo col, TargetType target,
                                             String targetName, List<String> warnings) {
        Family source = family(col.jdbcType());
        boolean fine = switch (target) {
            case STRING -> true;                                   // everything prints
            case INTEGER -> source == Family.INTEGER;
            case NUMBER -> source == Family.INTEGER || source == Family.DECIMAL;
            case BOOLEAN -> source == Family.BOOLEAN || source == Family.INTEGER;
            case DATE -> source == Family.DATETIME || source == Family.CHAR;
        };
        if (!fine) {
            warnings.add("Possible incompatible mapping: source column '" + col.name() + "' ("
                    + col.typeName() + ") → target '" + targetName + "' (" + target
                    + ") — values that fail to convert will be dropped to null at load time");
        }
    }

    private enum Family { INTEGER, DECIMAL, CHAR, DATETIME, BOOLEAN, OTHER }

    private static Family family(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> Family.INTEGER;
            case Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.REAL, Types.DOUBLE -> Family.DECIMAL;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR,
                 Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB -> Family.CHAR;
            case Types.DATE, Types.TIME, Types.TIMESTAMP,
                 Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> Family.DATETIME;
            case Types.BOOLEAN, Types.BIT -> Family.BOOLEAN;
            default -> Family.OTHER;
        };
    }

    private TableInfo findTable(SaveMappingRequest req) {
        SchemaSnapshot snap = metadata.getSnapshot(req.dataSourceId(), false);
        return snap.tables().stream()
                .filter(t -> t.name().equalsIgnoreCase(req.sourceTable()))
                .filter(t -> req.sourceSchema() == null || req.sourceSchema().isBlank()
                        || t.schema().equalsIgnoreCase(req.sourceSchema()))
                .findFirst().orElse(null);
    }

    static TargetType targetTypeOf(ColumnMapping m, List<String> errors) {
        String raw = m.targetType();
        if (raw == null || raw.isBlank()) return TargetType.STRING;
        try {
            return TargetType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("Unknown target type '" + raw + "' on target '" + m.target()
                    + "' — expected STRING, INTEGER, NUMBER, BOOLEAN or DATE");
            return null;
        }
    }

    private static String qualified(SaveMappingRequest req) {
        return (req.sourceSchema() == null || req.sourceSchema().isBlank() ? "" : req.sourceSchema() + ".")
                + req.sourceTable();
    }

    private static ValidationReport report(List<String> errors, List<String> warnings) {
        return new ValidationReport(errors.isEmpty(), errors, warnings);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
