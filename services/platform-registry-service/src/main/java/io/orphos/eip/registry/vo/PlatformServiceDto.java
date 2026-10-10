package io.orphos.eip.registry.vo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.registry.entity.PlatformService;

import java.util.Collections;
import java.util.List;

/**
 * Wire representation of a registered platform service (tier model §16). The
 * {@code capabilitiesJson} column is deserialized into a proper list so the SPA
 * and agents don't parse JSON-in-JSON.
 */
public record PlatformServiceDto(
        String serviceId,
        String displayName,
        String tier,
        String version,
        String healthUrl,
        List<String> capabilities,
        String status,
        String lastSeenAt,
        String registeredAt
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PlatformServiceDto from(PlatformService s) {
        return new PlatformServiceDto(
                s.getServiceId(),
                s.getDisplayName(),
                s.getTier(),
                s.getVersion(),
                s.getHealthUrl(),
                parseStringList(s.getCapabilitiesJson()),
                s.getStatus(),
                s.getLastSeenAt(),
                s.getRegisteredAt() != null ? s.getRegisteredAt().toString() : null
        );
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
