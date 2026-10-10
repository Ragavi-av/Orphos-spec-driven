package io.orphos.eip.dataingestion.vo;

import java.util.List;

/** Response shapes for the user-action audit trail (requirement 8.4). */
public final class AuditDtos {

    private AuditDtos() {}

    public record AuditEntry(
            String id,
            String actor,     // JWT preferred_username (fallback: sub)
            String action,    // DATASOURCE_CREATED | MAPPING_UPDATED | JOB_TRIGGERED | ...
            String detail,    // method + path + status (+ optional controller note)
            String at) {}     // ISO instant

    public record ListResponse(List<AuditEntry> entries) {}
}
