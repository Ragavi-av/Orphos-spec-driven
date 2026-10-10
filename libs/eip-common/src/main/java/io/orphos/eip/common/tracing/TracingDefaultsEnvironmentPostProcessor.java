package io.orphos.eip.common.tracing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Injects sensible defaults for distributed tracing at the lowest possible
 * precedence — services can still override anything via their own
 * {@code application.yml}, env vars, or command-line args.
 *
 * <p>Defaults assume Jaeger is reachable at {@code eip-jaeger:4318} (matches
 * the docker-compose service name). Jaeger ingests OTLP natively, so services
 * emit traces exactly as before. For non-docker deploys, override
 * {@code management.otlp.tracing.endpoint} or set
 * {@code MANAGEMENT_OTLP_TRACING_ENDPOINT}.</p>
 *
 * <p>Sampling is 1.0 in dev so every request shows up in Jaeger; tune
 * down to 0.1 or lower for production.</p>
 */
public class TracingDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SOURCE_NAME = "eipTracingDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (env.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("management.tracing.enabled", "true");
        defaults.put("management.tracing.sampling.probability", "1.0");
        defaults.put("management.otlp.tracing.endpoint", "http://eip-jaeger:4318/v1/traces");
        // Surface the traceId/spanId in the log MDC so a log line in VictoriaLogs
        // can be correlated with the corresponding trace in Jaeger by traceId.
        defaults.put("logging.pattern.level",
                "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]");

        env.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        // Run after the secrets bootstrap so secret-resolution doesn't clobber
        // these defaults (and so our defaults don't shadow secret values).
        return Ordered.LOWEST_PRECEDENCE;
    }
}
