package io.orphos.eip.docingest.audit;

import java.time.Instant;

/** Immutable audit trail entry for a file operation. */
public record AuditEvent(
    String eventType,    // UPLOAD | DOWNLOAD | DELETE | INGEST_COMPLETE | INGEST_FAILED
    String documentId,
    String tenantId,
    String fileName,
    String performedBy,
    String detail,
    Instant timestamp
) {}
