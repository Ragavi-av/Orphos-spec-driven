package io.orphos.eip.registry.vo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.registry.entity.PluginManifest;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Wire representation returned to the SPA. The tabsJson and adminSectionsJson
 * columns are deserialized into proper lists so the frontend doesn't have to
 * parse JSON-in-JSON.
 */
public record PluginManifestDto(
        String pluginId,
        String displayName,
        String version,
        String remoteScope,
        String remoteEntryPath,
        List<Map<String, Object>> tabs,
        List<String> adminSections,
        boolean hasSidePanel,
        boolean builtIn,
        boolean enabled
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PluginManifestDto from(PluginManifest m, boolean enabled) {
        return new PluginManifestDto(
                m.getPluginId(),
                m.getDisplayName(),
                m.getVersion(),
                m.getRemoteScope(),
                m.getRemoteEntryPath(),
                parseList(m.getTabsJson()),
                parseStringList(m.getAdminSectionsJson()),
                m.isHasSidePanel(),
                m.isBuiltIn(),
                enabled
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
