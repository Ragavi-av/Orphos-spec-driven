package io.orphos.eip.nlquery.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.util.CurrentUser;
import io.orphos.eip.nlquery.component.PromptConfigStore;
import io.orphos.eip.nlquery.component.PromptConfigStore.PromptRow;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped admin CRUD for the editable LLM prompts &amp; tuning shown in
 * Administration → Data &amp; AI → LLM Prompts.
 *
 * <p>Every prompt is identified by a stable {@code promptKey} and versioned:
 * editing creates a new active version (history is retained and an older
 * version can be re-activated for rollback). Mirrors the auth + tenant
 * resolution of {@code TenantProviderAdminController}: {@code ROLE_admin} only,
 * tenant from the JWT-bound {@link TenantContextHolder} (platform admins acting
 * via {@code X-Tenant-Id} are honoured by the resolution filter).</p>
 */
// ingestion_admin is included so the scoped Document Ingestion admin can edit
// the doc-processing prompt kind from its Document AI tab.
@RestController
@RequestMapping("/api/nl-query/admin/prompts")
@PreAuthorize("hasAnyRole('admin','platform_admin','data_ai_admin','ingestion_admin')")
public class PromptAdminController {

    private final PromptConfigStore store;

    public PromptAdminController(PromptConfigStore store) {
        this.store = store;
    }

    /** All active prompts for the tenant (the UI groups them by kind). */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        UUID tenant = TenantContextHolder.requireTenantId();
        List<Map<String, Object>> rows = store.listActive(tenant).stream().map(this::toView).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenant.toString());
        body.put("prompts", rows);
        return ResponseEntity.ok(body);
    }

    /** Add a brand-new prompt fragment (must use a fresh promptKey). */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        UUID tenant = TenantContextHolder.requireTenantId();
        String promptKey = asString(body.get("promptKey"));
        String kind      = asString(body.get("kind"));
        if (isBlank(promptKey) || isBlank(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", "promptKey and kind are required."));
        }
        boolean exists = store.listActive(tenant).stream().anyMatch(r -> promptKey.equals(r.promptKey()));
        if (exists) {
            return ResponseEntity.badRequest().body(Map.of("error", "A prompt with key '" + promptKey + "' already exists."));
        }
        PromptRow saved = store.saveNewVersion(tenant, kind, promptKey,
                asString(body.get("name")), asString(body.get("content")),
                asString(body.get("description")), asInt(body.get("orderIndex"), 0), editor());
        return ResponseEntity.ok(toView(saved));
    }

    /** Edit a prompt — creates a new active version, preserving missing fields. */
    @PutMapping("/{promptKey}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String promptKey,
            @RequestBody Map<String, Object> body) {
        UUID tenant = TenantContextHolder.requireTenantId();

        // Carry kind / name / orderIndex from the current active row (or the
        // bundled default) when the request omits them.
        PromptRow current = store.listActive(tenant).stream()
                .filter(r -> promptKey.equals(r.promptKey())).findFirst().orElse(null);

        String kind  = firstNonBlank(asString(body.get("kind")),  current != null ? current.kind() : null);
        String name  = body.containsKey("name") ? asString(body.get("name"))
                                                 : (current != null ? current.name() : null);
        int orderIndex = body.containsKey("orderIndex") ? asInt(body.get("orderIndex"), 0)
                                                        : (current != null ? current.orderIndex() : 0);
        String description = body.containsKey("description") ? asString(body.get("description"))
                                                            : (current != null ? current.description() : null);
        String content = asString(body.get("content"));

        if (isBlank(kind)) {
            return ResponseEntity.status(404).body(Map.of("error", "Unknown prompt: " + promptKey));
        }
        PromptRow saved = store.saveNewVersion(tenant, kind, promptKey, name, content, description, orderIndex, editor());
        return ResponseEntity.ok(toView(saved));
    }

    /** Full version history for a key, newest first. */
    @GetMapping("/{promptKey}/versions")
    public ResponseEntity<Map<String, Object>> versions(@PathVariable String promptKey) {
        UUID tenant = TenantContextHolder.requireTenantId();
        List<Map<String, Object>> rows = store.history(tenant, promptKey).stream().map(this::toView).toList();
        return ResponseEntity.ok(Map.of("promptKey", promptKey, "versions", rows));
    }

    /** Roll back / forward to an existing version. */
    @PostMapping("/{promptKey}/activate/{version}")
    public ResponseEntity<Map<String, Object>> activate(
            @PathVariable String promptKey, @PathVariable int version) {
        UUID tenant = TenantContextHolder.requireTenantId();
        store.activateVersion(tenant, promptKey, version);
        return ResponseEntity.ok(Map.of("promptKey", promptKey, "activeVersion", version));
    }

    /** Delete every version of a fragment. */
    @DeleteMapping("/{promptKey}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String promptKey) {
        UUID tenant = TenantContextHolder.requireTenantId();
        store.deleteKey(tenant, promptKey);
        return ResponseEntity.ok(Map.of("deleted", promptKey));
    }

    /** Bulk-restore all bundled-default prompts to defaults (optionally one kind). */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetAll(@RequestParam(required = false) String kind) {
        UUID tenant = TenantContextHolder.requireTenantId();
        String scope = (kind == null || kind.isBlank()) ? null : kind;
        int n = store.resetAll(tenant, scope, editor());
        return ResponseEntity.ok(Map.of("reset", n, "kind", scope == null ? "all" : scope));
    }

    /** Restore a fragment to its bundled default (new active version). */
    @PostMapping("/{promptKey}/reset")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable String promptKey) {
        UUID tenant = TenantContextHolder.requireTenantId();
        PromptRow restored = store.resetToDefault(tenant, promptKey, editor());
        if (restored == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No bundled default exists for '" + promptKey + "'."));
        }
        return ResponseEntity.ok(toView(restored));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toView(PromptRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promptKey",   r.promptKey());
        m.put("kind",        r.kind());
        m.put("name",        r.name());
        m.put("content",     r.content());
        m.put("description", r.description());
        m.put("orderIndex",  r.orderIndex());
        m.put("version",     r.version());
        m.put("active",      r.active());
        m.put("updatedAt",   r.updatedAt());
        m.put("createdBy",   r.createdBy());
        m.put("isDefault",   store.isDefaultKey(r.promptKey()));
        return m;
    }

    private static String editor() {
        return CurrentUser.sub().orElse("admin");
    }

    private static String asString(Object o) {
        return (o instanceof String s) ? s : (o == null ? null : String.valueOf(o));
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? def : Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : b;
    }
}
