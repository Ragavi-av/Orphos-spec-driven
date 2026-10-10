package io.orphos.eip.dataingestion.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Request/response shapes for data-mapping configuration (requirement 3). */
public final class MappingDtos {

    private MappingDtos() {}

    /** Target-side value types understood by the loader. */
    public enum TargetType { STRING, INTEGER, NUMBER, BOOLEAN, DATE }

    /**
     * Where mapped rows land: a flat ArcadeDB {@code DOCUMENT} type (the original
     * behaviour) or an ontology graph {@code VERTEX} (delegated to
     * okg-ingest-service). For VERTEX the {@code keyTarget} property is the
     * vertex's {@code externalId} within the {@code (tenantId, externalId)} key.
     */
    public enum TargetKind { DOCUMENT, VERTEX }

    /**
     * One target property fed by one or more source columns.
     * One source = one-to-one (req 3.2); several sources = many-to-one,
     * combined by the transform — CONCAT by default (req 3.3).
     * {@code transform} is a built-in function name, optionally with an
     * argument after {@code :} (e.g. {@code CONCAT: }, {@code DATE_PATTERN:yyyyMMdd}) — req 3.6.
     */
    public record ColumnMapping(
            @NotBlank String target,
            @NotEmpty List<String> sources,
            String targetType,     // TargetType name; STRING when blank
            String transform) {}   // optional; identity when blank

    /** Edge direction relative to the mapping's own (source-table) vertex. */
    public enum EdgeDirection { OUT, IN }

    /**
     * A relationship emitted per source row (VERTEX targets only): links this
     * mapping's vertex to another vertex whose {@code externalId} is carried in
     * {@code sourceKeyColumn} of the same row. {@code OUT} = this → other,
     * {@code IN} = other → this. The other endpoint must be loaded (by this or a
     * prior job) for the edge to be created.
     */
    public record EdgeMapping(
            @NotBlank String edgeType,          // SCREAMING_SNAKE, e.g. HAS_CLIENT
            @NotBlank String targetVertexType,  // the other endpoint's vertex type
            @NotBlank String sourceKeyColumn,   // source column holding the other vertex's externalId
            String direction) {}                // EdgeDirection name; OUT when blank

    public record SaveMappingRequest(
            @NotBlank String name,
            @NotBlank String dataSourceId,
            String sourceSchema,
            @NotBlank String sourceTable,
            @NotBlank String targetClass,          // ArcadeDB document type OR graph vertex type to load into
            String targetKind,                     // TargetKind name; DOCUMENT when blank
            String keyTarget,                      // upsert-key target property (= externalId for VERTEX)
            String incrementalColumn,              // source timestamp/sequence column (requirement 6.2)
            @NotEmpty List<ColumnMapping> mappings,
            List<EdgeMapping> edges,               // relationships to emit (VERTEX targets only); may be empty/null
            boolean enabled) {}

    /** Requirement 3.5/3.7 — surfaced on save AND from the dry-run validate endpoint. */
    public record ValidationReport(
            boolean valid,
            List<String> errors,      // block the save
            List<String> warnings) {} // stored with the mapping, shown in the UI

    public record MappingView(
            String id,
            String name,
            String dataSourceId,
            String sourceSchema,
            String sourceTable,
            String targetClass,
            String targetKind,
            String keyTarget,
            String incrementalColumn,
            List<ColumnMapping> mappings,
            List<EdgeMapping> edges,
            boolean enabled,
            List<String> warnings,
            String createdAt,
            String updatedAt) {}

    public record ListResponse(List<MappingView> mappings) {}
}
