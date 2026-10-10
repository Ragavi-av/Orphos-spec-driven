package io.orphos.eip.admin.service;

import io.orphos.eip.admin.vo.EmailDtos;
import io.orphos.eip.admin.repository.EmailRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * C6 - notification preference management.
 */
@Service
public class NotificationPrefService {

    private final EmailRepository repo;

    public NotificationPrefService(EmailRepository repo) {
        this.repo = repo;
    }

    public EmailDtos.NotificationPrefView get(String userKey) {
        return repo.findPrefs(userKey)
                .map(NotificationPrefService::toView)
                .orElseGet(() -> new EmailDtos.NotificationPrefView(
                        userKey, true, true, true, false, Instant.EPOCH));
    }

    public EmailDtos.NotificationPrefView save(String callerKey, EmailDtos.NotificationPrefRequest req) {
        String target = (req.userKey() == null || req.userKey().isBlank()) ? callerKey : req.userKey();
        repo.upsertPrefs(target, req.systemAlerts(), req.briefingDigest(),
                req.securityAlerts(), req.marketingUpdates());
        return get(target);
    }

    private static EmailDtos.NotificationPrefView toView(Map<String, Object> r) {
        return new EmailDtos.NotificationPrefView(
                java.util.Objects.toString(r.get("userKey")),
                Boolean.TRUE.equals(r.get("systemAlerts")),
                Boolean.TRUE.equals(r.get("briefingDigest")),
                Boolean.TRUE.equals(r.get("securityAlerts")),
                Boolean.TRUE.equals(r.get("marketingUpdates")),
                parseInstant(r.get("updatedAt")));
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return Instant.EPOCH;
        try { return Instant.parse(o.toString()); } catch (Exception e) { return Instant.EPOCH; }
    }
}
