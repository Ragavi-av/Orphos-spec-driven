package io.orphos.eip.admin.auth.service;

import io.orphos.eip.admin.auth.dto.Dtos;
import io.orphos.eip.admin.auth.model.AuthEventType;
import io.orphos.eip.admin.auth.model.AuthMethodType;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A1 - Self-managed authentication.
 *
 * Local username/password using BCrypt. Enforces:
 *  - Email uniqueness
 *  - Minimum password length (validated in DTO)
 *  - Failed-attempt lockout per {@code admin.auth.lockout.*} config
 */
@Service
public class LocalAuthService {

    private final AuthRepository repo;
    private final PasswordEncoder encoder;
    private final AuthMonitoringService monitor;

    @Value("${admin.auth.lockout.max-failed-attempts:5}")
    private int maxAttempts;

    @Value("${admin.auth.lockout.lock-minutes:30}")
    private int lockMinutes;

    public LocalAuthService(AuthRepository repo, PasswordEncoder encoder,
                            AuthMonitoringService monitor) {
        this.repo = repo;
        this.encoder = encoder;
        this.monitor = monitor;
    }

    public Dtos.LoginResponse register(Dtos.RegisterRequest req, String remoteIp) {
        if (repo.findLocalUserByEmail(req.email()).isPresent()) {
            throw new IllegalStateException("account already exists");
        }
        String hash = encoder.encode(req.password());
        Map<String, Object> created = repo.insertLocalUser(req.email(), hash, req.displayName());
        String userKey = (String) created.get("id");
        repo.upsertBinding(userKey, AuthMethodType.LOCAL, "local");
        repo.recordEvent(userKey, AuthEventType.REGISTER, AuthMethodType.LOCAL, true, "self-registered", remoteIp);
        return new Dtos.LoginResponse(userKey, AuthMethodType.LOCAL, Instant.now());
    }

    public Dtos.LoginResponse login(Dtos.LoginRequest req, String remoteIp) {
        Optional<Map<String, Object>> opt = repo.findLocalUserByEmail(req.email());
        if (opt.isEmpty()) {
            // Anonymous failure - record without leaking which emails exist
            repo.recordEvent("anonymous", AuthEventType.LOGIN_FAILURE, AuthMethodType.LOCAL,
                    false, "unknown_user", remoteIp);
            monitor.onFailedLogin("anonymous", remoteIp);
            throw new IllegalArgumentException("invalid credentials");
        }

        Map<String, Object> user = opt.get();
        String userId = (String) user.get("id");
        Instant lockedUntil = parseInstant(user.get("lockedUntil"));
        if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            repo.recordEvent(userId, AuthEventType.LOGIN_FAILURE, AuthMethodType.LOCAL,
                    false, "account_locked", remoteIp);
            throw new IllegalStateException("account temporarily locked");
        }

        String hash = (String) user.get("passwordHash");
        if (hash == null || !encoder.matches(req.password(), hash)) {
            int attempts = ((Number) user.getOrDefault("failedAttempts", 0)).intValue() + 1;
            Instant lockUntil = attempts >= maxAttempts
                    ? Instant.now().plus(Duration.ofMinutes(lockMinutes)) : null;
            repo.recordFailedAttempt(userId, attempts, lockUntil);
            repo.recordEvent(userId, AuthEventType.LOGIN_FAILURE, AuthMethodType.LOCAL,
                    false, "bad_password", remoteIp);
            if (lockUntil != null) {
                repo.recordEvent(userId, AuthEventType.ACCOUNT_LOCKED, AuthMethodType.LOCAL,
                        false, "max_attempts_exceeded", remoteIp);
            }
            monitor.onFailedLogin(userId, remoteIp);
            throw new IllegalArgumentException("invalid credentials");
        }

        repo.resetFailedAttempts(userId);
        repo.recordEvent(userId, AuthEventType.LOGIN_SUCCESS, AuthMethodType.LOCAL, true,
                "ok", remoteIp);
        return new Dtos.LoginResponse(userId, AuthMethodType.LOCAL, Instant.now());
    }

    public void forgot(Dtos.ForgotPasswordRequest req, String remoteIp) {
        // We always answer 202 regardless of whether the email exists, to avoid
        // disclosing membership. The actual reset link is dispatched by the email
        // service if and only if the user does exist.
        var opt = repo.findLocalUserByEmail(req.email());
        opt.ifPresent(u -> repo.recordEvent((String) u.get("id"),
                AuthEventType.PASSWORD_RESET_REQUESTED, AuthMethodType.LOCAL, true,
                "reset_requested", remoteIp));
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return null;
        try { return Instant.parse(o.toString()); } catch (Exception e) { return null; }
    }
}
