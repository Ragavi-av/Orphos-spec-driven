package io.orphos.eip.core.arcadedb;

import io.orphos.eip.core.dbregistry.ConnectionRegistryLoader;
import io.orphos.eip.secrets.SecretsClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * Registers {@link ArcadeDbTemplate} for any service that configures
 * {@code arcadedb.url}. Services that don't touch ArcadeDB (e.g.
 * notification-service) simply never get the bean — no dead dependency wired
 * in just because {@code eip-core-arcadedb} is on the classpath.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "arcadedb", name = "url")
@EnableConfigurationProperties(ArcadeDbProperties.class)
public class ArcadeDbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ArcadeDbTemplate arcadeDbTemplate(ArcadeDbProperties props) {
        return new ArcadeDbTemplate(props);
    }

    /**
     * Per-tenant connection router. Present in every ArcadeDB-consuming service.
     * {@code SecretsClient} is optional — when absent the resolver still serves
     * the default template for the bundled/unset case (it only needs secrets to
     * build a template for a non-default connection).
     */
    @Bean
    @ConditionalOnMissingBean
    public ArcadeConnectionResolver arcadeConnectionResolver(ArcadeDbTemplate defaultTemplate,
                                                             ObjectProvider<SecretsClient> secrets,
                                                             Environment env) {
        String file = env.getProperty("eip.connections.file", ConnectionRegistryLoader.DEFAULT_FILE);
        Duration ttl = env.getProperty("eip.arcade.connection-cache-ttl", Duration.class, Duration.ofMinutes(10));
        return new ArcadeConnectionResolver(defaultTemplate, new ConnectionRegistryLoader(),
                secrets.getIfAvailable(), file, ttl);
    }
}

