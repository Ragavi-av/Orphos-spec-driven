package io.orphos.eip.nlquery.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.component.provider.ChatProvider;
import io.orphos.eip.nlquery.component.provider.ChatProviderRegistry;
import io.orphos.eip.nlquery.util.CurrentUser;
import io.orphos.eip.nlquery.component.UserApiKeyStore;
import io.orphos.eip.nlquery.component.UserProviderConfigStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-user external-AI API keys.
 *
 * Self-serve: a user can read/write/clear their own keys. The key value is
 * never echoed back — responses only indicate whether a key is configured.
 *
 * Admin: an admin can do the same on behalf of any user via /users/{sub}/keys.
 *
 * All keys live in OpenBao at secret/tenants/{tenantId}/users/{sub}/{providerId};
 * the non-secret "configured" state is mirrored to ArcadeDB
 * ({@code AiProviderConfig}) for durability and audit. The resolution order at
 * chat-time is: per-request override → per-user → tenant → server default.
 */
@RestController
@RequestMapping("/api/nl-query")
public class UserApiKeyController {

    private final UserApiKeyStore store;
    private final UserProviderConfigStore configStore;
    private final ChatProviderRegistry registry;

    public UserApiKeyController(UserApiKeyStore store,
                                UserProviderConfigStore configStore,
                                ChatProviderRegistry registry) {
        this.store = store;
        this.configStore = configStore;
        this.registry = registry;
    }

    // ── Self-serve ──────────────────────────────────────────────────────────

    @GetMapping("/me/keys")
    public ResponseEntity<Map<String, Object>> listMyKeys() {
        String sub = currentSub();
        return ResponseEntity.ok(buildSummary(sub));
    }

    @PutMapping("/me/keys/{providerId}")
    public ResponseEntity<Map<String, Object>> setMyKey(
            @PathVariable String providerId,
            @RequestBody Map<String, Object> body) {
        return setKey(currentSub(), providerId, body);
    }

    @DeleteMapping("/me/keys/{providerId}")
    public ResponseEntity<Map<String, Object>> deleteMyKey(@PathVariable String providerId) {
        return deleteKey(currentSub(), providerId);
    }

    // ── Admin: act on behalf of any user ────────────────────────────────────

    @GetMapping("/users/{userSub}/keys")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Map<String, Object>> listUserKeys(@PathVariable String userSub) {
        return ResponseEntity.ok(buildSummary(userSub));
    }

    @PutMapping("/users/{userSub}/keys/{providerId}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Map<String, Object>> setUserKey(
            @PathVariable String userSub,
            @PathVariable String providerId,
            @RequestBody Map<String, Object> body) {
        return setKey(userSub, providerId, body);
    }

    @DeleteMapping("/users/{userSub}/keys/{providerId}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Map<String, Object>> deleteUserKey(
            @PathVariable String userSub,
            @PathVariable String providerId) {
        return deleteKey(userSub, providerId);
    }

    // ── Shared plumbing ─────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> setKey(String userSub, String providerId, Map<String, Object> body) {
        if (!store.isBackendConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "Per-user key storage is not configured. Configure the Infisical secrets backend on the server."));
        }
        ChatProvider provider = lookupExternal(providerId);
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown or in-house provider: " + providerId));
        }
        Object raw = body == null ? null : body.get("apiKey");
        if (!(raw instanceof String) || ((String) raw).isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Field 'apiKey' is required"));
        }
        UUID tenantId = currentTenant();
        store.writeKey(tenantId, userSub, providerId, (String) raw);
        configStore.upsert(tenantId, userSub, providerId, true, currentDisplay());
        return ResponseEntity.ok(Map.of(
                "providerId", providerId,
                "configured", true
        ));
    }

    private ResponseEntity<Map<String, Object>> deleteKey(String userSub, String providerId) {
        if (!store.isBackendConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "Per-user key storage is not configured. Configure the Infisical secrets backend on the server."));
        }
        ChatProvider provider = lookupExternal(providerId);
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown or in-house provider: " + providerId));
        }
        UUID tenantId = currentTenant();
        store.deleteKey(tenantId, userSub, providerId);
        configStore.upsert(tenantId, userSub, providerId, false, currentDisplay());
        return ResponseEntity.ok(Map.of(
                "providerId", providerId,
                "configured", false
        ));
    }

    private Map<String, Object> buildSummary(String userSub) {
        UUID tenantId = currentTenant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userSub", userSub);
        out.put("backendConfigured", store.isBackendConfigured());

        // Durable mirror of past setup — survives an OpenBao wipe, so we can flag
        // providers the user configured before but whose live key is now missing.
        Map<String, Boolean> persisted = configStore.listConfigured(tenantId, userSub);

        Map<String, Object> keys = new LinkedHashMap<>();
        for (ChatProvider p : registry.all()) {
            if (p.isInternal()) continue;
            boolean live = store.isBackendConfigured() && store.isConfigured(tenantId, userSub, p.id());
            boolean everConfigured = Boolean.TRUE.equals(persisted.get(p.id()));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", p.label());
            entry.put("configured", live);
            // True when the durable record says a key existed but OpenBao no longer
            // has it — the UI can prompt the user to re-enter their personal key.
            entry.put("needsReentry", everConfigured && !live);
            keys.put(p.id(), entry);
        }
        out.put("providers", keys);
        return out;
    }

    private UUID currentTenant() {
        return TenantContextHolder.current()
                .map(c -> c.tenantId())
                .orElseThrow(() -> new IllegalStateException("No tenant context on request"));
    }

    private String currentDisplay() {
        return CurrentUser.displayName().orElse("");
    }

    private ChatProvider lookupExternal(String providerId) {
        try {
            ChatProvider p = registry.resolve(providerId);
            return p.isInternal() ? null : p;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String currentSub() {
        return CurrentUser.sub()
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }
}
