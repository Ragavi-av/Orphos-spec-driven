package io.orphos.eip.ingest.vo;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * Request/response shapes for the generic graph write API — the surface the
 * data-ingestion connector uses to land mapped rows as ontology vertices/edges,
 * supporting both the predefined {@code Ent*} types and admin-defined new types.
 */
public final class GraphWriteDtos {

    private GraphWriteDtos() {}

    public record VertexRow(
            @NotBlank String externalId,      // natural key within (tenantId, externalId)
            Map<String, Object> properties) {}

    public record UpsertVerticesRequest(
            @NotBlank String vertexType,      // existing (EntClient…) or a new type name
            boolean ensureType,               // create the type + indexes if absent ("define new")
            List<VertexRow> rows) {}

    public record UpsertVerticesResponse(
            String status,
            String vertexType,
            int written,
            List<String> warnings) {}

    public record EdgeLink(
            @NotBlank String fromExternalId,
            @NotBlank String toExternalId) {}

    public record UpsertEdgesRequest(
            @NotBlank String edgeType,
            @NotBlank String fromType,
            @NotBlank String toType,
            boolean ensureType,
            List<EdgeLink> links) {}

    public record UpsertEdgesResponse(
            String status,
            String edgeType,
            int written,
            List<String> warnings) {}

    public record VertexTypesResponse(List<String> vertexTypes) {}
}
