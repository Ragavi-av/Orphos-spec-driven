package io.orphos.eip.admin.auth.service;

import io.orphos.eip.core.arcadedb.ArcadeDbProperties;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.auth.dto.Dtos;
import io.orphos.eip.admin.auth.model.AuthMethodType;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthConfigServiceTest {

    // Stub repository - guardrails under test are pure validation and don't
    // touch the DB before throwing.
    private final AuthRepository repo = new AuthRepository(new NoopArcadeClient());
    private final AuthConfigService svc = new AuthConfigService(repo);

    @Test
    void refusesToDisableEveryMethod() {
        var req = new Dtos.AuthMethodConfigRequest(false, false, false, false, AuthMethodType.LOCAL);
        assertThrows(IllegalArgumentException.class, () -> svc.updateConfig(req, "admin"));
    }

    @Test
    void requiresDefaultMethod() {
        var req = new Dtos.AuthMethodConfigRequest(true, false, false, false, null);
        assertThrows(IllegalArgumentException.class, () -> svc.updateConfig(req, "admin"));
    }

    static class NoopArcadeClient extends ArcadeDbTemplate {
        NoopArcadeClient() { super(new ArcadeDbProperties()); }
        @Override public List<Map<String, Object>> query(String sql, Map<String, Object> params) { return List.of(); }
        @Override public List<Map<String, Object>> command(String sql, Map<String, Object> params) { return List.of(); }
        @Override public void commandNoResult(String sql, Map<String, Object> params) {}
    }
}
