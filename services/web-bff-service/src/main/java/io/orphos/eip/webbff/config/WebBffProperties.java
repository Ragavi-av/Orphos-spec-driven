package io.orphos.eip.webbff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Downstream origins and fan-out tuning, bound from {@code eip.web-bff.*}.
 *
 * <p>Held as one typed object rather than a scatter of {@code @Value} strings so the
 * set of services this BFF may reach is declared in a single place — the list doubles
 * as the service's egress surface when reviewing what the edge can talk to.</p>
 */
@ConfigurationProperties(prefix = "eip.web-bff")
public class WebBffProperties {

    private final Downstream downstream = new Downstream();
    private final FanOut fanOut = new FanOut();

    public Downstream getDownstream() {
        return downstream;
    }

    public FanOut getFanOut() {
        return fanOut;
    }

    /** Base URLs of the services the dashboard screens aggregate. */
    public static class Downstream {
        private String platformRegistry = "http://eip-platform-registry:8093";
        private String platformCore = "http://eip-platform-core:8090";
        private String briefing = "http://eip-briefing:8084";
        private String priorityEngine = "http://eip-priority-engine:8083";
        private String decisionLog = "http://eip-decision-log:8086";
        private String notification = "http://eip-notification:8087";
        private String docIngest = "http://eip-doc-ingest:8091";
        // Persona metrics and the per-user dashboard layout.
        private String widgets = "http://eip-widgets:8095";

        public String getPlatformRegistry() { return platformRegistry; }
        public void setPlatformRegistry(String v) { this.platformRegistry = v; }

        public String getPlatformCore() { return platformCore; }
        public void setPlatformCore(String v) { this.platformCore = v; }

        public String getBriefing() { return briefing; }
        public void setBriefing(String v) { this.briefing = v; }

        public String getPriorityEngine() { return priorityEngine; }
        public void setPriorityEngine(String v) { this.priorityEngine = v; }

        public String getDecisionLog() { return decisionLog; }
        public void setDecisionLog(String v) { this.decisionLog = v; }

        public String getNotification() { return notification; }
        public void setNotification(String v) { this.notification = v; }

        public String getDocIngest() { return docIngest; }
        public void setDocIngest(String v) { this.docIngest = v; }

        public String getWidgets() { return widgets; }
        public void setWidgets(String v) { this.widgets = v; }
    }

    /** Parallel fan-out limits. */
    public static class FanOut {
        /**
         * Worker threads for downstream calls. Each aggregate holds one per in-flight
         * call, so this caps concurrent egress across all in-flight requests.
         */
        private int poolSize = 16;

        /** Queue depth once every worker is busy; beyond it, calls run on the request thread. */
        private int queueCapacity = 128;

        /**
         * Ceiling on one screen's whole fan-out. Below the 30s downstream read timeout on
         * purpose: a dashboard tile is worth showing as degraded long before a browser
         * would give up on the request.
         */
        private Duration timeout = Duration.ofSeconds(8);

        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int v) { this.poolSize = v; }

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int v) { this.queueCapacity = v; }

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration v) { this.timeout = v; }
    }
}
