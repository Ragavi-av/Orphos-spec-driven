package io.orphos.eip.nlquery.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine caches for NL-query work. Each layer has its own TTL because the
 * things they hold go stale at very different rates:
 *
 * <ul>
 *   <li>{@code embeddings} — Ollama embedding vectors keyed by the (normalised,
 *       padded) query text. Model weights never change under us, so these are
 *       valid for as long as we care to keep them. Identical phrases recur
 *       constantly (canonical KPI/OKR names, the fixed briefing lead-ins), and
 *       every miss is a network round trip to the hi-memory Ollama.</li>
 *   <li>{@code liveContext} — the ArcadeDB enterprise snapshot, keyed by
 *       tenant + persona roles. Building it is ~15 sequential HTTP queries and
 *       the result is identical for every question the same persona asks, so a
 *       short TTL removes that cost from all but the first question.</li>
 *   <li>{@code answers} — end-to-end briefings keyed by tenant + roles +
 *       provider + model + language + normalised question. This is what makes a
 *       repeated question return immediately instead of re-running the whole
 *       LLM pipeline.</li>
 * </ul>
 *
 * <p>All three TTLs are env-overridable so an operator can trade freshness for
 * latency without a rebuild. Set a TTL to 0 to disable that cache outright.</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Cache name for embedding vectors keyed by query text. */
    public static final String EMBEDDINGS   = "embeddings";
    /** Cache name for the per-persona ArcadeDB live-context snapshot. */
    public static final String LIVE_CONTEXT = "liveContext";
    /** Cache name for end-to-end NL answers. */
    public static final String ANSWERS      = "answers";

    private final long embeddingsTtlMinutes;
    private final long liveContextTtlSeconds;
    private final long answersTtlMinutes;

    public CacheConfig(
            @Value("${nlquery.cache.embeddings-ttl-minutes:120}")  long embeddingsTtlMinutes,
            @Value("${nlquery.cache.live-context-ttl-seconds:60}") long liveContextTtlSeconds,
            @Value("${nlquery.cache.answers-ttl-minutes:10}")      long answersTtlMinutes) {
        this.embeddingsTtlMinutes  = embeddingsTtlMinutes;
        this.liveContextTtlSeconds = liveContextTtlSeconds;
        this.answersTtlMinutes     = answersTtlMinutes;
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        // No dynamic cache creation — only the three named caches below exist.
        mgr.setCacheNames(java.util.List.of());
        mgr.registerCustomCache(EMBEDDINGS,   build(2_000, embeddingsTtlMinutes,  TimeUnit.MINUTES));
        mgr.registerCustomCache(LIVE_CONTEXT, build(  200, liveContextTtlSeconds, TimeUnit.SECONDS));
        mgr.registerCustomCache(ANSWERS,      build(1_000, answersTtlMinutes,     TimeUnit.MINUTES));
        return mgr;
    }

    /**
     * A TTL of 0 yields a zero-capacity cache — every read misses and every
     * write is dropped, which is how an operator turns one layer off.
     */
    private static Cache<Object, Object> build(long maxSize, long ttl, TimeUnit unit) {
        Caffeine<Object, Object> c = Caffeine.newBuilder().recordStats();
        if (ttl <= 0) return c.maximumSize(0).build();
        return c.maximumSize(maxSize).expireAfterWrite(ttl, unit).build();
    }
}
