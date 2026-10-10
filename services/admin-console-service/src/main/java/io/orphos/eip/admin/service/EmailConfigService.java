package io.orphos.eip.admin.service;

import io.orphos.eip.admin.component.SmtpSecretStore;
import io.orphos.eip.admin.service.AuditService;
import io.orphos.eip.admin.vo.EmailDtos;
import io.orphos.eip.admin.repository.EmailRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * C1 + C2 - email configuration management.
 */
@Service
public class EmailConfigService {

    private static final Set<String> ALLOWED_SECURITY = Set.of("STARTTLS", "SSL", "NONE");

    private final EmailRepository repo;
    private final SmtpSecretStore secretStore;
    private final AuditService audit;

    public EmailConfigService(EmailRepository repo, SmtpSecretStore secretStore, AuditService audit) {
        this.repo = repo;
        this.secretStore = secretStore;
        this.audit = audit;
    }

    public List<EmailDtos.EmailConfigView> list() {
        return repo.listConfigs().stream().map(EmailConfigService::toView).toList();
    }

    public EmailDtos.EmailConfigView active() {
        return repo.findActiveConfig().map(EmailConfigService::toView).orElse(null);
    }

    public EmailDtos.EmailConfigView upsert(EmailDtos.EmailConfigRequest req, String actorKey) {
        if (!ALLOWED_SECURITY.contains(req.security())) {
            throw new IllegalArgumentException("security must be one of " + ALLOWED_SECURITY);
        }
        if (req.port() <= 0 || req.port() > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        Map<String, Object> c = new HashMap<>();
        c.put("name", req.name());
        c.put("host", req.host());
        c.put("port", req.port());
        c.put("fromAddress", req.fromAddress());
        c.put("fromName", req.fromName());
        c.put("username", req.username());
        c.put("security", req.security());
        c.put("enabled", req.enabled());

        // If a password was supplied, swap it for a ref; otherwise keep whatever
        // ref the existing config had (so editing other fields doesn't blow away
        // the password).
        if (req.password() != null && !req.password().isBlank()) {
            c.put("passwordRef", secretStore.store(req.password()));
        } else {
            repo.findConfigByName(req.name())
                    .ifPresent(existing -> c.put("passwordRef", existing.getOrDefault("passwordRef", "")));
        }
        repo.upsertConfig(c);
        if (req.enabled()) repo.setSingleEnabled(req.name());

        audit.record(actorKey, "EMAIL_CONFIG_UPSERT", req.name(),
                Map.of("host", req.host(), "port", req.port(), "enabled", req.enabled()));
        return repo.findConfigByName(req.name()).map(EmailConfigService::toView)
                .orElseThrow(() -> new IllegalStateException("config not persisted"));
    }

    public Optional<String> resolvePassword(String passwordRef) {
        return secretStore.resolve(passwordRef);
    }

    public Optional<Map<String, Object>> findActiveRaw() {
        return repo.findActiveConfig();
    }

    static EmailDtos.EmailConfigView toView(Map<String, Object> r) {
        return new EmailDtos.EmailConfigView(
                Objects.toString(r.get("id")),
                Objects.toString(r.get("name")),
                Objects.toString(r.get("host")),
                ((Number) r.getOrDefault("port", 587)).intValue(),
                Objects.toString(r.getOrDefault("fromAddress", "")),
                Objects.toString(r.getOrDefault("fromName", "")),
                Objects.toString(r.getOrDefault("username", "")),
                Objects.toString(r.getOrDefault("security", "STARTTLS")),
                Boolean.TRUE.equals(r.get("enabled")),
                parseInstant(r.get("updatedAt"))
        );
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return Instant.EPOCH;
        try { return Instant.parse(o.toString()); } catch (Exception e) { return Instant.EPOCH; }
    }
}
