package com.orphos.mobile.core.role;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Central holder for the signed-in identity's access profile (from GET /api/mobile/profile):
 * the canonical persona, the raw Keycloak roles, and the (role, resourceKey → effect) grant
 * map. Reachable via {@link com.orphos.mobile.OrphosApp#accessState()}. Read by
 * {@link MobileAccess} to decide which mobile surfaces are visible.
 */
public class AccessState {

    private String persona = "";
    private final List<String> roles = new ArrayList<>();

    /** role → (resourceKey → "allow"|"deny"). */
    private Map<String, Map<String, String>> grantMap = new HashMap<>();
    private Set<String> immutableRoles = defaultImmutable();
    private Set<String> lockedResources = new HashSet<>();
    /** True once a live /profile response has been applied (drives grant-based vs persona fallback). */
    private boolean grantsAvailable = false;

    private static Set<String> defaultImmutable() {
        Set<String> s = new HashSet<>();
        s.add("platform_admin");
        return s;
    }

    public synchronized String persona() { return persona; }

    public synchronized List<String> roles() { return new ArrayList<>(roles); }

    public synchronized boolean grantsAvailable() { return grantsAvailable; }

    public synchronized Map<String, Map<String, String>> grantMap() { return grantMap; }

    public synchronized Set<String> immutableRoles() { return immutableRoles; }

    public synchronized Set<String> lockedResources() { return lockedResources; }

    /**
     * Drops the signed-in identity's persona, roles and grants (sign-out). The process
     * survives sign-out, so without this the next persona would be gated by the previous
     * persona's grants until — or, if the call fails, instead of — its own /profile.
     */
    public synchronized void reset() {
        persona = "";
        roles.clear();
        grantMap = new HashMap<>();
        immutableRoles = defaultImmutable();
        lockedResources = new HashSet<>();
        grantsAvailable = false;
    }

    /** Set the persona directly (e.g. restored from AppConfig on cold start / demo mode). */
    public synchronized void setPersona(String p) {
        this.persona = p == null ? "" : p.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Applies a GET /api/mobile/profile response:
     * {sub, roles:[...], persona:"...", access:{grants:[{role,resourceKey,effect}], immutableRoles, lockedResources}}.
     * The access map may be null.
     */
    public synchronized void applyProfile(JsonObject o) {
        if (o == null) return;
        this.persona = optString(o, "persona");
        roles.clear();
        JsonElement rolesEl = o.get("roles");
        if (rolesEl != null && rolesEl.isJsonArray()) {
            for (JsonElement e : rolesEl.getAsJsonArray()) {
                if (!e.isJsonNull()) roles.add(e.getAsString());
            }
        }

        Map<String, Map<String, String>> gm = new HashMap<>();
        Set<String> immutable = defaultImmutable();
        Set<String> locked = new HashSet<>();
        JsonElement accessEl = o.get("access");
        if (accessEl != null && accessEl.isJsonObject()) {
            JsonObject access = accessEl.getAsJsonObject();
            JsonElement grants = access.get("grants");
            if (grants != null && grants.isJsonArray()) {
                for (JsonElement e : grants.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    JsonObject g = e.getAsJsonObject();
                    String role = optString(g, "role");
                    String key = optString(g, "resourceKey");
                    String effect = optString(g, "effect");
                    if (role.isEmpty() || key.isEmpty()) continue;
                    gm.computeIfAbsent(role, k -> new HashMap<>()).put(key, effect);
                }
            }
            addAll(access.get("immutableRoles"), immutable);
            addAll(access.get("lockedResources"), locked);
        }
        this.grantMap = gm;
        this.immutableRoles = immutable;
        this.lockedResources = locked;
        this.grantsAvailable = true;
    }

    private static void addAll(JsonElement arrEl, Set<String> out) {
        if (arrEl != null && arrEl.isJsonArray()) {
            for (JsonElement e : ((JsonArray) arrEl)) {
                if (!e.isJsonNull()) out.add(e.getAsString());
            }
        }
    }

    private static String optString(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return (e != null && !e.isJsonNull()) ? e.getAsString().trim() : "";
        } catch (Exception ex) { return ""; }
    }
}
