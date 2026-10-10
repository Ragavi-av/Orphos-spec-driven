package io.orphos.eip.admin.auth.service;

import io.orphos.eip.admin.auth.dto.Dtos;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A6 - Authentication analytics.
 *
 * Rolls AuthEvent rows up into the per-24h summary the admin UI shows.
 * Kept stateless and recomputed on demand - the volume of auth events in a
 * single tenant is small enough that caching would be premature.
 */
@Service
public class AuthAnalyticsService {

    private final AuthRepository repo;

    public AuthAnalyticsService(AuthRepository repo) {
        this.repo = repo;
    }

    public Dtos.AuthAnalyticsView summary24h() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        List<Map<String, Object>> events = repo.recentEvents(since);

        long success = 0, failure = 0;
        Map<String, Long> byMethod = new HashMap<>();
        Map<String, Long> reasons = new HashMap<>();
        for (Map<String, Object> e : events) {
            String type = Objects.toString(e.get("type"));
            String method = Objects.toString(e.getOrDefault("method", ""));
            boolean ok = Boolean.TRUE.equals(e.get("success"));
            if ("LOGIN_SUCCESS".equals(type) && ok) success++;
            if ("LOGIN_FAILURE".equals(type)) {
                failure++;
                String r = Objects.toString(e.getOrDefault("reason", "unknown"));
                reasons.merge(r, 1L, Long::sum);
            }
            byMethod.merge(method, 1L, Long::sum);
        }
        return new Dtos.AuthAnalyticsView(events.size(), success, failure, byMethod, reasons);
    }
}
