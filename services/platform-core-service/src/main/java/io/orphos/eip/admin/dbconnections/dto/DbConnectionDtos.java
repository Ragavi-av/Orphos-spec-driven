package io.orphos.eip.admin.dbconnections.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * Request/response shapes for the DB-connections admin API.
 *
 * <p>Secrets follow the same contract as the data-ingestion connection API:
 * {@code secret} is the RAW credential, accepted only on write and immediately
 * exchanged for an Infisical reference — never persisted in the registry file
 * or echoed back. {@code hasCredential} tells the UI a secret is stored without
 * exposing it.</p>
 */
public final class DbConnectionDtos {

    private DbConnectionDtos() {}

    /** Create/update a connection. {@code id} is taken from the path on update; ignored here. */
    public record SaveConnectionRequest(
            @NotBlank String name,
            @NotBlank String type,          // ARCADEDB | POSTGRES | NEO4J
            String host,
            Integer port,
            String user,
            String url,                     // optional explicit URL (overrides host/port)
            Map<String, String> options,
            String secret) {}               // blank on update keeps the stored credential

    public record ConnectionView(
            String id,
            String name,
            String type,
            String host,
            Integer port,
            String user,
            String url,                     // derived endpoint the platform will use
            Map<String, String> options,
            boolean hasCredential,
            String lastTestAt,
            String lastTestStatus,
            String lastTestError,
            String createdAt,
            String updatedAt) {}

    /** Ad-hoc (pre-save) connection test — nothing persisted. */
    public record TestConnectionRequest(
            @NotBlank String type,
            String host,
            Integer port,
            String user,
            String url,
            Map<String, String> options,
            String secret) {}

    /** Outcome of a connection test. {@code detail} is e.g. the DB product/version. */
    public record TestResult(boolean ok, String message, String detail, long latencyMs, String testedFrom) {}

    public record ConnectionListResponse(List<ConnectionView> connections) {}

    // ── Assignments ───────────────────────────────────────────────────────────

    /** One row of the service × connection assignment matrix. */
    public record AssignmentRow(
            String serviceId,
            String containerName,
            String connectionId,            // null = not assigned (uses env default)
            String database,
            String schemaMode,              // DEFAULT | EXISTING
            String effectiveDatabase,       // read-only: the DB/schema the service actually uses
                                            // (explicit database when set, else the code default)
            String effectiveConnectionName) {} // read-only: the connection the service resolves to
                                            // (assigned connection's name, or the bundled default)

    public record AssignmentMatrixView(
            List<ConnectionView> connections,
            List<AssignmentRow> assignments) {}

    /**
     * Save the assignment matrix. {@code confirm} is the operator's explicit
     * acknowledgement that it is safe to (re)point already-bound services at a
     * different database in {@code DEFAULT} mode — i.e. that schema creation is
     * acceptable and no data will be lost. When such a change is present and
     * {@code confirm} is false, the write is refused and the affected services
     * are returned in {@link ApplyResult#confirmations()} for the UI to confirm.
     */
    public record SaveAssignmentsRequest(List<AssignmentRow> assignments, boolean confirm) {}

    /**
     * Result of writing assignments / applying. {@code affected} lists the
     * services whose binding changed (restart to apply). When
     * {@code requiresConfirmation} is true, nothing was written: {@code confirmations}
     * lists the risky changes the operator must confirm before re-submitting with
     * {@code confirm=true}.
     */
    public record ApplyResult(
            boolean written,
            List<AffectedService> affected,
            String message,
            boolean requiresConfirmation,
            List<PendingConfirmation> confirmations) {

        /** Back-compat constructor for the non-confirmation paths (nothing pending). */
        public ApplyResult(boolean written, List<AffectedService> affected, String message) {
            this(written, affected, message, false, List.of());
        }
    }

    public record AffectedService(String serviceId, String containerName) {}

    /**
     * A pending, risky assignment change the operator must confirm. {@code reason}
     * is a human-readable explanation of why confirmation is required (e.g. the
     * service is being re-pointed to a different database where the platform would
     * create its schema).
     */
    public record PendingConfirmation(String serviceId, String containerName, String reason) {}
}
