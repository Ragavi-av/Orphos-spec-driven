package io.orphos.eip.admin.service;

import io.orphos.eip.admin.service.AuditService;
import io.orphos.eip.admin.vo.EmailDtos;
import io.orphos.eip.admin.repository.EmailRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * C8 - email configuration backup / restore.
 *
 * Stores a JSON snapshot of the active SMTP config (minus the password ref,
 * which is intentionally NOT round-trippable - operators re-enter the secret
 * on restore) and all templates. Restoring is non-destructive: it upserts
 * everything in the snapshot but does not delete records absent from it.
 */
@Service
public class EmailBackupService {

    private final EmailRepository repo;
    private final EmailConfigService configService;
    private final TemplateService templateService;
    private final AuditService audit;

    public EmailBackupService(EmailRepository repo, EmailConfigService configService,
                              TemplateService templateService, AuditService audit) {
        this.repo = repo;
        this.configService = configService;
        this.templateService = templateService;
        this.audit = audit;
    }

    public EmailDtos.EmailBackupView create(String label, String actorKey) {
        EmailDtos.EmailConfigView active = configService.active();
        List<EmailDtos.EmailTemplateView> templates = templateService.list();
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("config", active);
        snapshot.put("templates", templates);
        snapshot.put("takenAt", Instant.now().toString());
        String id = repo.storeBackup(label, snapshot);
        audit.record(actorKey, "EMAIL_BACKUP", id, Map.of("label", label));
        return new EmailDtos.EmailBackupView(id, label, Instant.now());
    }

    public List<EmailDtos.EmailBackupView> list() {
        return repo.listBackups().stream()
                .map(r -> new EmailDtos.EmailBackupView(
                        Objects.toString(r.get("id")),
                        Objects.toString(r.getOrDefault("label", "")),
                        parseInstant(r.get("createdAt"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public void restore(String backupId, String actorKey) {
        Map<String, Object> backup = repo.findBackup(backupId)
                .orElseThrow(() -> new IllegalArgumentException("unknown backup"));
        Map<String, Object> snap = (Map<String, Object>) backup.getOrDefault("snapshot", Map.of());

        Map<String, Object> cfg = (Map<String, Object>) snap.getOrDefault("config", Map.of());
        if (!cfg.isEmpty()) {
            EmailDtos.EmailConfigRequest req = new EmailDtos.EmailConfigRequest(
                    Objects.toString(cfg.getOrDefault("name", "restored")),
                    Objects.toString(cfg.getOrDefault("host", "")),
                    ((Number) cfg.getOrDefault("port", 587)).intValue(),
                    Objects.toString(cfg.getOrDefault("fromAddress", "")),
                    Objects.toString(cfg.getOrDefault("fromName", "")),
                    Objects.toString(cfg.getOrDefault("username", "")),
                    null,   // password intentionally not restored
                    Objects.toString(cfg.getOrDefault("security", "STARTTLS")),
                    false   // operator re-enables explicitly after re-entering password
            );
            configService.upsert(req, actorKey);
        }

        List<Map<String, Object>> tmpls = (List<Map<String, Object>>) snap.getOrDefault("templates", List.of());
        for (Map<String, Object> t : tmpls) {
            EmailDtos.EmailTemplateRequest tr = new EmailDtos.EmailTemplateRequest(
                    Objects.toString(t.get("code")),
                    Objects.toString(t.get("name")),
                    Objects.toString(t.get("subject")),
                    Objects.toString(t.getOrDefault("bodyHtml", "")),
                    Objects.toString(t.getOrDefault("bodyText", "")),
                    (List<String>) t.getOrDefault("requiredVariables", List.of())
            );
            templateService.upsert(tr, actorKey);
        }
        audit.record(actorKey, "EMAIL_RESTORE", backupId, Map.of());
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return Instant.EPOCH;
        try { return Instant.parse(o.toString()); } catch (Exception e) { return Instant.EPOCH; }
    }
}
