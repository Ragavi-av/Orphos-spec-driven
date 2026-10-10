package io.orphos.eip.nlquery.service;

import io.orphos.eip.common.access.PersonaPolicy;
import io.orphos.eip.common.lang.LanguageDetector;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.nlquery.component.NlQueryCache;
import io.orphos.eip.nlquery.repository.ArcadeDbContextBuilder;
import io.orphos.eip.nlquery.vo.AiUsageEvent;
import io.orphos.eip.nlquery.service.AiUsageRecorder;
import io.orphos.eip.nlquery.component.provider.ChatProvider;
import io.orphos.eip.nlquery.component.provider.ChatProviderRegistry;
import io.orphos.eip.nlquery.util.CurrentUser;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import io.orphos.eip.nlquery.component.PromptConfigStore;
import io.orphos.eip.nlquery.component.RetrievalFederator;
import io.orphos.eip.nlquery.service.TrustEvaluator;
import io.orphos.eip.nlquery.vo.TrustSignal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class NlQueryService {

    private static final Logger log = LogManager.getLogger(NlQueryService.class);

    // ── Bilingual (JA+EN) routing ──────────────────────────────────────────────
    // Among the in-house models, Qwen2.5 ("inhouse-hi") handles Japanese far
    // better than Granite-2B / Phi-3.5. When a question is Japanese and the caller
    // landed on a weak-JA in-house model, transparently route to Qwen. An explicit
    // external choice (Gemini/Claude) — already strong in JA — is left untouched.
    private static final String JA_PREFERRED_PROVIDER = "inhouse-hi";
    private static final Set<String> WEAK_JA_PROVIDERS = Set.of("inhouse", "inhouse-phi");

    // Built-in Japanese response directive, appended at the very end of the briefing
    // prompt (highest recency) so the model answers in Japanese regardless of the
    // English system prompt. Admin-overridable via prompt key briefing-frame.language.ja.
    // NOTE: phrased as a plain imperative WITHOUT meta-preamble (no "the above
    // instructions are in English…"). Weak local models (qwen2.5:1.5b) tend to
    // echo such meta-text verbatim into the answer; a clean imperative is far
    // less likely to be copied. cleanBriefing() additionally strips any echo as
    // a hard guarantee. Admin-overridable via prompt key briefing-frame.language.ja.
    private static final String JA_RESPONSE_DIRECTIVE =
            "回答は必ず自然な日本語で記述してください。"
          + "見出しや箇条書きは使わず、流れるような文章で書いてください。"
          + "ただし ===FOLLOWUPS=== の区切り行と Q1:/Q2:/Q3: のラベルは英語表記のままにし、"
          + "質問文のみ日本語にしてください。";

    private final RetrievalFederator federator;
    private final ChatProviderRegistry providerRegistry;
    private final ArcadeDbContextBuilder contextBuilder;
    private final AiUsageRecorder usageRecorder;
    private final PromptConfigStore promptStore;
    private final TrustEvaluator trustEvaluator;
    private final NlQueryCache cache;
    private final ExecutorService pipelineExecutor;

    /**
     * Whether to run the pre-flight scope classifier. It costs a full extra model
     * round trip per question; RULE 0 in the briefing prompt and the OUT_OF_SCOPE
     * parse in {@link #parseResponse} remain in place either way, so switching it
     * off trades a stricter provider-independent gate for lower latency.
     */
    private final boolean scopeGateEnabled;

    public NlQueryService(RetrievalFederator federator,
                          ChatProviderRegistry providerRegistry,
                          ArcadeDbContextBuilder contextBuilder,
                          AiUsageRecorder usageRecorder,
                          PromptConfigStore promptStore,
                          TrustEvaluator trustEvaluator,
                          NlQueryCache cache,
                          @Qualifier("nlQueryPipelineExecutor") ExecutorService pipelineExecutor,
                          @Value("${nlquery.scope-gate.enabled:true}") boolean scopeGateEnabled) {
        this.federator = federator;
        this.providerRegistry = providerRegistry;
        this.contextBuilder = contextBuilder;
        this.usageRecorder = usageRecorder;
        this.promptStore = promptStore;
        this.trustEvaluator = trustEvaluator;
        this.cache = cache;
        this.pipelineExecutor = pipelineExecutor;
        this.scopeGateEnabled = scopeGateEnabled;
    }

    public NlQueryResult answer(String rawQuestion) {
        return answer(rawQuestion, null, ProviderOverrides.empty());
    }

    public NlQueryResult answer(String rawQuestion, String providerId, ProviderOverrides overrides) {
        // Strip JS 'undefined' artifacts that appear when optional fields are null in frontend
        String question = rawQuestion
                .replaceAll("(?i)\\bundefined\\b\\.?\\s*", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (question.isEmpty()) question = rawQuestion.trim();

        ProviderOverrides safeOverrides = overrides == null ? ProviderOverrides.empty() : overrides;

        // Detect the question's language once (bilingual JA+EN) and route Japanese
        // questions to a JA-capable model before any model call (scope gate included).
        String lang = LanguageDetector.detect(question);
        ChatProvider provider = routeForLanguage(providerRegistry.resolve(providerId), lang);
        UUID tenant = TenantContextHolder.requireTenantId();

        // Read the persona roles on the request thread — they come from a
        // thread-local and are needed both for the cache key and (below) for the
        // live-context lookup.
        Set<String> roles = PersonaPolicy.currentRoles();
        String resolvedModel = provider.resolveModelName(safeOverrides);

        // Answer cache — checked before anything expensive, including the scope
        // gate. A repeat of the same question by the same persona on the same
        // model returns immediately instead of re-running three model calls.
        String cacheKey = NlQueryCache.answerKey(
                tenant, roles, provider.id(), resolvedModel, lang, question);
        NlQueryResult cached = cache.lookupAnswer(cacheKey);
        if (cached != null) {
            log.info("Answer served from cache via {} — '{}'", provider.id(), question);
            return cached;
        }

        // Hard scope gate — runs BEFORE retrieval and the briefing call. Small local
        // models (e.g. 1.5-2B) routinely ignore the scope rule embedded in the long
        // briefing prompt, so we first ask a short single-word classification question
        // (a task small models handle reliably) and decline immediately if the question
        // is off-topic. This is the provider-independent backstop to RULE 0.
        if (!isInScope(tenant, question, provider, safeOverrides, lang)) {
            log.info("Scope gate: rejected out-of-scope question via {} — '{}'", provider.id(), question);
            return new NlQueryResult(outOfScopeMessage(tenant), List.of(), provider.id(), resolvedModel);
        }

        // Retrieval and the live-context build are independent I/O — the Qdrant
        // fan-out runs on a virtual thread while this thread queries ArcadeDB.
        // Retrieval takes its tenant explicitly and touches no thread-local, which
        // is what makes it safe to move off the request thread; buildLiveContext()
        // does read thread-locals, so it stays here.
        final String questionForRetrieval = question;
        CompletableFuture<RetrievalFederator.FederatedContext> retrievalFuture =
                CompletableFuture.supplyAsync(
                        () -> federator.retrieve(questionForRetrieval, tenant.toString(), lang),
                        pipelineExecutor)
                .exceptionally(e -> {
                    log.warn("Federated retrieval failed, answering without vector context: {}", e.getMessage());
                    return RetrievalFederator.FederatedContext.empty();
                });

        // Live context is identical for every question the same persona asks, and
        // costs ~15 sequential ArcadeDB queries to build — cache it briefly.
        String liveContext = cache.liveContext(tenant, roles, contextBuilder::buildLiveContext);
        RetrievalFederator.FederatedContext retrieval = retrievalFuture.join();

        log.info("Federated RAG: {} chunks matched (best score: {}) across {}, ragUsed={}, lang={}",
                retrieval.chunkCount(), String.format("%.3f", retrieval.bestScore()),
                retrieval.perSource(), retrieval.hasContext(), lang);

        String basePrompt = buildPrompt(tenant, question, retrieval.context(), liveContext, lang);

        // Per-provider prompt nudge — sourced from the editable prompt store
        // (provider-suffix.<id>), falling back to the provider's built-in default.
        String suffix = promptStore.text(tenant, "provider-suffix." + provider.id());
        if (suffix == null || suffix.isBlank()) suffix = provider.tuningSuffix();
        String prompt = (suffix == null || suffix.isBlank())
                ? basePrompt
                : basePrompt + "\n\nMODEL-SPECIFIC GUIDANCE:\n" + suffix + "\n";

        String  tenantId  = tenant.toString();
        // Store the STABLE Keycloak subject (UUID), not the display name: the
        // self-serve /me/usage endpoint scopes history by sub, and the admin
        // query-history view resolves sub → display name via Keycloak. Storing
        // the display name here previously left /me/usage matching nothing.
        String  userSub   = CurrentUser.sub().orElse("anonymous");
        String  modelName = resolvedModel;
        long    startNs   = System.nanoTime();

        // Prompt size is logged at INFO deliberately: on CPU-only inference the
        // prefill of a large prompt, not the generated output, is what dominates
        // answer latency — so this number is the first thing to look at when an
        // answer is slow.
        log.info("Sending prompt to LLM ({} chars) via provider '{}' (model {}) tenant {}",
                prompt.length(), provider.id(), modelName, tenantId);

        try {
            String raw = provider.chat(prompt, safeOverrides);
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.info("LLM response ({} chars raw) from '{}' in {} ms", raw.length(), provider.id(), latencyMs);

            // Capture tenantId here (caller thread) so the @Async record() can
            // write it even though TenantContextHolder may not propagate across
            // the async boundary by default.
            usageRecorder.record(AiUsageEvent.success(
                    tenantId, userSub, provider.id(), modelName,
                    prompt.length(), raw == null ? 0 : raw.length(), latencyMs));

            NlQueryResult parsed = parseResponse(raw, provider.id(), modelName, tenant);
            // Attach a trust signal to genuine briefings only — an out-of-scope
            // decline carries none. Best-effort: evaluate() fails open and the
            // answer is returned regardless.
            if (raw != null && !isOutOfScope(raw)) {
                boolean hasLive = liveContext != null && liveContext.strip().length() > 40;
                List<TrustSignal.Source> sources = retrieval.sources().stream()
                        .map(s -> new TrustSignal.Source(
                                s.type() != null ? s.type() : s.label(), s.id(), round3(s.score())))
                        .collect(Collectors.toList());
                TrustSignal trust = trustEvaluator.evaluate(
                        tenant, parsed.answer(), retrieval.bestScore(),
                        retrieval.hasContext(), hasLive, retrieval.context(), liveContext,
                        sources, provider, safeOverrides);
                if (trust != null) parsed = parsed.withTrust(trust);
            }
            cache.storeAnswer(cacheKey, parsed);
            return parsed;
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.error("LLM inference failed via {} ({}): {}", provider.id(), modelName, e.getMessage());

            usageRecorder.record(AiUsageEvent.failure(
                    tenantId, userSub, provider.id(), modelName,
                    prompt.length(), latencyMs, e.getClass().getSimpleName()));

            throw new RuntimeException("LLM unavailable (" + provider.id() + ") — " + e.getMessage(), e);
        }
    }

    // ── Language routing ────────────────────────────────────────────────────────

    /**
     * Swaps a weak-JA in-house provider for a JA-capable one when the question is
     * Japanese. Leaves non-Japanese questions and any explicitly-chosen external
     * provider untouched. Falls back to the original provider when the preferred
     * one is absent or unavailable.
     */
    private ChatProvider routeForLanguage(ChatProvider provider, String lang) {
        if (!"ja".equals(lang) || !WEAK_JA_PROVIDERS.contains(provider.id())) {
            return provider;
        }
        ChatProvider preferred = providerRegistry.find(JA_PREFERRED_PROVIDER);
        if (preferred != null && preferred.isAvailable(ProviderOverrides.empty())) {
            log.info("Routing Japanese question from '{}' to '{}' (stronger JA support)",
                    provider.id(), preferred.id());
            return preferred;
        }
        return provider;
    }

    // ── Scope gate ─────────────────────────────────────────────────────────────

    private static final Pattern CLS_IRRELEVANT = Pattern.compile("\\bIRRELEVANT\\b");
    private static final Pattern CLS_RELEVANT   = Pattern.compile("\\bRELEVANT\\b");

    /**
     * Provider-independent relevance check. Asks the model a single, focused
     * classification question — a task even small local models follow reliably,
     * unlike the multi-rule briefing prompt. Returns true (in scope) when the
     * verdict is unclear or the classifier errors, so a flaky classification never
     * blocks a legitimate enterprise question (fail open).
     */
    private boolean isInScope(UUID tenant, String question, ChatProvider provider,
                              ProviderOverrides overrides, String lang) {
        // Operator switch — skipping the classifier removes one full model round
        // trip per question. RULE 0 in the briefing prompt still applies.
        if (!scopeGateEnabled) return true;
        // Classifier prompt is admin-editable (scope-classifier.main, with a
        // {{QUESTION}} placeholder). If none is configured, fail open — never
        // block a question just because the classifier text is missing.
        String tpl = promptStore.text(tenant, "scope-classifier.main");
        if (tpl == null || tpl.isBlank()) return true;
        String prompt = tpl.replace("{{QUESTION}}", question);
        // For Japanese input, remind the (English-prompted) classifier to still
        // answer with the English verdict token so the RELEVANT/IRRELEVANT parse holds.
        if ("ja".equals(lang)) {
            prompt += "\n\n(The question may be written in Japanese. Still reply with EXACTLY ONE "
                    + "English word: RELEVANT or IRRELEVANT.)";
        }
        try {
            String verdict = provider.chat(prompt, overrides);
            if (verdict == null || verdict.isBlank()) return true;   // fail open
            String v = verdict.toUpperCase();
            boolean relevant   = CLS_RELEVANT.matcher(v).find();
            boolean irrelevant = CLS_IRRELEVANT.matcher(v).find();
            // Fail OPEN by design: block only when the verdict is UNAMBIGUOUSLY
            // irrelevant. A hedged/verbose answer that mentions both words (e.g.
            // "this is not irrelevant, it is relevant") must never block a
            // legitimate enterprise question. The briefing prompt's RULE 0 is the
            // backstop for anything that slips through here.
            if (irrelevant && !relevant) return false;               // clearly off-topic
            return true;                                             // relevant / both / ambiguous → fail open
        } catch (Exception e) {
            log.warn("Scope classifier failed via {}, allowing question through: {}",
                    provider.id(), e.getMessage());
            return true;                                             // fail open
        }
    }

    // ── Prompt builder ───────────────────────────────────────────────────────

    private String buildPrompt(UUID tenant, String question, String ragContext, String liveContext, String lang) {
        StringBuilder sb = new StringBuilder();

        // Ordered, admin-editable briefing rule fragments (intro, RULE 0..6,
        // example). Adding/removing/reordering rows in the LLM Prompts tab
        // changes the assembled prompt here.
        String rules = promptStore.orderedByKind(tenant, "briefing").stream()
                .map(PromptConfigStore.PromptRow::content)
                .map(s -> s == null ? "" : s.strip())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n\n"));
        if (!rules.isEmpty()) sb.append(rules).append("\n\n");

        boolean hasRag  = ragContext  != null && !ragContext.isBlank();
        boolean hasLive = liveContext != null && !liveContext.isBlank();

        // Structural frame — headers/placeholders are editable, but the
        // conditional inclusion logic stays here in code.
        if (hasRag) {
            append(sb, frame(tenant, "briefing-frame.background").replace("{{RAG_CONTEXT}}", ragContext));
        }
        if (hasLive) {
            append(sb, frame(tenant, "briefing-frame.live").replace("{{LIVE_CONTEXT}}", liveContext));
        }
        if (!hasRag && !hasLive) {
            append(sb, frame(tenant, "briefing-frame.nodata"));
        }
        append(sb, frame(tenant, "briefing-frame.question").replace("{{QUESTION}}", question));
        sb.append(frame(tenant, "briefing-frame.closing"));

        // Bilingual: for a Japanese question, append a response-language directive
        // LAST (highest recency) so the model writes in Japanese despite the English
        // system prompt. Admin-overridable via briefing-frame.language.ja; the
        // English path is left byte-for-byte unchanged (nothing appended).
        if ("ja".equals(lang)) {
            String directive = frame(tenant, "briefing-frame.language.ja");
            if (directive.isEmpty()) directive = JA_RESPONSE_DIRECTIVE;
            sb.append("\n\n").append(directive);
        }

        // Prompt composition at INFO: prefill of the prompt is the dominant cost
        // on CPU-only inference, so knowing which of the three inputs is bloating
        // it is what makes the latency tunable rather than guesswork.
        log.info("Prompt composition: rules={} chars, rag={} chars, live={} chars, total={} chars",
                rules.length(),
                hasRag ? ragContext.length() : 0,
                hasLive ? liveContext.length() : 0,
                sb.length());
        return sb.toString();
    }

    /** Active content for a key, trimmed; empty string when unset. */
    private String frame(UUID tenant, String key) {
        String s = promptStore.text(tenant, key);
        return s == null ? "" : s.strip();
    }

    private static void append(StringBuilder sb, String block) {
        if (block != null && !block.isEmpty()) sb.append(block).append("\n\n");
    }

    // ── Response parser — splits LLM output into briefing + follow-up questions ─

    private static final String   FOLLOWUP_DELIMITER = "===FOLLOWUPS===";
    // Capture each Q1/Q2/Q3 question, stopping at the next Q or end of text, so it
    // works whether the model put them on separate lines or one line.
    private static final Pattern  FOLLOWUP_LINE      =
            Pattern.compile("(?is)Q\\s*[123]\\s*[:.]\\s*(.+?)(?=\\s*Q\\s*[123]\\s*[:.]|$)");
    // Weak models routinely omit the ===FOLLOWUPS=== delimiter and instead emit a
    // header like "FOLLOW-UP QUESTIONS —", or jump straight to "Q1:". Recognise any
    // of these as the boundary so the questions still land in the suggestion chips.
    private static final Pattern[] FOLLOWUP_BOUNDARIES = {
            Pattern.compile("(?is)={2,}\\s*FOLLOW\\s*-?\\s*UPS?\\s*={2,}"),
            Pattern.compile("(?is)FOLLOW[\\s-]*UP\\s*QUESTIONS?\\s*[—\\-–:]*"),
            Pattern.compile("(?im)^\\s*FOLLOWUPS?\\s*[:—\\-–]")
    };
    private static final Pattern FIRST_FOLLOWUP_Q =
            Pattern.compile("(?im)^\\s*Q\\s*1\\s*[:.]");
    // Weak models echo the appended "MODEL-SPECIFIC GUIDANCE" block and then
    // re-answer; cut everything from that header onward.
    private static final Pattern TRAILING_GUIDANCE =
            Pattern.compile("(?is)\\n?\\s*MODEL[\\s-]*SPECIFIC\\s*GUIDANCE\\s*[:：].*$");
    // Scaffolding labels a weak model copies from the format rules ("WHAT —",
    // "WHY —", "WHO —", "PART 1 — …") instead of writing flowing prose. Stripped
    // at line starts only, so genuine prose is left untouched.
    private static final Pattern SCAFFOLD_LABEL =
            Pattern.compile("(?im)^\\s*(?:PART\\s*\\d+[^\\n:—–-]*|WHAT|WHY|WHO|EXECUTIVE\\s+BRIEFING|BRIEFING)\\s*[—–:-]\\s*");

    // Sentinel the LLM returns (per Rule 0) when a question is outside the
    // enterprise-operations scope — e.g. general-knowledge or trivia questions.
    private static final String OUT_OF_SCOPE_MARKER  = "OUT_OF_SCOPE";
    private static final String OUT_OF_SCOPE_MESSAGE =
            "I can only help with questions about your organisation's enterprise operations — "
          + "such as incidents, decisions, IT systems, costs, SLAs, alerts, budgets, and related "
          + "governance or risk matters. Please ask something within that scope.";

    // Only match unambiguous Java Map / raw-ID artifacts — avoid over-stripping prose
    // Instruction echo. Larger instruction-tuned models (observed on
    // gemma-4-31b-it) open by restating the prompt's own rule list — "Role:",
    // "Task:", "Rule 0 (Scope Guard): …" — before writing the briefing. Only the
    // bullet marker was being stripped, so the rules survived as plain lines at
    // the top of every answer, making each reply look identical regardless of the
    // question. Matched at line starts only, so prose is left untouched.
    private static final Pattern ECHOED_INSTRUCTION = Pattern.compile(
            "(?im)^[ \\t]*(?:Role|Task|Rule\\s*\\d+)\\b[^\\n]*(?:\\R|$)");
    private static final Pattern JAVA_MAP_BLOCK = Pattern.compile("\\{(?:[^}]*=[^}]*){1,}\\}");
    private static final Pattern JAVA_KEY_VALUE = Pattern.compile("(?<![\\w])\\w{2,20}=[A-Z0-9_]{2,30}(?![\\w])");
    private static final Pattern RAW_ID         = Pattern.compile("\\b[a-z]{2,4}-\\d{3,}\\b");
    private static final Pattern HASH_HEADER    = Pattern.compile("(?m)^#{1,3}\\s+.*$");
    private static final Pattern BARE_BULLET    = Pattern.compile("(?m)^[ \\t]*[-*][ \\t]+");

    // Weak JA-capable models (qwen2.5:1.5b) sometimes echo the appended language
    // directive at the very START of the answer instead of silently obeying it.
    // This strips a leading run of directive sentences — each clause is optional
    // so it tolerates partial/verbatim echoes of both the current directive and
    // the older "重要 — 言語: …" wording. Anchored at ^, so real briefing prose
    // (which never opens with these instruction phrases) is left untouched.
    private static final Pattern LEAKED_LANG_DIRECTIVE = Pattern.compile(
            "(?s)^\\s*"
          + "(?:重要\\s*[—\\-–][^\\n。]*?言語[:：]\\s*)?"
          + "(?:上記の指示[^。]*。\\s*)?"
          + "(?:回答は必ず自然な日本語[^。]*。\\s*)?"
          + "(?:見出し[^。]*。\\s*)?"
          + "(?:(?:ただし\\s*)?===FOLLOWUPS===[^。]*。\\s*)?"
          + "(?:質問[文内][^。]*。\\s*)?"
          + "(?:\\([^)]*(?:natural Japanese|FOLLOWUPS)[^)]*\\)\\s*)?");

    private static final List<String> DEFAULT_FOLLOWUPS = List.of(
        "When does this need to be resolved?",
        "Which systems or teams are affected?",
        "How should this be escalated?"
    );

    /**
     * Splits the raw LLM response on the ===FOLLOWUPS=== delimiter.
     * Part 1 → cleaned briefing prose (What/Why/Who).
     * Part 2 → up to 3 contextual follow-up questions (When/Where/How).
     * Falls back gracefully when the LLM omits the delimiter.
     */
    private NlQueryResult parseResponse(String raw, String providerId, String modelName, UUID tenant) {
        if (raw == null || raw.isBlank()) {
            return new NlQueryResult(fallbackBriefing(tenant), fallbackFollowups(tenant), providerId, modelName);
        }

        // Out-of-scope guard (Rule 0): if the model flagged the question as unrelated
        // to enterprise operations, return a polite decline with no follow-ups instead
        // of a briefing. We tolerate minor wrapping/punctuation around the bare token.
        if (isOutOfScope(raw)) {
            log.info("nl-query: question flagged OUT_OF_SCOPE by {} — returning scope decline", providerId);
            return new NlQueryResult(outOfScopeMessage(tenant), List.of(), providerId, modelName);
        }

        // Drop any echoed "MODEL-SPECIFIC GUIDANCE" block (and the duplicate answer
        // a weak model tacks on after it) before anything else.
        String body = TRAILING_GUIDANCE.matcher(raw).replaceAll("").trim();

        // Locate where the follow-up questions begin — the ===FOLLOWUPS===
        // delimiter, a "FOLLOW-UP QUESTIONS" header, or the first "Q1:" line — so
        // they always end up in the suggestion chips, never the answer body.
        int[] bounds = followupBounds(body);
        String briefingPart = bounds == null ? body : body.substring(0, bounds[0]);
        String followPart   = bounds == null ? ""   : body.substring(bounds[1]);

        String briefing = cleanBriefing(briefingPart.trim(), tenant);

        List<String> followups = new ArrayList<>();
        Matcher m = FOLLOWUP_LINE.matcher(followPart);
        while (m.find() && followups.size() < 3) {
            String q = m.group(1).trim().replaceAll("\\s+", " ");
            if (!q.isBlank()) followups.add(q);
        }
        if (followups.isEmpty()) followups = fallbackFollowups(tenant);

        log.debug("parseResponse: briefing={} chars, followups={}", briefing.length(), followups.size());
        return new NlQueryResult(briefing, followups, providerId, modelName);
    }

    /** Returns {briefingEnd, followStart} marking where follow-ups begin, or null if none found. */
    private static int[] followupBounds(String text) {
        for (Pattern p : FOLLOWUP_BOUNDARIES) {
            Matcher m = p.matcher(text);
            if (m.find()) return new int[]{m.start(), m.end()};
        }
        Matcher q = FIRST_FOLLOWUP_Q.matcher(text);
        if (q.find()) return new int[]{q.start(), q.start()};   // keep Q1 in the follow part
        return null;
    }

    /** Out-of-scope decline message from the store, with a hard-coded ultimate fallback. */
    private String outOfScopeMessage(UUID tenant) {
        String s = promptStore.text(tenant, "fallback.out-of-scope");
        return (s == null || s.isBlank()) ? OUT_OF_SCOPE_MESSAGE : s;
    }

    /** Default follow-up questions from the store, with a hard-coded ultimate fallback. */
    private List<String> fallbackFollowups(UUID tenant) {
        List<String> f = promptStore.followups(tenant);
        return (f == null || f.isEmpty()) ? DEFAULT_FOLLOWUPS : f;
    }

    /**
     * True when the LLM emitted the Rule 0 scope sentinel. We accept the bare token
     * possibly wrapped in light markdown/punctuation/quotes, but require the response
     * to be short so a genuine briefing that merely mentions the phrase isn't caught.
     */
    private boolean isOutOfScope(String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() > 80) return false;                 // a real briefing is far longer
        String letters = trimmed.toUpperCase().replaceAll("[^A-Z_]", "");
        return letters.equals(OUT_OF_SCOPE_MARKER)
            || trimmed.toUpperCase().startsWith(OUT_OF_SCOPE_MARKER);
    }

    /** Light-touch cleanup of the briefing portion only. */
    private String cleanBriefing(String s, UUID tenant) {
        // Drop any echoed response-language directive the model copied verbatim.
        s = LEAKED_LANG_DIRECTIVE.matcher(s).replaceFirst("").stripLeading();
        // Strip scaffolding labels (WHAT —/WHY —/WHO —/PART n —) a weak model
        // copies from the format rules instead of writing flowing prose.
        s = SCAFFOLD_LABEL.matcher(s).replaceAll("");
        s = JAVA_MAP_BLOCK.matcher(s).replaceAll("");
        s = JAVA_KEY_VALUE.matcher(s).replaceAll("");
        s = RAW_ID.matcher(s).replaceAll("");
        s = HASH_HEADER.matcher(s).replaceAll("");
        s = BARE_BULLET.matcher(s).replaceAll("");
        s = s.replaceAll("[ \\t]{2,}", " ")
             .replaceAll("(?m)^[ \\t]*$\\R", "")
             .trim();
        return s.isEmpty() ? fallbackBriefing(tenant) : s;
    }

    /** Empty-briefing message from the store, with a hard-coded ultimate fallback. */
    private String fallbackBriefing(UUID tenant) {
        String s = promptStore.text(tenant, "fallback.briefing");
        return (s == null || s.isBlank()) ? FALLBACK_BRIEFING : s;
    }

    private static final String FALLBACK_BRIEFING =
            "I was unable to produce a briefing from the available enterprise data at this time. "
          + "Please ensure ArcadeDB is seeded with relevant records and try again.";

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ── Inner records ─────────────────────────────────────────────────────────

    /**
     * The answer plus its follow-ups and (for genuine briefings) a
     * {@link TrustSignal}. {@code trust} is null for scope declines and when the
     * trust layer is disabled — callers must tolerate a null.
     */
    /**
     * @param providerId the route that answered — a routing key, not a model label
     *                   ({@code gemma} serves gemini-* models, {@code inhouse-hi}
     *                   serves Qwen2.5), so callers that name the model to a user
     *                   must read {@code modelName} instead.
     * @param modelName  the model actually called, i.e. what
     *                   {@code provider.resolveModelName(overrides)} returned.
     */
    public record NlQueryResult(String answer, List<String> followups, String providerId,
                                String modelName, TrustSignal trust) {
        public NlQueryResult(String answer, List<String> followups, String providerId, String modelName) {
            this(answer, followups, providerId, modelName, null);
        }
        public NlQueryResult withTrust(TrustSignal t) {
            return new NlQueryResult(answer, followups, providerId, modelName, t);
        }
    }
}
