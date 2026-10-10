package io.orphos.eip.ingest.vo;

import jakarta.validation.constraints.NotBlank;

/** Request/response shapes for the OKG connection admin API. */
public final class ConnectionDtos {

    private ConnectionDtos() {}

    /**
     * {@code apiToken} is the RAW Jira API token, accepted only on write and
     * immediately exchanged for a vault reference — it is never persisted or
     * echoed back. Leave blank on update to keep the existing token.
     */
    public record SaveConnectionRequest(
            @NotBlank String baseUrl,
            @NotBlank String email,
            String apiToken,
            String projectKeys,
            boolean enabled) {}

    /** {@code hasApiToken} tells the admin UI whether a credential is already stored, without exposing it. */
    public record ConnectionView(
            String source,
            String baseUrl,
            String email,
            boolean hasApiToken,
            String projectKeys,
            boolean enabled,
            String lastSyncAt,
            String lastSyncStatus,
            String lastSyncError) {}

    public record SyncTriggerResponse(String status, int projectsSynced, int issuesSynced, int linksSynced, String error) {}
}
