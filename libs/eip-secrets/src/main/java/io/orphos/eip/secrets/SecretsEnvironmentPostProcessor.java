package io.orphos.eip.secrets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves secrets from Infisical at application bootstrap and exposes them as a
 * high-precedence property source so {@code ${PLACEHOLDER}} references in
 * {@code application.yml} resolve to fetched values.
 *
 * Bindings are declared in each service's {@code application.yml} as a list of
 * {@code ENV_NAME=path#field} entries:
 *
 * <pre>{@code
 * secrets:
 *   host:        ${INFISICAL_HOST:http://eip-infisical:8080}
 *   project-id:  ${INFISICAL_PROJECT_ID:}
 *   environment: ${INFISICAL_ENV:prod}
 *   client-id:   ${INFISICAL_CLIENT_ID:}
 *   client-secret-file: ${INFISICAL_CLIENT_SECRET_FILE:}
 *   bindings:
 *     - ARCADEDB_PASSWORD=eip/arcadedb#password
 *     - SMTP_PASSWORD=eip/smtp#password
 * }</pre>
 *
 * If Infisical is unreachable, the machine identity is not configured, or a
 * binding's secret is missing, the processor logs a warning and leaves the
 * placeholder unresolved so the existing {@code ${VAR:default}} fallbacks
 * continue to work.
 */
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "infisical-secrets";

    private static final Logger log = LogManager.getLogger(SecretsEnvironmentPostProcessor.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Binder binder = Binder.get(environment);

        List<String> bindings = binder.bind("secrets.bindings", Bindable.listOf(String.class)).orElse(List.of());
        if (bindings.isEmpty()) {
            return;
        }

        SecretsClient client = SecretsConfig.from(environment);
        if (!client.isConfigured()) {
            log.warn("Infisical bindings declared but secrets.* is incomplete (host/project-id/client-id/client-secret) — leaving placeholders to their defaults");
            return;
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String entry : bindings) {
            SecretsConfig.Binding b = SecretsConfig.Binding.parse(entry);
            if (b == null) {
                log.warn("Skipping malformed secrets binding entry: {}", entry);
                continue;
            }
            Optional<String> value = client.readField(b.path(), b.field());
            if (value.isPresent()) {
                resolved.put(b.envName(), value.get());
            } else {
                log.warn("Infisical binding {} -> {}#{} returned no value; leaving placeholder unresolved", b.envName(), b.path(), b.field());
            }
        }

        if (!resolved.isEmpty()) {
            // Insert FIRST so Infisical values win over OS environment variables
            // bearing the same name.
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, resolved));
            log.info("Infisical injected {} secret(s) into the environment", resolved.size());
        }
    }

    @Override
    public int getOrder() {
        // Must run AFTER ConfigDataEnvironmentPostProcessor (which loads
        // application.yml at HIGHEST_PRECEDENCE + 10) so our Binder calls can
        // see the secrets.* properties declared there.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
