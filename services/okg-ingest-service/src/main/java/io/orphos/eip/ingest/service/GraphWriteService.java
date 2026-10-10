package io.orphos.eip.ingest.service;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeConnectionResolver;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.ingest.component.EnterpriseSchemaBootstrap;
import io.orphos.eip.ingest.repository.EnterpriseGraphRepository;
import io.orphos.eip.ingest.vo.GraphWriteDtos.EdgeLink;
import io.orphos.eip.ingest.vo.GraphWriteDtos.UpsertEdgesRequest;
import io.orphos.eip.ingest.vo.GraphWriteDtos.UpsertEdgesResponse;
import io.orphos.eip.ingest.vo.GraphWriteDtos.UpsertVerticesRequest;
import io.orphos.eip.ingest.vo.GraphWriteDtos.UpsertVerticesResponse;
import io.orphos.eip.ingest.vo.GraphWriteDtos.VertexRow;
import io.orphos.eip.ingest.service.TenantInfoCache;
import io.orphos.eip.ingest.vo.TenantRoutingInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Generic graph write surface: upserts arbitrary vertices/edges for the current
 * tenant, reusing {@link EnterpriseGraphRepository}'s {@code (tenantId,
 * externalId)} upsert convention. Where {@code EnterpriseGraphService} writes one
 * fixed enterprise profile, this writes any type by name — so a data-ingestion
 * mapping can target an existing {@code Ent*} vertex or define a brand-new type.
 *
 * <p>Type names are validated as plain identifiers before being concatenated into
 * DDL. New types are created on demand ({@code ensureType}) mirroring
 * {@code EnterpriseSchemaBootstrap}: the shared identity properties plus the
 * {@code (tenantId, externalId)} UNIQUE index the upsert relies on.</p>
 */
@Service
public class GraphWriteService {

    private static final Logger log = LogManager.getLogger(GraphWriteService.class);
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    /** Marks connector-loaded rows, distinct from the enterprise-profile "ADMIN" source. */
    public static final String SOURCE = "INGEST";

    private final EnterpriseGraphRepository graph;
    private final TenantInfoCache tenantInfo;
    private final ArcadeConnectionResolver resolver;

    public GraphWriteService(EnterpriseGraphRepository graph, TenantInfoCache tenantInfo,
                             ArcadeConnectionResolver resolver) {
        this.graph = graph;
        this.tenantInfo = tenantInfo;
        this.resolver = resolver;
    }

    public UpsertVerticesResponse upsertVertices(UpsertVerticesRequest req) {
        String type = requireIdent(req.vertexType(), "vertex type");
        Routed r = route();
        List<String> warnings = new ArrayList<>();

        if (req.ensureType()) ensureVertexType(r.arcade(), r.db(), type);

        int written = 0;
        List<VertexRow> rows = req.rows() == null ? List.of() : req.rows();
        for (VertexRow row : rows) {
            if (row.externalId() == null || row.externalId().isBlank()) {
                warnings.add("Skipped a row with a blank externalId");
                continue;
            }
            try {
                graph.upsertVertex(r.arcade(), r.db(), r.tenantId(), type, SOURCE,
                        row.externalId(), row.properties() == null ? Map.of() : row.properties());
                written++;
            } catch (Exception e) {
                warnings.add("Row '" + row.externalId() + "' failed: " + e.getMessage());
                log.debug("Vertex upsert failed for {} '{}': {}", type, row.externalId(), e.getMessage());
            }
        }
        log.info("Graph upsert: {} of {} {} vertices written for tenant {}",
                written, rows.size(), type, r.tenantId());
        return new UpsertVerticesResponse("SUCCESS", type, written, warnings);
    }

    public UpsertEdgesResponse upsertEdges(UpsertEdgesRequest req) {
        String edgeType = requireIdent(req.edgeType(), "edge type");
        String fromType = requireIdent(req.fromType(), "from vertex type");
        String toType = requireIdent(req.toType(), "to vertex type");
        Routed r = route();
        List<String> warnings = new ArrayList<>();

        if (req.ensureType()) ensureEdgeType(r.arcade(), r.db(), edgeType);

        int written = 0;
        List<EdgeLink> links = req.links() == null ? List.of() : req.links();
        for (EdgeLink link : links) {
            try {
                boolean created = graph.linkOnce(r.arcade(), r.db(), r.tenantId(), edgeType,
                        fromType, link.fromExternalId(), toType, link.toExternalId());
                if (created) written++;
                else warnings.add("Edge " + link.fromExternalId() + " → " + link.toExternalId()
                        + " not created (already linked or endpoint missing)");
            } catch (Exception e) {
                warnings.add("Edge " + link.fromExternalId() + " → " + link.toExternalId()
                        + " failed: " + e.getMessage());
            }
        }
        return new UpsertEdgesResponse("SUCCESS", edgeType, written, warnings);
    }

    /** Vertex type names in the current tenant's database (for the mapping-UI target picker). */
    public List<String> listVertexTypes() {
        Routed r = route();
        List<String> types = new ArrayList<>();
        try {
            for (Map<String, Object> row : r.arcade().queryOn(r.db(),
                    "SELECT name, type FROM schema:types", Map.of())) {
                if ("vertex".equalsIgnoreCase(String.valueOf(row.get("type")))) {
                    types.add(String.valueOf(row.get("name")));
                }
            }
        } catch (Exception e) {
            log.warn("Could not list vertex types: {}", e.getMessage());
        }
        types.sort(String::compareToIgnoreCase);
        return types;
    }

    // ── DDL (mirrors EnterpriseSchemaBootstrap) ───────────────────────────────

    private void ensureVertexType(ArcadeDbTemplate arcade, String db, String type) {
        arcade.commandNoResultOn(db, "CREATE VERTEX TYPE " + type + " IF NOT EXISTS", Map.of());
        for (String prop : List.of("id", "tenantId", "externalId", "source", "createdAt", "updatedAt", "name")) {
            tryExec(arcade, db, "CREATE PROPERTY " + type + "." + prop + " STRING");
        }
        arcade.commandNoResultOn(db, "CREATE INDEX IF NOT EXISTS ON " + type + " (id) UNIQUE", Map.of());
        arcade.commandNoResultOn(db, "CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId) NOTUNIQUE", Map.of());
        arcade.commandNoResultOn(db, "CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId, externalId) UNIQUE", Map.of());
    }

    private void ensureEdgeType(ArcadeDbTemplate arcade, String db, String type) {
        arcade.commandNoResultOn(db, "CREATE EDGE TYPE " + type + " IF NOT EXISTS", Map.of());
    }

    private void tryExec(ArcadeDbTemplate arcade, String db, String ddl) {
        try {
            arcade.commandNoResultOn(db, ddl, Map.of());
        } catch (Exception e) {
            // CREATE PROPERTY has no IF NOT EXISTS — duplicate-property errors are expected.
            log.debug("DDL skipped ({}): {}", e.getMessage(), ddl);
        }
    }

    // ── routing ────────────────────────────────────────────────────────────────

    /** The routed template + db + tenant for the current thread's tenant context. */
    private record Routed(ArcadeDbTemplate arcade, String db, UUID tenantId) {}

    private Routed route() {
        UUID tenantId = TenantContextHolder.requireTenantId();
        TenantRoutingInfo routing = tenantInfo.find(tenantId)
                .filter(TenantRoutingInfo::active)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot resolve routing (or tenant inactive) for " + tenantId + " — refusing to write"));
        return new Routed(resolver.templateFor(routing.arcadeConnectionId()), routing.dataset(), tenantId);
    }

    private static String requireIdent(String name, String what) {
        if (name == null || !IDENT.matcher(name.trim()).matches()) {
            throw new IllegalArgumentException("Invalid " + what + " name: '" + name + "'");
        }
        return name.trim();
    }
}
