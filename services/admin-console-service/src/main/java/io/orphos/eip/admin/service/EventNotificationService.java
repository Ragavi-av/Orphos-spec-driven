package io.orphos.eip.admin.service;

import io.orphos.eip.admin.component.RecipientResolver;
import io.orphos.eip.admin.repository.EmailRepository;
import io.orphos.eip.admin.service.EmailSendService;
import io.orphos.eip.admin.service.TemplateService;
import io.orphos.eip.admin.util.EventCatalog;
import io.orphos.eip.admin.vo.EventDtos;
import io.orphos.eip.admin.repository.EventRepository;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Core of the event-communication framework.
 *
 * <p>An inbound event is (1) recorded to the per-tenant event log, (2) matched
 * against the tenant's enabled {@code NotificationRule}s for its type, (3) the
 * audience of each matching rule is resolved to email addresses (platform
 * admins / tenant admins / all users / a specific role — via
 * {@link RecipientResolver} — plus any extra addresses on the rule), and
 * (4) one email per recipient is rendered from the rule's template (seeded
 * from {@link EventCatalog} on first use) and sent through the tenant's own
 * SMTP configuration from the Communications tab.</p>
 *
 * <p>Platform-scoped events (no tenantId, e.g. SERVICE_DOWN) fan out to every
 * tenant that has an enabled rule for the type, each delivery using that
 * tenant's SMTP config and realm users.</p>
 *
 * <p>Dispatch is deliberately non-throwing: producers fired-and-forgot, so
 * every failure is recorded on the event log entry instead of propagating.</p>
 */
@Service
public class EventNotificationService {

    private static final Logger log = LogManager.getLogger(EventNotificationService.class);

    private final EventRepository events;
    private final EmailRepository emails;
    private final TemplateService templates;
    private final EmailSendService sender;
    private final RecipientResolver recipients;

    public EventNotificationService(EventRepository events, EmailRepository emails,
                                    TemplateService templates, EmailSendService sender,
                                    RecipientResolver recipients) {
        this.events = events;
        this.emails = emails;
        this.templates = templates;
        this.sender = sender;
        this.recipients = recipients;
    }

    /** Entry point for the internal ingest endpoint and in-process producers. */
    public void dispatch(EventDtos.EventIngestRequest req) {
        if (req.tenantId() != null && !req.tenantId().isBlank()) {
            UUID tenantId;
            try {
                tenantId = UUID.fromString(req.tenantId().trim());
            } catch (IllegalArgumentException e) {
                log.warn("Event {} dropped — malformed tenantId '{}'", req.eventType(), req.tenantId());
                return;
            }
            TenantContextHolder.runWith(TenantContext.of(tenantId, null),
                    () -> dispatchForCurrentTenant(req, tenantId));
            return;
        }

        // Platform-scoped event: fan out to every tenant with an enabled rule.
        List<Map<String, Object>> allRules = events.listEnabledRulesAllTenants(req.eventType());
        if (allRules.isEmpty()) {
            log.info("Platform event {} received but no tenant has an enabled rule for it", req.eventType());
            return;
        }
        Map<String, List<Map<String, Object>>> byTenant = new LinkedHashMap<>();
        for (Map<String, Object> r : allRules) {
            byTenant.computeIfAbsent(Objects.toString(r.get("tenantId")), k -> new ArrayList<>()).add(r);
        }
        for (String tid : byTenant.keySet()) {
            try {
                UUID tenantId = UUID.fromString(tid);
                TenantContextHolder.runWith(TenantContext.of(tenantId, null),
                        () -> dispatchForCurrentTenant(req, tenantId));
            } catch (Exception e) {
                log.warn("Platform event {} not dispatched for tenant {}: {}",
                        req.eventType(), tid, e.getMessage());
            }
        }
    }

    /** Requires a bound tenant context. */
    private void dispatchForCurrentTenant(EventDtos.EventIngestRequest req, UUID tenantId) {
        String eventId;
        try {
            eventId = events.recordEvent(req.eventType(), req.severityOrDefault(), req.source(),
                    req.subject(), req.message(), req.attributes());
        } catch (Exception e) {
            log.error("Event {} could not be recorded for tenant {}: {}",
                    req.eventType(), tenantId, e.getMessage());
            return;
        }

        List<Map<String, Object>> rules = events.listEnabledRulesForEvent(req.eventType());
        if (rules.isEmpty()) {
            events.markDispatched(eventId, 0, "NO_RULES");
            return;
        }

        int sent = 0;
        int failed = 0;
        boolean recipientsFound = false;
        for (Map<String, Object> rule : rules) {
            String audience = Objects.toString(rule.get("audience"), "");
            String roleName = Objects.toString(rule.getOrDefault("roleName", ""), "");
            Set<String> to = new LinkedHashSet<>(recipients.resolve(audience, roleName, tenantId));
            Object extra = rule.get("extraRecipients");
            if (extra instanceof List<?> extras) {
                for (Object x : extras) {
                    String e = Objects.toString(x, "").trim();
                    if (!e.isEmpty()) to.add(e);
                }
            }
            if (to.isEmpty()) continue;
            recipientsFound = true;

            String templateCode = ensureTemplate(
                    Objects.toString(rule.getOrDefault("templateCode", ""), ""), req.eventType());
            TemplateService.Rendered rendered;
            try {
                rendered = templates.render(templateCode, templateVars(req, tenantId));
            } catch (Exception e) {
                log.warn("Template {} failed to render for event {}: {}",
                        templateCode, req.eventType(), e.getMessage());
                failed += to.size();
                continue;
            }
            for (String addr : to) {
                try {
                    var result = sender.sendRendered(templateCode, addr, rendered);
                    if (result.delivered()) sent++; else failed++;
                } catch (Exception e) {
                    // Typically "no active SMTP configuration" — visible in the
                    // event log so admins know to finish Communications setup.
                    log.warn("Event {} email to {} failed: {}", req.eventType(), addr, e.getMessage());
                    failed++;
                }
            }
        }

        String status = !recipientsFound ? "NO_RECIPIENTS"
                : failed == 0 ? "SENT"
                : sent == 0 ? "FAILED"
                : "PARTIAL";
        events.markDispatched(eventId, sent, status);
        log.info("Event {} [tenant={}] dispatched: {} sent, {} failed, status={}",
                req.eventType(), tenantId, sent, failed, status);
    }

    /**
     * Returns a template code guaranteed to exist for the current tenant.
     * Blank rule template → the event type's default. Missing default →
     * seeded from {@link EventCatalog}. Missing custom code → generic.
     */
    private String ensureTemplate(String ruleTemplateCode, String eventType) {
        String code = ruleTemplateCode.isBlank()
                ? EventCatalog.defaultTemplateCode(eventType)
                : ruleTemplateCode;
        if (emails.findTemplate(code).isPresent()) return code;
        Map<String, Object> seed = EventCatalog.seedTemplate(code);
        if (seed != null) {
            emails.upsertTemplate(seed);
            return code;
        }
        // Rule points at a tenant template that no longer exists — fall back.
        if (emails.findTemplate(EventCatalog.GENERIC_TEMPLATE).isEmpty()) {
            emails.upsertTemplate(Objects.requireNonNull(
                    EventCatalog.seedTemplate(EventCatalog.GENERIC_TEMPLATE)));
        }
        return EventCatalog.GENERIC_TEMPLATE;
    }

    private static Map<String, Object> templateVars(EventDtos.EventIngestRequest req, UUID tenantId) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("eventType", req.eventType());
        vars.put("severity", req.severityOrDefault());
        vars.put("source", req.source() == null ? "" : req.source());
        vars.put("subject", req.subject() == null ? req.eventType() : req.subject());
        vars.put("message", req.message() == null ? "" : req.message());
        vars.put("occurredAt", req.occurredAt() == null ? java.time.Instant.now().toString() : req.occurredAt());
        vars.put("tenantId", tenantId.toString());
        vars.put("attributes", req.attributes() == null ? Map.of() : req.attributes());
        return vars;
    }
}
