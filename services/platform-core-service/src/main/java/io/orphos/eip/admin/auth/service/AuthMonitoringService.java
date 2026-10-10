package io.orphos.eip.admin.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.orphos.eip.admin.auth.dto.Dtos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * A7 - Authentication security monitoring.
 *
 * In-process detector for brute-force / credential-stuffing patterns. Keeps
 * a rolling Caffeine counter per (userKey, remoteIpHash) and surfaces top
 * offenders to the admin UI.
 *
 * <p>This is intentionally local-only - we don't want a cross-service shared
 * cache for failed login telemetry (it'd be a juicy target). When the
 * platform grows past a single admin instance, swap in a Redis bucket.</p>
 */
@Service
public class AuthMonitoringService {

    private static final Logger log = LogManager.getLogger(AuthMonitoringService.class);

    private final Cache<String, Counter> failureCache;

    @Value("${admin.auth.lockout.window-minutes:15}")
    private int windowMinutes;

    @Value("${admin.auth.lockout.max-failed-attempts:5}")
    private int alertThreshold;

    public AuthMonitoringService() {
        this.failureCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(15))
                .maximumSize(10_000)
                .build();
    }

    public void onFailedLogin(String userKey, String remoteIp) {
        String key = (userKey == null ? "anon" : userKey) + "|" +
                (remoteIp == null ? "" : Integer.toHexString(remoteIp.hashCode()));
        Counter c = failureCache.get(key, k -> new Counter());
        long count = c.add();
        if (count == alertThreshold) {
            log.warn("Auth security alert: {} reached {} failures in window", key, count);
        }
    }

    public List<Dtos.SecurityAlertView> currentAlerts() {
        List<Dtos.SecurityAlertView> out = new ArrayList<>();
        failureCache.asMap().forEach((k, c) -> {
            long count = c.count.sum();
            if (count >= alertThreshold) {
                String userKey = k.contains("|") ? k.substring(0, k.indexOf('|')) : k;
                out.add(new Dtos.SecurityAlertView(userKey, "excessive_failed_logins", count, c.lastSeen));
            }
        });
        out.sort(Comparator.comparingLong(Dtos.SecurityAlertView::count).reversed());
        return out;
    }

    private static final class Counter {
        final LongAdder count = new LongAdder();
        volatile Instant lastSeen = Instant.now();
        long add() {
            count.increment();
            lastSeen = Instant.now();
            return count.sum();
        }
    }
}
