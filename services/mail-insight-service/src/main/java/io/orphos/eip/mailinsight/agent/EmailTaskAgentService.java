package io.orphos.eip.mailinsight.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.inference.NlQueryClient;
import io.orphos.eip.mailinsight.model.MailInsightDto;
import io.orphos.eip.mailinsight.repo.MailInsightRepository;
import io.orphos.eip.mailinsight.security.CurrentUser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Email-task agent behind the Notifications/Tasks kebab-menu AI actions.
 *
 * <p>An executive picks a mail item and gives an instruction ("reply saying
 * I'll approve by Friday", "set up a 30-minute sync next Tuesday at 10").
 * The agent drafts the reply or meeting with the tenant's LLM (nl-query
 * internal completion — same path the classifier uses) and, after the user
 * reviews/edits the draft in the dialog, sends it through the tenant's
 * SMTP configuration via admin-console-service's internal send endpoint.
 * Meeting requests go out as a standards-based text/calendar (ICS) invite,
 * so recipients get a normal accept/decline card in Outlook/Gmail.</p>
 *
 * <p>Nothing is ever sent without an explicit user confirmation: drafting
 * and sending are separate calls, and the draft returned to the UI is fully
 * editable. The item's stored fields (title/summary/sender display) are the
 * only mail context used — the service never re-reads raw mail bodies.</p>
 */
@Service
public class EmailTaskAgentService {

    private static final Logger log = LogManager.getLogger(EmailTaskAgentService.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern EMAIL_RX = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final DateTimeFormatter ICS_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    public static final String ACTION_REPLY = "REPLY";
    public static final String ACTION_MEETING = "MEETING";

    private final MailInsightRepository repo;
    private final NlQueryClient llm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(SEND_TIMEOUT).build();

    private final boolean enabled;
    private final String emailSendBaseUrl;
    private final String internalToken;

    public EmailTaskAgentService(
            MailInsightRepository repo,
            NlQueryClient llm,
            @Value("${mail-insight.agent.enabled:true}") boolean enabled,
            @Value("${mail-insight.agent.email-send-url:http://eip-admin-console:8094}") String emailSendBaseUrl,
            @Value("${mail-insight.agent.internal-token:${EIP_SECURITY_INTERNAL_SERVICE_TOKEN:}}") String internalToken) {
        this.repo = repo;
        this.llm = llm;
        this.enabled = enabled;
        this.emailSendBaseUrl = emailSendBaseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public boolean isEnabled() {
        return enabled && !internalToken.isEmpty();
    }

    // ── Drafting ─────────────────────────────────────────────────────────────

    public record Draft(String action, String subject, String bodyText,
                        String start, String end, String location, String note) {}

    /**
     * Drafts a reply or meeting for the given item. LLM when available,
     * deterministic skeleton otherwise — the feature degrades, never breaks.
     */
    public Draft draft(String itemId, String action, String instruction) {
        MailInsightDto item = repo.findById(itemId);
        if (item == null) throw new IllegalArgumentException("unknown item: " + itemId);

        String userName = CurrentUser.displayName().orElse("the executive");
        boolean meeting = ACTION_MEETING.equals(action);

        if (llm.isEnabled()) {
            try {
                String prompt = meeting
                        ? meetingPrompt(item, instruction, userName)
                        : replyPrompt(item, instruction, userName);
                var completion = llm.complete(prompt, "email-agent");
                if (completion.isPresent()) {
                    Draft d = parseDraft(action, completion.get());
                    if (d != null) return d;
                }
            } catch (Exception e) {
                log.warn("Agent draft via LLM failed for item {}: {}", itemId, e.getMessage());
            }
        }
        return fallbackDraft(item, action, instruction, userName);
    }

    /**
     * Prompt keys in nl-query's admin-editable store (kind email-drafting,
     * Administration → Communications → Email AI). The bundled templates below
     * must stay in sync with the seeds in nl-query's default-prompts.yml.
     */
    static final String REPLY_PROMPT_KEY   = "email-drafting.reply";
    static final String MEETING_PROMPT_KEY = "email-drafting.meeting";

    private static final String DEFAULT_REPLY_TEMPLATE = """
            You are an executive assistant drafting an email reply on behalf of {{EXECUTIVE}}.

            Original email (only metadata is available):
            - From: {{FROM}}
            - Subject: {{SUBJECT}}
            - Summary: {{SUMMARY}}

            The executive's instruction for the reply:
            {{INSTRUCTION}}

            Write a professional, concise reply that follows the instruction. Match the
            language of the original subject/summary. Sign off with the executive's name.
            Reply with ONLY a compact JSON object, no prose, no markdown fences:
            {"subject": "Re: ...", "bodyText": "the full reply body with \\n line breaks"}
            """;

    private static final String DEFAULT_MEETING_TEMPLATE = """
            You are an executive assistant scheduling a meeting on behalf of {{EXECUTIVE}}.
            Today is {{TODAY}}.

            Context — the meeting relates to this email:
            - From: {{FROM}}
            - Subject: {{SUBJECT}}
            - Summary: {{SUMMARY}}

            The executive's instruction:
            {{INSTRUCTION}}

            Propose the meeting. Resolve any relative dates ("next Tuesday") against
            today's date; default to 30 minutes when no duration is given and pick a
            sensible business-hours slot when none is given. Match the language of the
            context. Reply with ONLY a compact JSON object, no prose, no markdown fences:
            {"subject": "meeting title",
             "bodyText": "short agenda / invitation text",
             "start": "YYYY-MM-DDTHH:mm",
             "end": "YYYY-MM-DDTHH:mm",
             "location": "Teams / room / empty string"}
            """;

    private String replyPrompt(MailInsightDto item, String instruction, String userName) {
        return llm.promptText(REPLY_PROMPT_KEY).orElse(DEFAULT_REPLY_TEMPLATE)
                .replace("{{EXECUTIVE}}",   safe(userName))
                .replace("{{FROM}}",        safe(item.senderDisplay()))
                .replace("{{SUBJECT}}",     safe(item.title()))
                .replace("{{SUMMARY}}",     safe(item.summary()))
                .replace("{{INSTRUCTION}}", safe(instruction));
    }

    private String meetingPrompt(MailInsightDto item, String instruction, String userName) {
        return llm.promptText(MEETING_PROMPT_KEY).orElse(DEFAULT_MEETING_TEMPLATE)
                .replace("{{EXECUTIVE}}",   safe(userName))
                .replace("{{TODAY}}",       java.time.LocalDate.now().toString())
                .replace("{{FROM}}",        safe(item.senderDisplay()))
                .replace("{{SUBJECT}}",     safe(item.title()))
                .replace("{{SUMMARY}}",     safe(item.summary()))
                .replace("{{INSTRUCTION}}", safe(instruction));
    }

    /** Tolerant parse — strips markdown fences, extracts the outermost object. */
    private Draft parseDraft(String action, String completion) {
        try {
            String s = completion.trim()
                    .replaceAll("(?s)^```[a-zA-Z]*", "").replaceAll("```\\s*$", "").trim();
            int open = s.indexOf('{');
            int close = s.lastIndexOf('}');
            if (open < 0 || close <= open) return null;
            JsonNode n = mapper.readTree(s.substring(open, close + 1));
            String subject = n.path("subject").asText("");
            String body = n.path("bodyText").asText("");
            if (subject.isBlank() || body.isBlank()) return null;
            return new Draft(action, subject, body,
                    n.path("start").asText(""), n.path("end").asText(""),
                    n.path("location").asText(""), "drafted by AI — review before sending");
        } catch (Exception e) {
            log.debug("Draft completion did not parse: {}", e.getMessage());
            return null;
        }
    }

    private static Draft fallbackDraft(MailInsightDto item, String action, String instruction, String userName) {
        if (ACTION_MEETING.equals(action)) {
            LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
            return new Draft(action,
                    "Meeting: " + safe(item.title()),
                    "Hi,\n\nI'd like to set up a meeting regarding \"" + safe(item.title()) + "\".\n"
                            + (instruction == null || instruction.isBlank() ? "" : "\n" + instruction + "\n")
                            + "\nBest regards,\n" + userName,
                    start.toString().substring(0, 16),
                    start.plusMinutes(30).toString().substring(0, 16),
                    "",
                    "AI drafting unavailable — template draft, please edit");
        }
        return new Draft(action,
                "Re: " + safe(item.title()),
                "Hello " + safe(item.senderDisplay()) + ",\n\n"
                        + (instruction == null || instruction.isBlank()
                            ? "Thank you for your email regarding \"" + safe(item.title()) + "\"."
                            : instruction)
                        + "\n\nBest regards,\n" + userName,
                "", "", "",
                "AI drafting unavailable — template draft, please edit");
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    public record SendRequest(String action, List<String> to, List<String> cc,
                              String subject, String bodyText,
                              String start, String end, String location) {}

    /** Sends the (user-reviewed) draft via the tenant's SMTP configuration. */
    public Map<String, Object> send(String itemId, SendRequest req) {
        if (!isEnabled()) {
            throw new IllegalStateException("email agent is not configured (internal token missing)");
        }
        MailInsightDto item = repo.findById(itemId);
        if (item == null) throw new IllegalArgumentException("unknown item: " + itemId);

        List<String> to = normalizeAddresses(req.to());
        List<String> cc = normalizeAddresses(req.cc());
        if (to.isEmpty()) throw new IllegalArgumentException("at least one valid recipient is required");
        if (req.subject() == null || req.subject().isBlank()) throw new IllegalArgumentException("subject is required");
        if (req.bodyText() == null || req.bodyText().isBlank()) throw new IllegalArgumentException("body is required");

        String ics = null;
        if (ACTION_MEETING.equals(req.action())) {
            ics = buildIcs(req, to);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", TenantContextHolder.requireTenantId().toString());
        body.put("to", to);
        body.put("cc", cc);
        body.put("subject", req.subject().trim());
        body.put("bodyText", req.bodyText());
        // Answers should reach the executive, not the shared from-address.
        CurrentUser.email().ifPresent(e -> body.put("replyTo", e));
        if (ics != null) {
            body.put("icsContent", ics);
            body.put("icsMethod", "REQUEST");
        }
        body.put("sourceTag", "email-agent");

        try {
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(emailSendBaseUrl + "/api/internal/email/send"))
                    .timeout(SEND_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode n = mapper.readTree(resp.body());
                boolean delivered = n.path("delivered").asBoolean(false);
                if (!delivered) {
                    throw new IllegalStateException("delivery failed: " + n.path("message").asText("unknown"));
                }
                // Reflect that the item has been acted on.
                repo.updateStatus(itemId, "IN_PROGRESS");
                return Map.of("ok", true, "recipients", to.size() + cc.size(),
                        "meeting", ics != null);
            }
            String err = extractError(resp.body());
            // 409 = "no active SMTP configuration" — admin-actionable, keep the message.
            throw new IllegalStateException(resp.statusCode() == 409
                    ? err : "send endpoint returned HTTP " + resp.statusCode() + ": " + err);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not reach the email send endpoint: " + e.getMessage());
        }
    }

    private String extractError(String respBody) {
        try {
            return mapper.readTree(respBody).path("error").asText(respBody);
        } catch (Exception e) {
            return respBody;
        }
    }

    private static List<String> normalizeAddresses(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (String s : in) {
            if (s == null) continue;
            String v = s.trim();
            if (!v.isEmpty() && EMAIL_RX.matcher(v).matches() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    /**
     * Minimal RFC 5545 VEVENT with floating local times — recipients see the
     * slot in their own calendar's timezone. Organizer is the executive so
     * accept/decline responses route back to them.
     */
    private String buildIcs(SendRequest req, List<String> to) {
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDateTime.parse(req.start());
            end = req.end() == null || req.end().isBlank()
                    ? start.plusMinutes(30) : LocalDateTime.parse(req.end());
        } catch (Exception e) {
            throw new IllegalArgumentException("meeting start/end must be ISO date-times (YYYY-MM-DDTHH:mm)");
        }
        if (!end.isAfter(start)) throw new IllegalArgumentException("meeting end must be after start");

        String organizer = CurrentUser.email().orElse("");
        String organizerName = CurrentUser.displayName().orElse(organizer);
        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n")
           .append("VERSION:2.0\r\n")
           .append("PRODID:-//Orphos EIP//Email Task Agent//EN\r\n")
           .append("METHOD:REQUEST\r\n")
           .append("BEGIN:VEVENT\r\n")
           .append("UID:").append(UUID.randomUUID()).append("@orphos-eip\r\n")
           .append("DTSTAMP:").append(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                   .format(Instant.now().atOffset(ZoneOffset.UTC))).append("\r\n")
           .append("DTSTART:").append(ICS_LOCAL.format(start)).append("\r\n")
           .append("DTEND:").append(ICS_LOCAL.format(end)).append("\r\n")
           .append("SUMMARY:").append(icsEscape(req.subject())).append("\r\n")
           .append("DESCRIPTION:").append(icsEscape(req.bodyText())).append("\r\n");
        if (req.location() != null && !req.location().isBlank()) {
            ics.append("LOCATION:").append(icsEscape(req.location())).append("\r\n");
        }
        if (!organizer.isBlank()) {
            ics.append("ORGANIZER;CN=").append(icsEscape(organizerName))
               .append(":mailto:").append(organizer).append("\r\n");
        }
        for (String attendee : to) {
            ics.append("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:")
               .append(attendee).append("\r\n");
        }
        ics.append("STATUS:CONFIRMED\r\n")
           .append("END:VEVENT\r\n")
           .append("END:VCALENDAR\r\n");
        return ics.toString();
    }

    private static String icsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
