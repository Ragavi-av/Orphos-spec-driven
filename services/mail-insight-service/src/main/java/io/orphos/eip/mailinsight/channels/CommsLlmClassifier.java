package io.orphos.eip.mailinsight.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.mailinsight.graph.GraphItem;
import io.orphos.eip.mailinsight.inference.Classification;
import io.orphos.eip.mailinsight.inference.NlQueryClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Best-effort LLM enrichment for communication-channel messages (Slack/Teams/…).
 * Mirrors {@link io.orphos.eip.mailinsight.inference.LlmClassifier} but uses a
 * distinct, independently admin-editable prompt (key
 * {@code comms-classification.main}) and usage tag ({@code comms-classification})
 * so the "Communications AI" tab can tune channel triage separately from email.
 * Falls back to the rule baseline on any failure.
 */
@Component
public class CommsLlmClassifier {

    private static final Logger log = LogManager.getLogger(CommsLlmClassifier.class);

    /** Prompt key in nl-query's admin-editable store (Administration → AI → Communications AI). */
    static final String PROMPT_KEY = "comms-classification.main";
    /** Usage tag so nl-query honours the tenant admin's per-function provider/model assignment. */
    static final String USAGE_TYPE = "comms-classification";

    private static final String DEFAULT_TEMPLATE = """
            You are an executive assistant triaging a chat message from a team communication
            channel (e.g. Slack or Microsoft Teams) for a CXO. Classify it and reply with ONLY a
            compact JSON object, no prose, no markdown fences.

            Schema:
            {
              "kind": "TASK" | "NOTIFICATION",
              "priority": "CRITICAL" | "HIGH" | "MEDIUM" | "LOW",
              "criticality": "ALERT" | "WARNING" | "INFORMATION",
              "dueDate": "ISO-8601 datetime or empty string",
              "summary": "one concise sentence, no PII beyond the sender name",
              "rationale": "one short sentence explaining the priority"
            }

            Rules:
            - TASK = the reader must do or decide something (a direct ask, an @mention, an approval).
              NOTIFICATION = informational/awareness (status updates, FYIs, automated posts).
            - ALERT = critical/urgent/outage/security. WARNING = reminder/overdue/at-risk. INFORMATION = routine.
            - Chat is terse and informal — weigh direct asks and mentions higher than casual chatter.
            - The "kind", "priority" and "criticality" values MUST stay in English exactly as in the schema.
            - Write "summary" and "rationale" in the SAME language as the message.

            A deterministic pre-classification (refine it, don't blindly copy):
            kind={{BASE_KIND}}, priority={{BASE_PRIORITY}}, criticality={{BASE_CRITICALITY}}.

            Message:
            - channel type: {{ITEM_TYPE}}
            - subject/first line: {{SUBJECT}}
            - from: {{FROM}}
            - text: {{PREVIEW}}
            """;

    private final NlQueryClient nlQuery;
    private final ObjectMapper mapper = new ObjectMapper();

    public CommsLlmClassifier(NlQueryClient nlQuery) {
        this.nlQuery = nlQuery;
    }

    public boolean isAvailable() {
        return nlQuery.isEnabled();
    }

    public Classification enrich(GraphItem item, Classification base) {
        Optional<String> completion = nlQuery.complete(buildPrompt(item, base), USAGE_TYPE);
        if (completion.isEmpty()) return base;
        try {
            JsonNode json = parseJson(completion.get());
            if (json == null) return base;

            String kind = upper(json.path("kind").asText(base.kind()));
            String priority = upper(json.path("priority").asText(base.priority()));
            String criticality = upper(json.path("criticality").asText(base.criticality()));
            String summary = json.path("summary").asText(base.summary());
            String rationale = json.path("rationale").asText(base.rationale());
            Instant due = parseInstant(json.path("dueDate").asText(""));
            if (due == null) due = base.dueDate();

            if (!isValidKind(kind)) kind = base.kind();
            if (!isValidPriority(priority)) priority = base.priority();
            if (!isValidCriticality(criticality)) criticality = base.criticality();

            return new Classification(kind, priority, criticality,
                    base.score(), base.scoreBreakdown(), rationale, due, truncate(summary), "LLM");
        } catch (Exception e) {
            log.debug("Comms LLM enrichment parse failed, keeping rule baseline: {}", e.getMessage());
            return base;
        }
    }

    private String buildPrompt(GraphItem item, Classification base) {
        String template = nlQuery.promptText(PROMPT_KEY).orElse(DEFAULT_TEMPLATE);
        return template
                .replace("{{BASE_KIND}}",        nullSafe(base.kind()))
                .replace("{{BASE_PRIORITY}}",    nullSafe(base.priority()))
                .replace("{{BASE_CRITICALITY}}", nullSafe(base.criticality()))
                .replace("{{ITEM_TYPE}}",        nullSafe(item.sourceType()).toLowerCase())
                .replace("{{SUBJECT}}",          nullSafe(item.subject()))
                .replace("{{FROM}}",             nullSafe(item.senderDisplay()))
                .replace("{{PREVIEW}}",          truncate(nullSafe(item.bodyPreview())));
    }

    private JsonNode parseJson(String raw) {
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return mapper.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isValidKind(String v) {
        return "TASK".equals(v) || "NOTIFICATION".equals(v);
    }

    private static boolean isValidPriority(String v) {
        return "CRITICAL".equals(v) || "HIGH".equals(v) || "MEDIUM".equals(v) || "LOW".equals(v);
    }

    private static boolean isValidCriticality(String v) {
        return "ALERT".equals(v) || "WARNING".equals(v) || "INFORMATION".equals(v);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
