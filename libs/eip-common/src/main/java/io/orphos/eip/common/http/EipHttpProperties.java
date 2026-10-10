package io.orphos.eip.common.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Outbound HTTP defaults for every service that depends on {@code eip-common}.
 *
 * <p>The point of this class is that timeouts are configured in exactly one place.
 * {@code new RestTemplate()} has <em>no</em> connect or read timeout at all — a
 * hung upstream then pins the calling thread indefinitely, which is the failure
 * mode documented on {@code ArcadeDbTemplate}. Anything built through
 * {@link EipRestTemplates} is bounded by these values unless the caller overrides
 * them per client.</p>
 */
@ConfigurationProperties("eip.http")
public class EipHttpProperties {

    /** How long to wait for the TCP connection. Short: an unreachable peer should fail fast. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * How long to wait for the response once connected. Generous by default because the
     * slowest internal callers are LLM-backed endpoints; latency-sensitive callers pass
     * their own value to {@link EipRestTemplates#create(Duration, Duration)}.
     */
    private Duration readTimeout = Duration.ofSeconds(30);

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
