package io.orphos.eip.nlquery.component;

import io.orphos.eip.nlquery.service.NlQueryService;
import io.orphos.eip.nlquery.repository.ArcadeDbContextBuilder;
import io.orphos.eip.nlquery.vo.ProviderOverrides;
import io.orphos.eip.nlquery.config.CacheConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Explicit read-through caching for the two expensive, highly repeatable parts of
 * the answer pipeline: the whole briefing and the ArcadeDB live-context snapshot.
 *
 * <p>Deliberately hand-rolled rather than {@code @Cacheable}: the keys need the
 * caller's persona roles (a thread-local, awkward in SpEL) and the answer path is
 * reached by self-invocation from {@code answer(String)}, which a Spring cache
 * proxy would not intercept.</p>
 *
 * <p><b>Key composition matters for isolation.</b> The tenant id and the caller's
 * sorted role set are both part of every key, because the live context is
 * persona-filtered — two personas in one tenant legitimately see different
 * enterprise data and must never share a cached answer. The API key from
 * {@link ProviderOverrides} is deliberately <i>excluded</i>: it does not change
 * the answer and must not be written into a cache key.</p>
 */
@Component
public class NlQueryCache {

    private static final Logger log = LogManager.getLogger(NlQueryCache.class);

    private final Cache answers;
    private final Cache liveContext;

    public NlQueryCache(CacheManager cacheManager) {
        this.answers     = cacheManager.getCache(CacheConfig.ANSWERS);
        this.liveContext = cacheManager.getCache(CacheConfig.LIVE_CONTEXT);
    }

    // ── Answers ───────────────────────────────────────────────────────────────

    /**
     * Cache key for a fully-resolved answer request. Everything that can change
     * the generated text is included; the API key is not.
     */
    public static String answerKey(UUID tenant, Set<String> roles, String providerId,
                                   String modelName, String lang, String question) {
        return tenant + "|" + roleKey(roles) + "|" + providerId + "|" + modelName
             + "|" + lang + "|" + normalise(question);
    }

    /** Cached answer for this key, or null on a miss. */
    public NlQueryService.NlQueryResult lookupAnswer(String key) {
        if (answers == null) return null;
        Cache.ValueWrapper hit = answers.get(key);
        if (hit == null) return null;
        Object v = hit.get();
        if (v instanceof NlQueryService.NlQueryResult r) {
            log.debug("Answer cache hit for key {}", key);
            return r;
        }
        return null;
    }

    /** Stores a successful answer. Declines and failures are not worth a slot. */
    public void storeAnswer(String key, NlQueryService.NlQueryResult result) {
        if (answers == null || result == null) return;
        answers.put(key, result);
    }

    // ── Live context ──────────────────────────────────────────────────────────

    /**
     * Read-through for the ArcadeDB enterprise snapshot. Keyed by tenant +
     * persona roles, so the persona filtering applied inside
     * {@link ArcadeDbContextBuilder#buildLiveContext()} stays correct.
     *
     * <p>The supplier runs on the calling thread, which is required: it reads the
     * tenant and role thread-locals.</p>
     */
    public String liveContext(UUID tenant, Set<String> roles, Supplier<String> builder) {
        if (liveContext == null) return builder.get();
        String key = tenant + "|" + roleKey(roles);
        Cache.ValueWrapper hit = liveContext.get(key);
        if (hit != null && hit.get() instanceof String s) return s;
        String built = builder.get();
        if (built != null) liveContext.put(key, built);
        return built;
    }

    // ── Key helpers ───────────────────────────────────────────────────────────

    /** Roles as a stable, order-independent string so key equality is reliable. */
    private static String roleKey(Set<String> roles) {
        if (roles == null || roles.isEmpty()) return "-";
        return String.join(",", new TreeSet<>(roles));
    }

    /**
     * Case- and whitespace-insensitive question form, so "What is the SLA status?"
     * and "what is the SLA status?" share one entry. Punctuation is kept: it can
     * genuinely change intent.
     */
    private static String normalise(String question) {
        return question == null ? "" : question.trim().toLowerCase().replaceAll("\s+", " ");
    }
}
