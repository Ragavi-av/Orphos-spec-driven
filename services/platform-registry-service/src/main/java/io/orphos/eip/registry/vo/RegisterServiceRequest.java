package io.orphos.eip.registry.vo;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Self-registration payload for a platform service (tier model §16). Sent by a
 * base/service at startup, or used by an admin to register one manually.
 */
public record RegisterServiceRequest(
        @NotBlank String serviceId,
        @NotBlank String displayName,
        String tier,
        String version,
        @NotBlank String healthUrl,
        List<String> capabilities
) {}
