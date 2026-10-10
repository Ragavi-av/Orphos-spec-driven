package io.orphos.eip.admin.onboarding.service;

import io.orphos.eip.core.arcadedb.ArcadeConnectionResolver;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.AnalyzeResult;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.CommitRequest;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.CommitResult;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.EntityModel;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.WidgetProposal;
import io.orphos.eip.admin.onboarding.infer.OntologyGenerator;
import io.orphos.eip.admin.onboarding.infer.SchemaInferencer;
import io.orphos.eip.admin.onboarding.infer.SchemaInferencer.InferenceModel;
import io.orphos.eip.admin.onboarding.parse.ParsedTable;
import io.orphos.eip.admin.onboarding.parse.TableParser;
import io.orphos.eip.admin.onboarding.registry.TenantDataRegistry;
import io.orphos.eip.admin.tenant.arcade.ArcadeProvisioningClient;
import io.orphos.eip.admin.tenant.fuseki.FusekiProvisioningClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates data onboarding. {@link #analyze} parses + infers + previews
 * (no writes) and caches the parsed tables under an opaque token; the commit
 * stage replays that cache so the admin never re-uploads the files.
 */
@Service
public class OnboardingService {

    private static final Logger log = LogManager.getLogger(OnboardingService.class);
    private static final long TTL_NANOS = 30L * 60 * 1_000_000_000L;   // 30 minutes

    private final TableParser parser;
    private final SchemaInferencer inferencer;
    private final OntologyGenerator ontology;
    private final ArcadeDataLoader loader;
    private final TenantDataRegistry registry;
    private final FusekiProvisioningClient fuseki;
    private final ArcadeProvisioningClient arcadeProvisioning;
    private final ArcadeDbTemplate arcade;
    private final ArcadeConnectionResolver resolver;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OnboardingService(TableParser parser, SchemaInferencer inferencer, OntologyGenerator ontology,
                             ArcadeDataLoader loader, TenantDataRegistry registry,
                             FusekiProvisioningClient fuseki, ArcadeProvisioningClient arcadeProvisioning,
                             ArcadeDbTemplate arcade, ArcadeConnectionResolver resolver) {
        this.parser = parser;
        this.inferencer = inferencer;
        this.ontology = ontology;
        this.loader = loader;
        this.registry = registry;
        this.fuseki = fuseki;
        this.arcadeProvisioning = arcadeProvisioning;
        this.arcade = arcade;
        this.resolver = resolver;
    }

    /**
     * The ArcadeDB connection a tenant's dedicated database lives on. Read from
     * the Tenant registry (always on the platform DB); null → the platform
     * ArcadeDB. Lets onboarding target a tenant created against an external
     * ArcadeDB connection.
     */
    private String connIdFor(UUID tenantId) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT arcadeConnectionId FROM Tenant WHERE id = :id LIMIT 1",
                    Map.of("id", tenantId.toString()));
            if (!rows.isEmpty()) {
                Object v = rows.get(0).get("arcadeConnectionId");
                String s = v == null ? null : v.toString();
                return (s == null || s.isBlank()) ? null : s;
            }
        } catch (Exception e) {
            log.debug("Could not read arcadeConnectionId for tenant {}: {}", tenantId, e.getMessage());
        }
        return null;
    }

    /** Template routed to the tenant's ArcadeDB server for its dedicated-database ops. */
    private ArcadeDbTemplate tenantArcade(UUID tenantId) {
        return resolver.templateFor(connIdFor(tenantId));
    }

    /** A tenant's own database name, derived from its UUID. */
    public static String dedicatedDb(UUID tenantId) {
        return "eip_tenant_" + tenantId.toString().replace("-", "");
    }

    /** Cached parsed upload, keyed by token, scoped to the tenant that uploaded it. */
    public record Cached(UUID tenantId, List<ParsedTable> tables, long createdNanos) {}

    public AnalyzeResult analyze(UUID tenantId, List<MultipartFile> files) throws IOException {
        evictExpired();
        List<ParsedTable> tables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            try {
                tables.addAll(parser.parse(f.getOriginalFilename(), f.getBytes()));
            } catch (Exception e) {
                warnings.add("Could not parse '" + f.getOriginalFilename() + "': " + e.getMessage());
            }
        }
        if (tables.isEmpty()) {
            return new AnalyzeResult(null, List.of(), List.of(), List.of(), "",
                    merge(warnings, "No readable tables found in the uploaded files."));
        }

        InferenceModel m = inferencer.infer(tables);
        String owl = ontology.generate(m.entities(), m.relationships());

        String token = UUID.randomUUID().toString();
        cache.put(token, new Cached(tenantId, tables, System.nanoTime()));
        log.info("Onboarding analyze: tenant={}, files={}, entities={}, widgets={}, token={}",
                tenantId, files.size(), m.entities().size(), m.widgets().size(), token);

        return new AnalyzeResult(token, m.entities(), m.relationships(), m.widgets(), owl,
                merge(warnings, m.warnings()));
    }

    /**
     * Commit a (possibly admin-edited) plan: load each entity's rows into ArcadeDB,
     * persist the registry, publish the ontology to Fuseki, and set the tenant's
     * default dashboard layout from the proposed widgets.
     */
    public CommitResult commit(UUID tenantId, CommitRequest req) {
        Cached c = cached(req.uploadToken(), tenantId);
        if (c == null) {
            throw new IllegalStateException("Upload session expired — please re-upload the files.");
        }
        List<String> warnings = new ArrayList<>();

        // Each onboarded tenant gets its OWN database + Fuseki dataset (DEDICATED).
        // ensureDatabase/ensureDataset are idempotent — created on first onboard,
        // reused (UPSERT into) on subsequent uploads.
        String db = dedicatedDb(tenantId);
        String connId = connIdFor(tenantId);
        ArcadeDbTemplate tenantDb = resolver.templateFor(connId);
        try {
            arcadeProvisioning.ensureDatabase(connId, db);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not provision database " + db + ": " + ex.getMessage(), ex);
        }
        try { fuseki.ensureDataset(db); } catch (Exception ex) {
            warnings.add("Fuseki dataset provisioning warning: " + ex.getMessage());
        }

        int rows = 0;
        for (EntityModel e : req.entities()) {
            ParsedTable table = c.tables().stream()
                    .filter(t -> Objects.equals(t.sourceFile(), e.sourceFile())
                              && Objects.equals(t.sheetName(), e.sourceSheet()))
                    .findFirst().orElse(null);
            if (table == null) { warnings.add("No data found for entity '" + e.className() + "'."); continue; }
            try {
                rows += loader.load(e, table, tenantId.toString(), db, tenantDb);   // UPSERT into the tenant's DB
            } catch (Exception ex) {
                warnings.add("Failed loading '" + e.className() + "': " + ex.getMessage());
            }
        }

        // Metadata stays in the admin DB (orphos), keyed by tenantId. save()
        // merges with any previously-onboarded entities; the dashboard default is
        // then set from the full merged widget set.
        registry.save(tenantId, req.entities(), req.widgets());
        setDashboardDefault(tenantId, registry.listWidgets(tenantId));
        // Ontology + tenant record point at the dedicated stores. Regenerate the
        // OWL from the (possibly admin-edited) entities so edits to types/roles/
        // columns are reflected — the echoed ontologyOwl preview from analyze may
        // be stale relative to the edited models.
        String owl;
        try {
            owl = ontology.generate(req.entities(), req.relationships());
        } catch (Exception ex) {
            owl = req.ontologyOwl();
            warnings.add("Ontology regeneration failed — publishing the analyze-time preview: " + ex.getMessage());
        }
        boolean ontologyLoaded = fuseki.uploadRdfXml(db, owl);
        markDedicated(tenantId, db);
        invalidate(req.uploadToken());

        log.info("Onboarding commit: tenant={}, db={}, entities={}, rows={}, widgets={}, ontology={}",
                tenantId, db, req.entities().size(), rows, req.widgets().size(), ontologyLoaded);
        return new CommitResult(req.entities().size(), rows, req.widgets().size(), ontologyLoaded, warnings);
    }

    /** Point the tenant record at its dedicated database so the dashboard + co-pilot resolve it. */
    private void markDedicated(UUID tenantId, String db) {
        try {
            arcade.command("UPDATE Tenant SET isolationMode = 'DEDICATED', arcadeDatabase = :db, updatedAt = :u "
                    + "WHERE id = :id",
                    Map.of("db", db, "u", Instant.now().toString(), "id", tenantId.toString()));
        } catch (Exception e) {
            log.warn("Could not mark tenant {} DEDICATED: {}", tenantId, e.getMessage());
        }
    }

    /** Current onboarded state for the UI: entities (with live row counts) + widgets. */
    public Map<String, Object> currentState(UUID tenantId) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        List<EntityModel> entities = registry.listEntities(tenantId);
        String db = dedicatedDb(tenantId);
        ArcadeDbTemplate tenantDb = tenantArcade(tenantId);
        List<Map<String, Object>> entityViews = new ArrayList<>();
        for (EntityModel e : entities) {
            long count = 0;
            try {
                List<Map<String, Object>> r = tenantDb.queryOn(db,
                        "SELECT count(*) AS n FROM " + e.className() + " WHERE tenantId = :t",
                        Map.of("t", tenantId.toString()));
                if (!r.isEmpty() && r.get(0).get("n") instanceof Number n) count = n.longValue();
            } catch (Exception ignored) { /* type may not exist yet */ }
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("className", e.className());
            v.put("label", e.label());
            v.put("rowCount", count);
            v.put("columns", e.columns().size());
            entityViews.add(v);
        }
        out.put("database", db);
        out.put("entities", entityViews);
        // Full entity models (with columns) so the admin "manage widgets" UI can
        // drive its entity/column pickers without re-uploading the source files.
        out.put("entityModels", entities);
        out.put("widgets", registry.listWidgets(tenantId));
        out.put("onboarded", !entities.isEmpty());
        return out;
    }

    /** The tenant's committed widgets (for the manage-widgets admin screen). */
    public List<WidgetProposal> listWidgets(UUID tenantId) {
        return registry.listWidgets(tenantId);
    }

    /**
     * Add or update a single committed widget (admin manage screen). A blank id
     * means "create" — an id is generated from the widget's shape. The widget is
     * validated against the tenant's onboarded entities/columns so it can never
     * reference a column that no longer exists. Layout stores self-heal: the
     * widgets-service sanitiser drops unknown ids and adds newly-known ones, so
     * no dashboard-default rewrite is needed here.
     */
    public WidgetProposal saveWidget(UUID tenantId, WidgetProposal widget) {
        validateWidget(tenantId, widget);
        WidgetProposal w = widget;
        if (w.id() == null || w.id().isBlank()) {
            String prop = firstNonBlank(w.column(), w.groupBy(), w.dateColumn(), "all");
            String base = (w.type() == null ? "count" : w.type().name().toLowerCase())
                    + "-" + w.entity().toLowerCase() + "-" + prop + "-"
                    + UUID.randomUUID().toString().substring(0, 6);
            w = new WidgetProposal(base, w.label(), w.type(), w.entity(), w.column(),
                    w.groupBy(), w.dateColumn(), w.color(), w.defaultVisible());
        }
        registry.upsertWidget(tenantId, w);
        log.info("Widget upsert: tenant={}, id={}, type={}, entity={}", tenantId, w.id(), w.type(), w.entity());
        return w;
    }

    /** Delete a committed widget by id. Returns true if one was removed. */
    public boolean deleteWidget(UUID tenantId, String widgetId) {
        boolean removed = registry.deleteWidget(tenantId, widgetId);
        if (removed) log.info("Widget deleted: tenant={}, id={}", tenantId, widgetId);
        return removed;
    }

    /** Reject widgets that reference an entity/column the tenant has not onboarded. */
    private void validateWidget(UUID tenantId, WidgetProposal w) {
        if (w == null) throw new IllegalArgumentException("Widget is required.");
        if (w.label() == null || w.label().isBlank()) throw new IllegalArgumentException("Widget name is required.");
        if (w.type() == null) throw new IllegalArgumentException("Widget formula (type) is required.");
        EntityModel entity = registry.listEntities(tenantId).stream()
                .filter(e -> e.className().equals(w.entity())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity '" + w.entity() + "'."));
        java.util.Set<String> props = entity.columns().stream()
                .map(c -> c.property()).collect(java.util.stream.Collectors.toSet());
        for (String p : new String[]{w.column(), w.groupBy(), w.dateColumn()}) {
            if (p != null && !p.isBlank() && !props.contains(p)) {
                throw new IllegalArgumentException("Column '" + p + "' is not part of entity '" + w.entity() + "'.");
            }
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "all";
    }

    /** Make the proposed default-visible widgets the tenant's dashboard default. */
    private void setDashboardDefault(UUID tenantId, List<WidgetProposal> widgets) {
        try {
            List<String> order = widgets.stream().map(WidgetProposal::id).toList();
            List<String> visible = widgets.stream().filter(WidgetProposal::defaultVisible)
                    .map(WidgetProposal::id).toList();
            if (visible.isEmpty() && !order.isEmpty()) visible = order.subList(0, Math.min(4, order.size()));
            arcade.command("DELETE FROM DashboardLayoutDefault WHERE tenantId = :t",
                    Map.of("t", tenantId.toString()));
            arcade.command("INSERT INTO DashboardLayoutDefault SET tenantId = :t, visibleIds = :v, "
                    + "widgetOrder = :o, updatedAt = :u, updatedBy = 'onboarding'",
                    Map.of("t", tenantId.toString(), "v", visible, "o", order, "u", Instant.now().toString()));
        } catch (Exception e) {
            log.warn("Could not set dashboard default for {}: {}", tenantId, e.getMessage());
        }
    }

    /** Retrieve cached parsed tables for a token, validating tenant ownership. */
    public Cached cached(String token, UUID tenantId) {
        Cached c = token == null ? null : cache.get(token);
        if (c == null || !c.tenantId().equals(tenantId)) return null;
        if (System.nanoTime() - c.createdNanos() > TTL_NANOS) { cache.remove(token); return null; }
        return c;
    }

    public void invalidate(String token) {
        if (token != null) cache.remove(token);
    }

    private void evictExpired() {
        long now = System.nanoTime();
        cache.entrySet().removeIf(e -> now - e.getValue().createdNanos() > TTL_NANOS);
    }

    private static List<String> merge(List<String> base, Object more) {
        List<String> out = new ArrayList<>(base);
        if (more instanceof List<?> l) l.forEach(x -> out.add(String.valueOf(x)));
        else if (more != null) out.add(String.valueOf(more));
        return out;
    }
}
