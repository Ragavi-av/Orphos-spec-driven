package io.orphos.eip.mailinsight.controller;

import io.orphos.eip.mailinsight.agent.EmailTaskAgentService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Email-task agent API behind the Notifications / Tasks-for-Action kebab-menu
 * AI actions ("Draft reply…", "Schedule meeting…").
 *
 * <p>Two-step contract so nothing is sent unreviewed: {@code /draft} returns
 * an editable proposal, {@code /send} delivers exactly what the user
 * confirmed. Tenant scoping and personal-mailbox visibility are enforced by
 * {@code MailInsightRepository} (an item owned by another user resolves to
 * "unknown item").</p>
 */
@RestController
@RequestMapping("/api/mail-insight/items/{id}/agent")
public class EmailTaskAgentController {

    private static final Logger log = LogManager.getLogger(EmailTaskAgentController.class);

    private final EmailTaskAgentService agent;

    public EmailTaskAgentController(EmailTaskAgentService agent) {
        this.agent = agent;
    }

    /** Lets the UI hide the AI actions when the agent is not configured. */
    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String id) {
        return Map.of("enabled", agent.isEnabled());
    }

    public record DraftRequest(String action, String instruction) {}

    @PostMapping("/draft")
    public ResponseEntity<?> draft(@PathVariable String id, @RequestBody DraftRequest req) {
        String action = normalizeAction(req.action());
        if (action == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "action must be REPLY or MEETING"));
        }
        try {
            return ResponseEntity.ok(agent.draft(id, action,
                    req.instruction() == null ? "" : req.instruction().trim()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Agent draft failed for item {}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "draft failed: " + e.getMessage()));
        }
    }

    public record SendBody(String action, List<String> to, List<String> cc,
                           String subject, String bodyText,
                           String start, String end, String location) {}

    @PostMapping("/send")
    public ResponseEntity<?> send(@PathVariable String id, @RequestBody SendBody body) {
        String action = normalizeAction(body.action());
        if (action == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "action must be REPLY or MEETING"));
        }
        try {
            return ResponseEntity.ok(agent.send(id, new EmailTaskAgentService.SendRequest(
                    action, body.to(), body.cc(), body.subject(), body.bodyText(),
                    body.start(), body.end(), body.location())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // Actionable configuration problems (no SMTP config, agent disabled).
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Agent send failed for item {}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "send failed: " + e.getMessage()));
        }
    }

    private static String normalizeAction(String action) {
        String a = Objects.toString(action, "").trim().toUpperCase();
        return switch (a) {
            case EmailTaskAgentService.ACTION_REPLY, EmailTaskAgentService.ACTION_MEETING -> a;
            default -> null;
        };
    }
}
