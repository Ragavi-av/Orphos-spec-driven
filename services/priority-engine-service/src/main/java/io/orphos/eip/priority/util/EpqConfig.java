package io.orphos.eip.priority.util;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "epq")
public class EpqConfig {

    private Weights weights = new Weights();
    private Cache cache = new Cache();

    public Weights getWeights() { return weights; }
    public void setWeights(Weights weights) { this.weights = weights; }

    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }

    public static class Weights {
        private double severity   = 0.35;
        private double urgency    = 0.25;
        private double impact     = 0.20;
        private double recency    = 0.10;
        private double domainRisk = 0.10;

        public double getSeverity()   { return severity; }
        public void setSeverity(double v)   { this.severity = v; }

        public double getUrgency()    { return urgency; }
        public void setUrgency(double v)    { this.urgency = v; }

        public double getImpact()     { return impact; }
        public void setImpact(double v)     { this.impact = v; }

        public double getRecency()    { return recency; }
        public void setRecency(double v)    { this.recency = v; }

        public double getDomainRisk() { return domainRisk; }
        public void setDomainRisk(double v) { this.domainRisk = v; }
    }

    public static class Cache {
        private long ttlSeconds = 30;
        private long refreshMs  = 30_000;

        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long v) { this.ttlSeconds = v; }

        public long getRefreshMs() { return refreshMs; }
        public void setRefreshMs(long v) { this.refreshMs = v; }
    }
}
