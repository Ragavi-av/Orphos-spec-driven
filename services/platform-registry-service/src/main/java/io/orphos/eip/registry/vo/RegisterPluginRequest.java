package io.orphos.eip.registry.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterPluginRequest(
        @NotBlank String pluginId,
        @NotBlank String displayName,
        String version,
        @NotBlank String remoteScope,
        @NotBlank String remoteEntryPath,
        String tabsJson,
        String adminSectionsJson,
        boolean hasSidePanel,
        boolean builtIn
) {}
