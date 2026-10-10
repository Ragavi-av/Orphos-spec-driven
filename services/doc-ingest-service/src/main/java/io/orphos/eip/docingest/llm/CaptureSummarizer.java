package io.orphos.eip.docingest.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Summarises a short captured note (mobile handwriting / scribble) with the
 * tenant's configured AI provider via nl-query — the same route and usage type
 * as {@link DocumentAiEnricher}, so an admin's provider/model pin for document
 * processing applies here too. Empty when AI is disabled or the reply is
 * unusable; the caller then lets the user write the summary themselves.
 */
@Component
public class CaptureSummarizer {

    private static final Logger log = LogManager.getLogger(CaptureSummarizer.class);

    /** Admin-editable prompt key; unknown keys fall back to the bundled template. */
    static final String PROMPT_KEY = "doc-processing.capture-summary";

    private static final int MAX_EXCERPT_CHARS = 4_000;
    private static final int MAX_TITLE_CHARS   = 80;
    private static final int MAX_KEY_POINTS    = 6;
    private static final int MAX_ITEMS         = 10;  // actions / decisions each

    private static final String DEFAULT_TEMPLATE = """
            You are an executive assistant. The text below was just recognised from a
            handwritten note. Reply with ONLY a compact JSON object, no prose, no
            markdown fences.

            Schema:
            {
              "title": "a short name for the note, at most 6 words",
              "summary": "one or two sentences stating what the note is about",
              "keyPoints": ["each concrete fact, action, person, date or time in the note"],
              "actions": ["each task the note says someone has to do, as a short imperative"],
              "decisions": ["each choice or approval the note says is still to be made"]
            }

            Rules:
            - Use ONLY what the note says; never invent names, dates or details.
            - "actions" and "decisions" may be empty; never list the same item in both.
            - A decision already taken is a key point, not a decision.
            - Keep dates, times and numbers exactly as written.
            - Write every field in the SAME language as the note.
            - Recognition can garble a word; read it in context, but do not add content.

            Note:
            {{TEXT}}
            """;

    /**
     * An AI summary of a note: a suggested title, the summary, its key points, and the
     * actions and pending decisions it contains (both possibly empty).
     */
    public record Summary(String title, String summary, List<String> keyPoints,
                          List<String> actions, List<String> decisions) {}

    private final NlQueryClient nlQuery;
    private final ObjectMapper mapper = new ObjectMapper();

    public CaptureSummarizer(NlQueryClient nlQuery) {
        this.nlQuery = nlQuery;
    }

    public Optional<Summary> summarize(String tenantId, String text) {
        if (text == null || text.isBlank() || tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        String prompt = nlQuery.promptText(tenantId, PROMPT_KEY).orElse(DEFAULT_TEMPLATE)
                .replace("{{TEXT}}", excerpt(text));
        Optional<String> completion = nlQuery.complete(tenantId, prompt, DocumentAiEnricher.USAGE_TYPE);
        if (completion.isEmpty()) return Optional.empty();

        JsonNode json = parseJson(completion.get());
        if (json == null) {
            log.debug("Capture summary reply was not JSON [tenant={}]", tenantId);
            return Optional.empty();
        }
        String summary = json.path("summary").asText("").strip();
        List<String> points = strings(json.path("keyPoints"), MAX_KEY_POINTS);
        if (summary.isEmpty() && points.isEmpty()) return Optional.empty();

        List<String> decisions = strings(json.path("decisions"), MAX_ITEMS);
        List<String> actions = strings(json.path("actions"), MAX_ITEMS);
        actions.removeIf(a -> decisions.stream().anyMatch(d -> d.equalsIgnoreCase(a)));

        String title = json.path("title").asText("").strip();
        if (title.length() > MAX_TITLE_CHARS) title = title.substring(0, MAX_TITLE_CHARS);
        return Optional.of(new Summary(title, summary, points, actions, decisions));
    }

    /** Non-blank string entries of a JSON array, capped; empty when the field is absent. */
    private static List<String> strings(JsonNode array, int max) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : array) {
            String s = p.asText("").strip();
            if (!s.isEmpty() && out.size() < max) out.add(s);
        }
        return out;
    }

    /** Tolerant JSON extraction — strips markdown fences and locates the object. */
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

    private static String excerpt(String text) {
        String t = text.strip();
        return t.length() > MAX_EXCERPT_CHARS ? t.substring(0, MAX_EXCERPT_CHARS) + "…" : t;
    }
}
