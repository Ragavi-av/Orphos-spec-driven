package io.orphos.eip.admin.auth.dto;

import io.orphos.eip.admin.auth.model.AuthMethodType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** All DTOs for the auth subsystem, kept in one file for readability. */
public final class Dtos {
    private Dtos() {}

    // ----- Requests -----
    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Size(max = 80) String displayName
    ) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record ForgotPasswordRequest(@Email @NotBlank String email) {}

    public record SwitchMethodRequest(@NotBlank String userKey, AuthMethodType target,
                                      String providerName) {}

    public record IdentityProviderRequest(
            @NotBlank String name,
            AuthMethodType type,
            boolean enabled,
            boolean allowSelfRegistration,
            List<String> allowedEmailDomains,
            // Provider-specific config - shape varies; validated per type at the service layer.
            Map<String, Object> config
    ) {}

    public record AuthMethodConfigRequest(boolean localEnabled, boolean oidcEnabled,
                                          boolean samlEnabled, boolean ldapEnabled,
                                          AuthMethodType defaultMethod) {}

    // ----- Responses -----
    public record LoginResponse(String userKey, AuthMethodType method, Instant issuedAt) {}

    public record IdentityProviderView(
            String id, String name, AuthMethodType type, boolean enabled,
            boolean allowSelfRegistration, List<String> allowedEmailDomains,
            // Config is returned redacted (secrets stripped).
            Map<String, Object> safeConfig
    ) {}

    public record AuthMethodConfigView(boolean localEnabled, boolean oidcEnabled,
                                       boolean samlEnabled, boolean ldapEnabled,
                                       AuthMethodType defaultMethod) {}

    public record AuthAnalyticsView(
            long totalAttempts24h, long successes24h, long failures24h,
            Map<String, Long> byMethod24h,
            Map<String, Long> failureReasons24h
    ) {}

    public record SecurityAlertView(String userKey, String reason, long count, Instant lastSeen) {}

    public record AuditEntryView(String id, String actorKey, String action,
                                 String target, Map<String, Object> detail, Instant at) {}
}
