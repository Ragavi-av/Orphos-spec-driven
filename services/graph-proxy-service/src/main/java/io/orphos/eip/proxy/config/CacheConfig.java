package io.orphos.eip.proxy.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed cache for SPARQL / ArcadeDB query results, with the
 * {@link #tenantAwareKeyGenerator()} bean ensuring cache keys are partitioned
 * by tenant so identical SPARQL strings issued by Tenant A and Tenant B do not
 * collide.
 *
 * <p>Annotate cached methods with {@code @Cacheable(cacheNames = "graphQueries",
 * keyGenerator = "tenantAwareKeyGenerator")} — the generator prepends the
 * current tenant UUID to the call args. Forgetting the generator is treated
 * as a programming error: the default key generator would happily share
 * Tenant A's cached projection with Tenant B.</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager("graphQueries", "ontologyMeta");
        mgr.setCaffeine(Caffeine.newBuilder()
                .maximumSize(2_000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats());
        return mgr;
    }

    /**
     * Key generator that prepends the current tenant UUID to the method args
     * so cache entries are partitioned per tenant. Falls back to a sentinel
     * "no-tenant" key for non-tenant-scoped calls (e.g. global health probes
     * that someone might accidentally annotate) — those will share a single
     * cache slot, which is harmless because no real data flows through them.
     */
    @Bean
    public KeyGenerator tenantAwareKeyGenerator() {
        return (target, method, params) -> {
            UUID tenantId = TenantContextHolder.current()
                    .map(ctx -> ctx.tenantId())
                    .orElse(null);
            Object[] tenantPrefixed = new Object[params.length + 2];
            tenantPrefixed[0] = tenantId != null ? tenantId.toString() : "__no_tenant__";
            tenantPrefixed[1] = method.getName();
            System.arraycopy(params, 0, tenantPrefixed, 2, params.length);
            return new SimpleKey(tenantPrefixed);
        };
    }
}
