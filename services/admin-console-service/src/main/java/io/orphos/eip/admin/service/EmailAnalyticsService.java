package io.orphos.eip.admin.service;

import io.orphos.eip.admin.vo.EmailDtos;
import io.orphos.eip.admin.repository.EmailRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * C7 - email analytics and tracking.
 *
 * Rolls EmailEvent into the per-24h summary the admin UI shows. Each event
 * carries a hashed recipient (not the raw address), so this surface never
 * exposes recipient identities.
 */
@Service
public class EmailAnalyticsService {

    private final EmailRepository repo;

    public EmailAnalyticsService(EmailRepository repo) {
        this.repo = repo;
    }

    public EmailDtos.EmailAnalyticsView summary24h() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        List<Map<String, Object>> events = repo.eventsSince(since);

        long sent = 0, delivered = 0, bounced = 0, opened = 0;
        Map<String, Long> byTemplate = new HashMap<>();
        for (Map<String, Object> e : events) {
            String status = Objects.toString(e.get("status"));
            String tc = Objects.toString(e.getOrDefault("templateCode", ""));
            sent++;
            switch (status) {
                case "DELIVERED" -> delivered++;
                case "BOUNCED", "FAILED" -> bounced++;
                case "OPENED" -> opened++;
                default -> { /* QUEUED etc. */ }
            }
            byTemplate.merge(tc, 1L, Long::sum);
        }
        return new EmailDtos.EmailAnalyticsView(sent, delivered, bounced, opened, byTemplate);
    }

    public List<EmailDtos.EmailEventView> recent24h() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        return repo.eventsSince(since).stream()
                .map(r -> new EmailDtos.EmailEventView(
                        Objects.toString(r.get("id")),
                        Objects.toString(r.getOrDefault("templateCode", "")),
                        Objects.toString(r.getOrDefault("toHash", "")),
                        Objects.toString(r.getOrDefault("status", "")),
                        Objects.toString(r.getOrDefault("reason", "")),
                        parseInstant(r.get("createdAt"))))
                .toList();
    }

    /** Hook called by external delivery webhooks (e.g. SES, SendGrid) to record opens/bounces. */
    public void recordExternalEvent(String templateCode, String toAddress, String status, String reason) {
        repo.recordEvent(templateCode, toAddress, status, reason);
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return Instant.EPOCH;
        try { return Instant.parse(o.toString()); } catch (Exception e) { return Instant.EPOCH; }
    }
}
