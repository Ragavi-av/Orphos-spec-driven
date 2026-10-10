package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.service.EmailAnalyticsService;
import io.orphos.eip.admin.service.EmailBackupService;
import io.orphos.eip.admin.service.EmailConfigService;
import io.orphos.eip.admin.service.EmailSendService;
import io.orphos.eip.admin.service.TemplateService;
import io.orphos.eip.admin.util.CurrentUser;
import io.orphos.eip.admin.vo.EmailDtos;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin-only email configuration management. */
@RestController
@RequestMapping("/api/admin/email")
@PreAuthorize("hasRole('admin')")
public class AdminEmailController {

    private final EmailConfigService config;
    private final TemplateService templates;
    private final EmailSendService sender;
    private final EmailAnalyticsService analytics;
    private final EmailBackupService backups;

    public AdminEmailController(EmailConfigService config, TemplateService templates,
                                EmailSendService sender, EmailAnalyticsService analytics,
                                EmailBackupService backups) {
        this.config = config;
        this.templates = templates;
        this.sender = sender;
        this.analytics = analytics;
        this.backups = backups;
    }

    // ----- C1 / C2 -----
    @GetMapping("/config")
    public List<EmailDtos.EmailConfigView> list() { return config.list(); }

    @GetMapping("/config/active")
    public EmailDtos.EmailConfigView active() { return config.active(); }

    @PutMapping("/config")
    public EmailDtos.EmailConfigView upsert(@Valid @RequestBody EmailDtos.EmailConfigRequest req) {
        return config.upsert(req, CurrentUser.key());
    }

    // ----- C3 -----
    @GetMapping("/templates")
    public List<EmailDtos.EmailTemplateView> templates() { return templates.list(); }

    @PutMapping("/templates")
    public EmailDtos.EmailTemplateView upsertTemplate(@Valid @RequestBody EmailDtos.EmailTemplateRequest req) {
        return templates.upsert(req, CurrentUser.key());
    }

    @DeleteMapping("/templates/{code}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String code) {
        templates.delete(code, CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    // ----- C4 -----
    @PostMapping("/templates/validate")
    public EmailDtos.TemplateValidationReport validate(@Valid @RequestBody EmailDtos.EmailTemplateRequest req) {
        return templates.validate(req);
    }

    // ----- C5 -----
    @PostMapping("/test")
    public EmailDtos.DeliveryResult test(@Valid @RequestBody EmailDtos.TestEmailRequest req) {
        return sender.sendTest(req);
    }

    // ----- C7 -----
    @GetMapping("/analytics")
    public EmailDtos.EmailAnalyticsView analytics() { return analytics.summary24h(); }

    @GetMapping("/events")
    public List<EmailDtos.EmailEventView> events() { return analytics.recent24h(); }

    // ----- C8 -----
    @GetMapping("/backups")
    public List<EmailDtos.EmailBackupView> listBackups() { return backups.list(); }

    @PostMapping("/backups")
    public EmailDtos.EmailBackupView createBackup(@Valid @RequestBody EmailDtos.EmailBackupRequest req) {
        return backups.create(req.label(), CurrentUser.key());
    }

    @PostMapping("/backups/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable String id) {
        backups.restore(id, CurrentUser.key());
        return ResponseEntity.noContent().build();
    }
}
