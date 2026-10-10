package io.orphos.eip.secrets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime backstop for {@link SecretsEnvironmentPostProcessor}.
 *
 * <p>That env post-processor resolves {@code secrets.bindings} during the very
 * early {@code prepareEnvironment} phase, where the network read is
 * timing-fragile and can silently return empty — leaving e.g.
 * {@code arcadedb.password} blank, which makes every ArcadeDB call 403 with
 * "Basic authentication error".</p>
 *
 * <p>This {@link BeanFactoryPostProcessor} re-resolves any binding whose target
 * property is still blank. It runs during context refresh — after the network is
 * fully up and the client-secret file is readable — and BEFORE any regular bean
 * is instantiated, contributing the values as a high-precedence
 * {@link MapPropertySource}. It only fills in <em>blank</em> bindings, so an
 * explicit env override or a value the early post-processor already resolved
 * always wins.</p>
 */
public class SecretsBindingPostProcessor implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    static final String PROPERTY_SOURCE_NAME = "infisical-secrets-runtime";

    private static final Logger log = LogManager.getLogger(SecretsBindingPostProcessor.class);

    private ConfigurableEnvironment environment;

    @Override
    public void setEnvironment(Environment env) {
        if (env instanceof ConfigurableEnvironment ce) {
            this.environment = ce;
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        ConfigurableEnvironment environment = this.environment;
        if (environment == null) {
            try {
                environment = beanFactory.getBean(ConfigurableEnvironment.class);
            } catch (Exception e) {
                log.warn("Infisical runtime backstop: no Environment available, skipping");
                return;
            }
        }

        Binder binder = Binder.get(environment);
        List<String> bindings = binder.bind("secrets.bindings", Bindable.listOf(String.class)).orElse(List.of());
        if (bindings.isEmpty()) {
            return;
        }

        SecretsClient client = SecretsConfig.from(environment);
        if (!client.isConfigured()) {
            log.warn("Infisical runtime backstop: secrets.* is incomplete — leaving placeholders to their defaults");
            return;
        }

        // Collect only the bindings the early post-processor (or an explicit env
        // var) left blank — never override an already-set value.
        List<SecretsConfig.Binding> pending = new ArrayList<>();
        for (String entry : bindings) {
            SecretsConfig.Binding b = SecretsConfig.Binding.parse(entry);
            if (b == null) {
                log.warn("Skipping malformed secrets binding entry: {}", entry);
                continue;
            }
            String existing = environment.getProperty(b.envName());
            if (existing != null && !existing.isBlank()) {
                continue;
            }
            pending.add(b);
        }
        if (pending.isEmpty()) {
            return;
        }

        // A not-yet-ready Infisical returns empty from readField exactly like a
        // legitimately-missing secret, so before resolving we wait (bounded,
        // exponential backoff) until the backend actually answers. This closes
        // the startup race where a service boots ahead of Infisical, binds a
        // blank ARCADEDB_PASSWORD, and then 403s ("Basic authentication error")
        // on every ArcadeDB call until restarted.
        awaitReachable(client, environment);

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (SecretsConfig.Binding b : pending) {
            Optional<String> value = client.readField(b.path(), b.field());
            if (value.isPresent()) {
                resolved.put(b.envName(), value.get());
            } else {
                log.warn("Infisical runtime backstop: {} -> {}#{} returned no value", b.envName(), b.path(), b.field());
            }
        }

        if (!resolved.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, resolved));
            log.info("Infisical runtime backstop injected {} secret(s) before bean creation", resolved.size());
        }
    }

    /**
     * Blocks (up to {@code secrets.bootstrap.max-wait-ms}, default 60s) until the
     * secrets backend is reachable, retrying with exponential backoff. Returns
     * early the instant it becomes reachable; if it never does within the budget,
     * logs a prominent error explaining the likely 403 fallout and lets bootstrap
     * proceed (so a genuinely-down backend degrades rather than crash-loops).
     */
    private void awaitReachable(SecretsClient client, Environment env) {
        if (client.isReachable()) {
            return;
        }
        long maxWaitMs   = env.getProperty("secrets.bootstrap.max-wait-ms", Long.class, 60_000L);
        long backoffMs   = env.getProperty("secrets.bootstrap.initial-backoff-ms", Long.class, 1_000L);
        final long maxBackoffMs = 8_000L;
        long deadline = System.currentTimeMillis() + maxWaitMs;
        int attempt = 0;
        while (!client.isReachable()) {
            if (System.currentTimeMillis() >= deadline) {
                log.error("Infisical runtime backstop: backend still unreachable after {}ms — required "
                        + "secrets (e.g. ARCADEDB_PASSWORD) will fall back to their empty defaults, which "
                        + "typically surfaces as ArcadeDB 403 'Basic authentication error'. Restart this "
                        + "service once Infisical is healthy.", maxWaitMs);
                return;
            }
            attempt++;
            log.warn("Infisical runtime backstop: backend not reachable yet (attempt {}), retrying in {}ms", attempt, backoffMs);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Infisical runtime backstop: interrupted while waiting for the secrets backend");
                return;
            }
            backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
        }
        log.info("Infisical runtime backstop: secrets backend reachable after {} retry attempt(s)", attempt);
    }

    @Override
    public int getOrder() {
        // Run before other BFPPs (e.g. PropertySourcesPlaceholderConfigurer) so
        // the injected source is visible to every @Value resolution.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
