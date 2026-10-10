package io.orphos.eip.nlquery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.nlquery.component.OkgInsightsClient;
import io.orphos.eip.nlquery.component.provider.ChatProvider;
import io.orphos.eip.nlquery.util.TrustProperties;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import io.orphos.eip.nlquery.component.PromptConfigStore;
import io.orphos.eip.nlquery.vo.TrustSignal.Grounding;
import io.orphos.eip.nlquery.vo.TrustSignal.Source;
import io.orphos.eip.nlquery.vo.TrustSignal.Verification;
import io.orphos.eip.nlquery.vo.TrustSignal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes the {@link TrustSignal} for a generated answer. Runs after the
 * briefing is produced and combines three independent signals:
 *
 * <ol>
 *   <li><b>Grounding band</b> — derived deterministically from the RAG best
 *       score and whether live enterprise data was present.</li>
 *   <li><b>Groundedness self-check</b> — one cheap LLM pass asking whether every
 *       claim in the answer follows from the supplied context (admin-editable
 *       prompt {@code trust-verify.main}).</li>
 *   <li><b>OWL cross-check</b> — compares the answer against the reasoner's
 *       deterministic facts (via {@link OkgInsightsClient}); a contradiction is
 *       the strongest possible trust signal because those facts are proven, not
 *       generated.</li>
 * </ol>
 *
 * <p>Every stage is best-effort and fails open: a stage that errors downgrades
 * verification to {@link Verification#SKIPPED} rather than failing the answer.</p>
 */
@Service
public class TrustEvaluator {

    private static final Logger log = LogManager.getLogger(TrustEvaluator.class);

    private final TrustProperties props;
    private final PromptConfigStore promptStore;
    private final OkgInsightsClient okgClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TrustEvaluator(TrustProperties props, PromptConfigStore promptStore, OkgInsightsClient okgClient) {
        this.props = props;
        this.promptStore = promptStore;
        this.okgClient = okgClient;
    }

    // Base confidence per grounding band, before verification/contradiction adjustments.
    private static final double BASE_GROUNDED    = 0.85;
    private static final double BASE_PARTIAL     = 0.60;
    private static final double BASE_SYNTHESIZED = 0.30;

    private static final String DEFAULT_VERIFY_PROMPT = """
            You are a strict fact-checker. Below is CONTEXT (enterprise data) and an ANSWER written from it.
            Decide whether EVERY factual claim in the ANSWER is supported by the CONTEXT.
            General enterprise best-practice advice that makes no specific factual claim about this organisation counts as supported.
            Reply with ONLY a compact JSON object, no prose, no markdown fences:
            {"verified": true|false, "unsupported": ["short phrase for each unsupported claim"]}

            CONTEXT:
            {{CONTEXT}}

            ANSWER:
            {{ANSWER}}
            """;

    private static final String DEFAULT_CONTRADICTION_PROMPT = """
            Below are VERIFIED FACTS (deterministically computed, always correct) and an ANSWER.
            List any statement in the ANSWER that directly contradicts a VERIFIED FACT.
            Reply with ONLY a compact JSON object, no prose, no markdown fences:
            {"contradictions": ["short phrase for each contradiction"]}

            VERIFIED FACTS:
            {{FACTS}}

            ANSWER:
            {{ANSWER}}
            """;

    /**
     * Evaluates trust for a completed answer. {@code provider}/{@code overrides}
     * are the same ones that produced the answer, so the self-check runs on the
     * tenant's chosen model. Returns {@code null} only when the whole layer is
     * disabled (caller then omits the trust envelope).
     */
    public TrustSignal evaluate(UUID tenant, String answer,
                                double bestScore, boolean hasRag, boolean hasLive,
                                String ragContext, String liveContext, List<Source> sources,
                                ChatProvider provider, ProviderOverrides overrides) {
        if (!props.isEnabled()) return null;

        Grounding grounding = classifyGrounding(bestScore, hasRag, hasLive);
        List<String> caveats = new ArrayList<>();

        // ── Self-check (skipped for SYNTHESIZED — there is no context to verify against) ──
        Verification verification = Verification.SKIPPED;
        if (props.isSelfCheckEnabled() && grounding != Grounding.SYNTHESIZED) {
            verification = runSelfCheck(tenant, answer, ragContext, liveContext, provider, overrides, caveats);
        }

        // ── OWL deterministic cross-check ──
        int contradictions = runOwlCrossCheck(tenant, answer, provider, overrides, caveats);

        double confidence = computeConfidence(grounding, verification, caveats.size(), contradictions);
        return new TrustSignal(grounding, round(confidence), verification, List.copyOf(caveats), sources);
    }

    // ── Grounding band ────────────────────────────────────────────────────────

    private Grounding classifyGrounding(double bestScore, boolean hasRag, boolean hasLive) {
        if (!hasRag && !hasLive) return Grounding.SYNTHESIZED;
        if (bestScore >= props.getGroundedScoreThreshold() || hasLive) return Grounding.GROUNDED;
        return Grounding.PARTIAL;
    }

    // ── Groundedness self-check ─────────────────────────────────────────────────

    private Verification runSelfCheck(UUID tenant, String answer, String ragContext, String liveContext,
                                      ChatProvider provider, ProviderOverrides overrides, List<String> caveats) {
        String context = ((ragContext == null ? "" : ragContext) + "\n" + (liveContext == null ? "" : liveContext)).strip();
        if (context.isEmpty()) return Verification.SKIPPED;
        try {
            String tpl = promptStore.text(tenant, "trust-verify.main");
            if (tpl == null || tpl.isBlank()) tpl = DEFAULT_VERIFY_PROMPT;
            String prompt = tpl.replace("{{CONTEXT}}", clip(context, 6000)).replace("{{ANSWER}}", clip(answer, 4000));
            JsonNode json = parseJson(provider.chat(prompt, overrides));
            if (json == null) return Verification.SKIPPED;
            boolean verified = json.path("verified").asBoolean(true);
            List<String> unsupported = stringList(json.get("unsupported"));
            if (!verified || !unsupported.isEmpty()) {
                unsupported.forEach(u -> caveats.add("Unsupported claim: " + u));
                return Verification.FLAGGED;
            }
            return Verification.PASSED;
        } catch (Exception e) {
            log.debug("Groundedness self-check skipped: {}", e.getMessage());
            return Verification.SKIPPED;
        }
    }

    // ── OWL deterministic cross-check ────────────────────────────────────────────

    private int runOwlCrossCheck(UUID tenant, String answer, ChatProvider provider,
                                 ProviderOverrides overrides, List<String> caveats) {
        OkgInsightsClient.DerivedFacts facts = okgClient.fetch();
        if (facts.isEmpty()) return 0;
        try {
            String tpl = promptStore.text(tenant, "trust-contradiction.main");
            if (tpl == null || tpl.isBlank()) tpl = DEFAULT_CONTRADICTION_PROMPT;
            String prompt = tpl.replace("{{FACTS}}", String.join("; ", facts.statements()))
                               .replace("{{ANSWER}}", clip(answer, 4000));
            JsonNode json = parseJson(provider.chat(prompt, overrides));
            if (json == null) return 0;
            List<String> contradictions = stringList(json.get("contradictions"));
            contradictions.forEach(c -> caveats.add("Contradicts verified fact: " + c));
            return contradictions.size();
        } catch (Exception e) {
            log.debug("OWL cross-check comparison skipped: {}", e.getMessage());
            return 0;
        }
    }

    // ── Confidence ───────────────────────────────────────────────────────────────

    private double computeConfidence(Grounding grounding, Verification verification,
                                     int caveatCount, int contradictions) {
        double c = switch (grounding) {
            case GROUNDED -> BASE_GROUNDED;
            case PARTIAL -> BASE_PARTIAL;
            case SYNTHESIZED -> BASE_SYNTHESIZED;
        };
        switch (verification) {
            case PASSED -> c += 0.10;
            case FLAGGED -> c -= 0.25;
            case SKIPPED -> { /* no adjustment */ }
        }
        // Each contradiction with proven facts is decisive — penalise hard.
        c -= 0.20 * contradictions;
        // A pile of unsupported claims beyond the first erodes confidence further.
        c -= 0.05 * Math.max(0, caveatCount - contradictions - 1);
        return Math.max(0.05, Math.min(0.98, c));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static final Pattern JSON_OBJECT = Pattern.compile("(?s)\\{.*\\}");

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = JSON_OBJECT.matcher(raw);
        if (!m.find()) return null;
        try {
            return mapper.readTree(m.group());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                String s = n.asText("").trim();
                if (!s.isEmpty()) out.add(s);
            });
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
