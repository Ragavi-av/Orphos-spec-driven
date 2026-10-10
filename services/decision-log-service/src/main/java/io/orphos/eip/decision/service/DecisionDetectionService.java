package io.orphos.eip.decision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.core.ai.NlQueryInternalClient;
import io.orphos.eip.decision.util.DecisionLogProperties;
import io.orphos.eip.decision.util.LlmJson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Decision_Detection_Engine + Priority_Association_Algorithm (Req 3).
 *
 * <p>Two-stage, mirroring mail-insight's ClassifierChain: a deterministic
 * rule pass over bilingual (EN/JA) decision-language patterns always runs and
 * produces a baseline candidate + confidence; when the tenant's LLM is
 * reachable (via nl-query's internal completion API, usage type
 * {@code decision-detection}) it refines the classification, priority and
 * confidence. LLM failures degrade to the rule baseline — detection never
 * depends on model availability.</p>
 */
@Service
public class DecisionDetectionService {

    private static final Logger log = LogManager.getLogger(DecisionDetectionService.class);

    static final String USAGE_TYPE = "decision-detection";

    /** Strong decision-language indicators (bilingual: platform supports EN + JA). */
    private static final List<Pattern> STRONG_PATTERNS = compile(
        "\\bdecided\\b", "\\bdecision (was|has been|is) (made|taken|approved)\\b",
        "\\bapproved\\b", "\\bsigned off\\b", "\\bgreen[- ]?lit\\b", "\\bgo[- ]ahead\\b",
        "\\bwe will (proceed|move forward|adopt|discontinue|migrate)\\b",
        "\\bresolved to\\b", "\\bfinal decision\\b", "\\bratified\\b",
        "決定しました", "承認されました", "承認しました", "決議", "採択", "最終決定");

    /** Weaker signals — only count in combination. */
    private static final List<Pattern> WEAK_PATTERNS = compile(
        "\\bagreed\\b", "\\bconsensus\\b", "\\bconcluded\\b", "\\bchose\\b", "\\bselected\\b",
        "\\bwill (now )?use\\b", "\\bpolicy change\\b", "\\bbudget (approval|approved)\\b",
        "\\bproposal (accepted|passed)\\b", "合意", "選定", "方針", "決めました");

    private static final List<String> DOMAIN_KEYWORDS_IT = List.of("server", "software", "cloud", "migration",
        "security", "system", "infrastructure", "デプロイ", "システム", "セキュリティ");
    private static final List<String> DOMAIN_KEYWORDS_FINANCE = List.of("budget", "cost", "invoice", "spend",
        "procurement", "vendor", "予算", "コスト", "調達");
    private static final List<String> DOMAIN_KEYWORDS_HR = List.of("hiring", "recruit", "headcount", "employee",
        "policy", "training", "採用", "人事", "研修");

    private final NlQueryInternalClient llm;
    private final DecisionLogProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public DecisionDetectionService(NlQueryInternalClient llm, DecisionLogProperties props) {
        this.llm = llm;
        this.props = props;
    }

    /** A flagged decision candidate ready to persist. */
    public record DetectedDecision(String title, String description, String rationale, String domain,
                                   String decisionArea, String priorityLevel, double priorityScore,
                                   String classificationType, String strategicImportance,
                                   String involvedPeopleJson, String affectedAreasJson,
                                   double confidence, String detectedBy) {}

    /**
     * Analyzes one piece of source text (email summary, document title, chat
     * message) for decision indicators (Req 3.1/3.2). Empty when no decision
     * is present or confidence stays below the configured floor.
     */
    public Optional<DetectedDecision> detect(String tenantIdStr, String title, String body, String author) {
        String text = ((title == null ? "" : title) + "\n" + (body == null ? "" : body)).strip();
        if (text.isBlank()) return Optional.empty();

        double ruleConfidence = ruleConfidence(text);
        boolean llmAvailable = props.getDetection().isLlmEnabled() && llm.isEnabled();
        // No rule hit at all and no LLM to double-check → not a decision.
        if (ruleConfidence <= 0 && !llmAvailable) return Optional.empty();

        DetectedDecision baseline = ruleBaseline(title, body, author, ruleConfidence);
        if (!llmAvailable) {
            return baseline.confidence() >= props.getDetection().getMinConfidence()
                ? Optional.of(baseline) : Optional.empty();
        }

        DetectedDecision refined = llmRefine(tenantIdStr, text, baseline).orElse(baseline);
        return refined.confidence() >= props.getDetection().getMinConfidence()
            ? Optional.of(refined) : Optional.empty();
    }

    /** Deterministic pattern score — also unit-testable without any model (Req 3.5 accuracy floor). */
    double ruleConfidence(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        long strong = STRONG_PATTERNS.stream().filter(p -> p.matcher(lower).find()).count();
        long weak = WEAK_PATTERNS.stream().filter(p -> p.matcher(lower).find()).count();
        if (strong == 0 && weak == 0) return 0.0;
        double score = Math.min(0.55 + strong * 0.15 + weak * 0.05, 0.9);
        return strong > 0 ? score : Math.min(score, 0.55);
    }

    private DetectedDecision ruleBaseline(String title, String body, String author, double confidence) {
        String cleanTitle = title == null || title.isBlank()
            ? firstSentence(body) : title.strip();
        String domain = inferDomain((title + " " + body).toLowerCase(Locale.ROOT));
        String priority = confidence >= 0.8 ? "HIGH" : confidence >= 0.65 ? "MEDIUM" : "LOW";
        return new DetectedDecision(
            truncate(cleanTitle, 200),
            truncate(body == null ? "" : body.strip(), 4000),
            "Flagged by decision-language pattern analysis.",
            domain, "", priority, priorityScore(priority, confidence),
            "OPERATIONAL", "MEDIUM",
            author == null || author.isBlank() ? "[]"
                : "[{\"name\":\"" + author.replace("\"", "") + "\",\"role\":\"author\",\"participation\":\"INITIATOR\"}]",
            "[]", confidence, "RULES");
    }

    private Optional<DetectedDecision> llmRefine(String tenantIdStr, String text, DetectedDecision baseline) {
        String prompt = """
            You analyze enterprise communications to detect organizational decisions.
            Analyze the TEXT below. Respond with ONLY a JSON object, no prose:
            {
              "isDecision": true|false,
              "confidence": 0.0-1.0,
              "title": "concise decision title",
              "description": "what was decided, 1-3 sentences",
              "rationale": "why, if stated",
              "domain": "IT|FINANCE|HR|OPERATIONS|SALES|LEGAL|OTHER",
              "decisionArea": "more specific functional area, if inferable",
              "priorityLevel": "CRITICAL|HIGH|MEDIUM|LOW",
              "classificationType": "STRATEGIC|OPERATIONAL|TACTICAL|POLICY|FINANCIAL|PERSONNEL",
              "strategicImportance": "HIGH|MEDIUM|LOW",
              "involvedPeople": [{"name":"...","role":"...","participation":"DECIDER|CONTRIBUTOR|INFORMED"}],
              "affectedAreas": [{"kind":"DEPARTMENT|PERSON|POLICY","name":"..."}]
            }
            A decision is an explicit organizational choice (approval, adoption, rejection,
            budget sign-off, policy change) — not a question, plan draft, or FYI.
            The text may be English or Japanese; answer field values in the text's language
            except the enum fields which must use the exact English tokens above.

            TEXT:
            """ + truncate(text, 6000);

        try {
            Optional<String> completion = llm.complete(prompt, UUID.fromString(tenantIdStr), USAGE_TYPE);
            if (completion.isEmpty()) return Optional.empty();
            Optional<JsonNode> parsed = LlmJson.parse(completion.get());
            if (parsed.isEmpty()) return Optional.empty();
            JsonNode n = parsed.get();
            if (!n.path("isDecision").asBoolean(false)) {
                // The model overrules a weak rule hit; strong rule evidence still stands at reduced confidence.
                if (baseline.confidence() < 0.7) {
                    return Optional.of(withConfidence(baseline, 0.0));
                }
                return Optional.of(withConfidence(baseline, baseline.confidence() - 0.2));
            }
            double llmConfidence = LlmJson.clamp01(n.path("confidence").asDouble(baseline.confidence()));
            // Blend: model opinion dominates but rule evidence keeps a floor.
            double confidence = LlmJson.clamp01(0.7 * llmConfidence + 0.3 * baseline.confidence());
            String priority = enumOr(n, "priorityLevel", List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"),
                baseline.priorityLevel());
            return Optional.of(new DetectedDecision(
                truncate(LlmJson.text(n, "title", baseline.title()), 200),
                truncate(LlmJson.text(n, "description", baseline.description()), 4000),
                truncate(LlmJson.text(n, "rationale", baseline.rationale()), 2000),
                enumOr(n, "domain", List.of("IT", "FINANCE", "HR", "OPERATIONS", "SALES", "LEGAL", "OTHER"),
                    baseline.domain()),
                truncate(LlmJson.text(n, "decisionArea", baseline.decisionArea()), 120),
                priority, priorityScore(priority, confidence),
                enumOr(n, "classificationType",
                    List.of("STRATEGIC", "OPERATIONAL", "TACTICAL", "POLICY", "FINANCIAL", "PERSONNEL"),
                    baseline.classificationType()),
                enumOr(n, "strategicImportance", List.of("HIGH", "MEDIUM", "LOW"), baseline.strategicImportance()),
                arrayJson(n, "involvedPeople", baseline.involvedPeopleJson()),
                arrayJson(n, "affectedAreas", baseline.affectedAreasJson()),
                confidence, "LLM"));
        } catch (Exception e) {
            log.debug("LLM detection refinement failed, using rule baseline: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Priority_Association_Algorithm (Req 3.3): a 0-100 criticality score from
     * the assigned level, sharpened by detection confidence — aligned with the
     * EPQ scale priority-engine uses so the two rankings are comparable.
     */
    static double priorityScore(String priorityLevel, double confidence) {
        double base = switch (priorityLevel == null ? "" : priorityLevel) {
            case "CRITICAL" -> 90;
            case "HIGH" -> 72;
            case "MEDIUM" -> 50;
            case "LOW" -> 25;
            default -> 40;
        };
        return Math.round((base * (0.7 + 0.3 * LlmJson.clamp01(confidence))) * 10.0) / 10.0;
    }

    private static DetectedDecision withConfidence(DetectedDecision d, double confidence) {
        return new DetectedDecision(d.title(), d.description(), d.rationale(), d.domain(), d.decisionArea(),
            d.priorityLevel(), d.priorityScore(), d.classificationType(), d.strategicImportance(),
            d.involvedPeopleJson(), d.affectedAreasJson(), Math.max(0, confidence), d.detectedBy());
    }

    private static String inferDomain(String lower) {
        if (containsAny(lower, DOMAIN_KEYWORDS_FINANCE)) return "FINANCE";
        if (containsAny(lower, DOMAIN_KEYWORDS_HR)) return "HR";
        if (containsAny(lower, DOMAIN_KEYWORDS_IT)) return "IT";
        return "OTHER";
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        return needles.stream().anyMatch(haystack::contains);
    }

    private String arrayJson(JsonNode n, String field, String fallback) {
        JsonNode arr = n.path(field);
        if (!arr.isArray() || arr.isEmpty()) return fallback;
        try {
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String enumOr(JsonNode n, String field, List<String> allowed, String fallback) {
        String v = n.path(field).asText("").toUpperCase(Locale.ROOT);
        return allowed.contains(v) ? v : fallback;
    }

    private static String firstSentence(String body) {
        if (body == null || body.isBlank()) return "Untitled decision";
        String s = body.strip();
        int cut = s.indexOf('\n');
        if (cut < 0 || cut > 140) cut = Math.min(s.length(), 140);
        return s.substring(0, cut);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static List<Pattern> compile(String... patterns) {
        List<Pattern> out = new ArrayList<>();
        for (String p : patterns) out.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
        return out;
    }
}
