package io.orphos.eip.common.web.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-path rate-limit policy. Each {@link Rule} pins a path prefix to a
 * token bucket sized by {@code capacity} requests per {@code refillPeriod}.
 * Buckets are keyed by client IP (X-Forwarded-For aware).
 *
 * <p>The whole feature is off by default — enable per service by setting
 * {@code eip.rate-limit.enabled=true} and declaring rules.</p>
 */
@ConfigurationProperties("eip.rate-limit")
public class RateLimitProperties {

    private boolean enabled = false;
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) { this.rules = rules; }

    public static class Rule {
        /** Ant-style path the rule applies to, e.g. {@code /api/auth/**}. */
        private String pathPrefix;
        /** Tokens granted per refill period. */
        private long capacity = 60;
        /** How often the bucket fully refills. */
        private Duration refillPeriod = Duration.ofMinutes(1);

        public String getPathPrefix() { return pathPrefix; }
        public void setPathPrefix(String pathPrefix) { this.pathPrefix = pathPrefix; }

        public long getCapacity() { return capacity; }
        public void setCapacity(long capacity) { this.capacity = capacity; }

        public Duration getRefillPeriod() { return refillPeriod; }
        public void setRefillPeriod(Duration refillPeriod) { this.refillPeriod = refillPeriod; }
    }
}
