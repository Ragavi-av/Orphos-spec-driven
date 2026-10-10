package io.orphos.eip.admin.service;

import io.orphos.eip.core.arcadedb.ArcadeDbProperties;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.service.AuditService;
import io.orphos.eip.admin.vo.EmailDtos;
import io.orphos.eip.admin.repository.EmailRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplateServiceTest {

    // Plain stubs - dropping Mockito here so tests don't depend on the inline
    // mock maker being compatible with the running JVM.
    private final EmailRepository repo = new EmailRepository(new NoopArcadeClient());
    private final AuditService audit = new AuditService(new NoopArcadeClient());
    private final TemplateService svc = new TemplateService(repo, audit);

    @Test
    void validateFlagsMissingDeclaredVariable() {
        var req = new EmailDtos.EmailTemplateRequest(
                "welcome", "Welcome", "Hi {{firstName}}",
                "<p>Hello {{firstName}}</p>", null, List.of("firstName", "company"));
        var report = svc.validate(req);
        assertFalse(report.ok());
        assertTrue(String.join(";", report.issues()).contains("company"));
    }

    @Test
    void validatePassesForCorrectTemplate() {
        var req = new EmailDtos.EmailTemplateRequest(
                "welcome", "Welcome", "Hi {{firstName}}",
                "<p>Hello {{firstName}}</p>", "Hello {{firstName}}", List.of("firstName"));
        var report = svc.validate(req);
        assertTrue(report.ok(), "issues: " + report.issues());
    }

    /** ArcadeDbTemplate that returns empty results - validate() doesn't touch the DB. */
    static class NoopArcadeClient extends ArcadeDbTemplate {
        NoopArcadeClient() { super(new ArcadeDbProperties()); }
        @Override public List<Map<String, Object>> query(String sql, Map<String, Object> params) { return List.of(); }
        @Override public List<Map<String, Object>> command(String sql, Map<String, Object> params) { return List.of(); }
        @Override public void commandNoResult(String sql, Map<String, Object> params) {}
    }
}
