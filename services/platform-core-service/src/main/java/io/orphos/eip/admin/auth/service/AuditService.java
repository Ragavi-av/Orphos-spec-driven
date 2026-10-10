package io.orphos.eip.admin.auth.service;

import io.orphos.eip.admin.auth.dto.Dtos;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A8 - Authentication audit logging.
 *
 * Thin facade around the AuthAuditLog table. All admin-mutating endpoints
 * call {@link #record} so we have a complete trail of who changed what.
 */
@Service
public class AuditService {

    private final AuthRepository repo;

    public AuditService(AuthRepository repo) {
        this.repo = repo;
    }

    public void record(String actorKey, String action, String target, Map<String, Object> detail) {
        repo.appendAudit(actorKey, action, target, detail);
    }

    public List<Dtos.AuditEntryView> recent(int limit) {
        int safe = Math.max(1, Math.min(limit, 500));
        return repo.listAudit(safe).stream().map(this::toView).toList();
    }

    @SuppressWarnings("unchecked")
    private Dtos.AuditEntryView toView(Map<String, Object> r) {
        return new Dtos.AuditEntryView(
                Objects.toString(r.get("id")),
                Objects.toString(r.getOrDefault("actorKey", "system")),
                Objects.toString(r.get("action")),
                Objects.toString(r.getOrDefault("target", "")),
                (Map<String, Object>) r.getOrDefault("detail", Map.of()),
                parseInstant(r.get("createdAt"))
        );
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return Instant.EPOCH;
        try { return Instant.parse(o.toString()); } catch (Exception e) { return Instant.EPOCH; }
    }
}
