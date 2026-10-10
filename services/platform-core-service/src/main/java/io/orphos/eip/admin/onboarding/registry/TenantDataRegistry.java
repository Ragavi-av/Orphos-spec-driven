package io.orphos.eip.admin.onboarding.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.EntityModel;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.WidgetProposal;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-tenant registry of the onboarded data model: the inferred entities and the
 * widget descriptors, stored as JSON blobs in one {@code TenantDataModel}
 * document per tenant in ArcadeDB (orphos). Drives the dynamic dashboard
 * widgets and the generic co-pilot grounding.
 */
@Component
public class TenantDataRegistry {

    private static final Logger log = LogManager.getLogger(TenantDataRegistry.class);
    private static final String TYPE = "TenantDataModel";

    private final ArcadeDbTemplate arcade;
    private final ObjectMapper mapper = new ObjectMapper();

    public TenantDataRegistry(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    @PostConstruct
    public void ensureSchema() {
        try {
            arcade.commandNoResult("CREATE DOCUMENT TYPE " + TYPE + " IF NOT EXISTS", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".tenantId IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".entitiesJson IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".widgetsJson IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".updatedAt IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId) UNIQUE", Map.of());
            log.info("TenantDataModel schema ready on ArcadeDB");
        } catch (Exception e) {
            log.warn("Could not initialise TenantDataModel schema (will retry per-call): {}", e.getMessage());
        }
    }

    /**
     * Merge the committed entities/widgets into the tenant's model: entities (and
     * their widgets) whose className is in this commit are replaced; everything
     * else is kept. So onboarding additional files ADDS entities instead of
     * wiping the ones onboarded earlier.
     */
    public void save(UUID tenantId, List<EntityModel> entities, List<WidgetProposal> widgets) {
        try {
            java.util.Set<String> incoming = entities.stream()
                    .map(EntityModel::className).collect(java.util.stream.Collectors.toSet());

            List<EntityModel> mergedEntities = new java.util.ArrayList<>(
                    listEntities(tenantId).stream().filter(e -> !incoming.contains(e.className())).toList());
            mergedEntities.addAll(entities);

            List<WidgetProposal> mergedWidgets = new java.util.ArrayList<>(
                    listWidgets(tenantId).stream().filter(w -> !incoming.contains(w.entity())).toList());
            mergedWidgets.addAll(widgets);

            writeModel(tenantId, mergedEntities, mergedWidgets);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist tenant data model: " + e.getMessage(), e);
        }
    }

    /**
     * Replace just the tenant's widget set, leaving the onboarded entities
     * untouched. Backs the admin "manage widgets" screen (add / edit / delete)
     * so an operator can curate widgets without re-uploading the source files.
     */
    public void saveWidgets(UUID tenantId, List<WidgetProposal> widgets) {
        try {
            writeModel(tenantId, listEntities(tenantId), widgets == null ? List.of() : widgets);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist tenant widgets: " + e.getMessage(), e);
        }
    }

    /**
     * Add a new widget or replace an existing one (matched by id). Returns true
     * if it was newly created, false if it replaced an existing widget.
     */
    public boolean upsertWidget(UUID tenantId, WidgetProposal widget) {
        List<WidgetProposal> widgets = new java.util.ArrayList<>(listWidgets(tenantId));
        int idx = -1;
        for (int i = 0; i < widgets.size(); i++) {
            if (widgets.get(i).id().equals(widget.id())) { idx = i; break; }
        }
        boolean isNew = idx < 0;
        if (isNew) widgets.add(widget); else widgets.set(idx, widget);
        saveWidgets(tenantId, widgets);
        return isNew;
    }

    /** Remove a widget by id. Returns true if one was removed. */
    public boolean deleteWidget(UUID tenantId, String widgetId) {
        List<WidgetProposal> widgets = new java.util.ArrayList<>(listWidgets(tenantId));
        boolean removed = widgets.removeIf(w -> w.id().equals(widgetId));
        if (removed) saveWidgets(tenantId, widgets);
        return removed;
    }

    /** Persist the full model (entities + widgets) for a tenant, replacing any prior row. */
    private void writeModel(UUID tenantId, List<EntityModel> entities, List<WidgetProposal> widgets)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        String ej = mapper.writeValueAsString(entities);
        String wj = mapper.writeValueAsString(widgets);
        arcade.command("DELETE FROM " + TYPE + " WHERE tenantId = :t",
                Map.of("t", tenantId.toString()));
        arcade.command("INSERT INTO " + TYPE + " SET tenantId = :t, entitiesJson = :e, "
                + "widgetsJson = :w, updatedAt = :u",
                Map.of("t", tenantId.toString(), "e", ej, "w", wj, "u", Instant.now().toString()));
    }

    public List<EntityModel> listEntities(UUID tenantId) {
        return load(tenantId).map(r -> readList(r.get("entitiesJson"), EntityModel[].class)).orElse(List.of());
    }

    public List<WidgetProposal> listWidgets(UUID tenantId) {
        return load(tenantId).map(r -> readList(r.get("widgetsJson"), WidgetProposal[].class)).orElse(List.of());
    }

    public Optional<WidgetProposal> widget(UUID tenantId, String widgetId) {
        return listWidgets(tenantId).stream().filter(w -> w.id().equals(widgetId)).findFirst();
    }

    public boolean hasModel(UUID tenantId) {
        return load(tenantId).isPresent();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Optional<Map<String, Object>> load(UUID tenantId) {
        try {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT entitiesJson, widgetsJson FROM " + TYPE + " WHERE tenantId = :t LIMIT 1",
                    Map.of("t", tenantId.toString()));
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (Exception e) {
            log.warn("TenantDataModel read failed for {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private <T> List<T> readList(Object json, Class<T[]> arrayType) {
        if (json == null) return List.of();
        try {
            return List.of(mapper.readValue(json.toString(), arrayType));
        } catch (Exception e) {
            log.warn("Failed to parse registry JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
