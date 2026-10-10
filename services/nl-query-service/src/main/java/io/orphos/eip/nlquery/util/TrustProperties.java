package io.orphos.eip.nlquery.util;

import io.orphos.eip.nlquery.vo.TrustSignal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the response-trust layer. All default to sensible values so the
 * feature is on out of the box, except the OWL cross-check which needs the
 * okg-reasoning URL configured for the environment before it can be enabled.
 */
@ConfigurationProperties(prefix = "nlquery.trust")
public class TrustProperties {

    /** Master switch — when false, answers carry no {@link TrustSignal}. */
    private boolean enabled = true;

    /**
     * Run the post-generation LLM groundedness self-check. Adds one extra
     * model call per answer; disable to trade the verification signal for
     * lower latency (grounding band + provenance still computed).
     */
    private boolean selfCheckEnabled = true;

    /**
     * Cross-check the answer against the OWL reasoner's deterministic facts.
     * Off by default: requires {@link #okgBaseUrl} to point at a reachable
     * okg-reasoning-service. The caller's Bearer JWT is forwarded for auth.
     */
    private boolean owlCheckEnabled = false;

    /** Base URL of okg-reasoning-service for the OWL cross-check (no trailing slash needed). */
    private String okgBaseUrl = "http://eip-okg-reasoning:8088";

    /** RAG best-score at/above which the answer is treated as strongly GROUNDED. */
    private double groundedScoreThreshold = 0.60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isSelfCheckEnabled() { return selfCheckEnabled; }
    public void setSelfCheckEnabled(boolean selfCheckEnabled) { this.selfCheckEnabled = selfCheckEnabled; }

    public boolean isOwlCheckEnabled() { return owlCheckEnabled; }
    public void setOwlCheckEnabled(boolean owlCheckEnabled) { this.owlCheckEnabled = owlCheckEnabled; }

    public String getOkgBaseUrl() { return okgBaseUrl; }
    public void setOkgBaseUrl(String okgBaseUrl) { this.okgBaseUrl = okgBaseUrl; }

    public double getGroundedScoreThreshold() { return groundedScoreThreshold; }
    public void setGroundedScoreThreshold(double groundedScoreThreshold) { this.groundedScoreThreshold = groundedScoreThreshold; }
}
