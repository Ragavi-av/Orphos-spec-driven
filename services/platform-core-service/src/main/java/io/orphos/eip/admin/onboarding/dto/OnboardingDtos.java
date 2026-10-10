package io.orphos.eip.admin.onboarding.dto;

import java.util.List;

/**
 * DTOs for the data-onboarding pipeline (Administration → Data &amp; AI → upload).
 *
 * <p>The {@code analyze} endpoint returns an {@link AnalyzeResult} the admin
 * reviews/edits; the (possibly edited) {@link CommitRequest} is then sent to
 * {@code commit} to provision the ontology, ArcadeDB data, and widgets.</p>
 */
public final class OnboardingDtos {

    private OnboardingDtos() {}

    /** Inferred data type of a column. */
    public enum ColumnType { INTEGER, NUMBER, DATE, BOOLEAN, STRING }

    /** Semantic role used to drive ontology + widget generation. */
    public enum ColumnRole { ID, MEASURE, CATEGORY, DATE, TEXT }

    public record ColumnInfo(
            String name,          // original header
            String property,      // sanitised property name used in ArcadeDB/OWL
            ColumnType type,
            ColumnRole role,
            int distinctCount,
            List<String> samples  // a few example values (no full data)
    ) {}

    public record EntityModel(
            String className,     // PascalCase class/vertex-type name
            String label,         // human label (original sheet/file name)
            String sourceFile,
            String sourceSheet,
            int rowCount,
            String idProperty,    // chosen key column property, or null
            List<ColumnInfo> columns
    ) {}

    /** A proposed object property (relationship) between two entities. */
    public record RelationshipModel(
            String name,          // e.g. worksInDepartment
            String fromClass,
            String toClass,
            String viaProperty    // the column on fromClass that references toClass
    ) {}

    public enum WidgetType { COUNT, SUM, AVG, BREAKDOWN, TREND }

    public record WidgetProposal(
            String id,            // stable widget id (tenant-scoped)
            String label,
            WidgetType type,
            String entity,        // className
            String column,        // measure/category column property (null for COUNT)
            String groupBy,       // category column for BREAKDOWN (else null)
            String dateColumn,    // date column for TREND (else null)
            String color,
            boolean defaultVisible
    ) {}

    public record AnalyzeResult(
            String uploadToken,   // opaque handle to the cached parsed data, replayed on commit
            List<EntityModel> entities,
            List<RelationshipModel> relationships,
            List<WidgetProposal> widgets,
            String ontologyOwl,   // generated OWL/RDF-XML preview
            List<String> warnings
    ) {}

    /** Echoed back (optionally edited) by the admin to commit the onboarding. */
    public record CommitRequest(
            List<EntityModel> entities,
            List<RelationshipModel> relationships,
            List<WidgetProposal> widgets,
            String ontologyOwl,
            // analyze returns an opaque uploadToken; commit replays the cached
            // parsed data so the admin doesn't have to re-upload the files.
            String uploadToken
    ) {}

    public record CommitResult(
            int entitiesCreated,
            int rowsLoaded,
            int widgetsCreated,
            boolean ontologyLoaded,
            List<String> warnings
    ) {}
}
