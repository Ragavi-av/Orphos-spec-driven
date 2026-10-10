package io.orphos.eip.secrets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Provides a runtime {@link SecretsClient} bean for services that need to read
 * or write secrets dynamically (e.g. per-user provider API keys). Bootstrap
 * secret resolution is handled separately by {@link SecretsEnvironmentPostProcessor}.
 */
@AutoConfiguration
public class SecretsAutoConfiguration {

    /**
     * Runtime backstop that re-resolves any blank {@code secrets.bindings} from
     * Infisical before beans are created. {@code static} so the BFPP is created
     * without prematurely instantiating this configuration class.
     */
    @Bean
    public static SecretsBindingPostProcessor secretsBindingPostProcessor() {
        return new SecretsBindingPostProcessor();
    }

    @Bean
    public SecretsClient secretsClient(
            @Value("${secrets.host:http://eip-infisical:8080}") String host,
            @Value("${secrets.environment:prod}")               String environment,
            @Value("${secrets.project-id:}")                    String projectId,
            @Value("${secrets.project-id-file:}")               String projectIdFile,
            @Value("${secrets.client-id:}")                     String clientId,
            @Value("${secrets.client-id-file:}")                String clientIdFile,
            @Value("${secrets.client-secret:}")                 String clientSecret,
            @Value("${secrets.client-secret-file:}")            String clientSecretFile) {
        return new SecretsClient(
                host,
                SecretsClient.resolveValue(projectId, projectIdFile),
                environment,
                SecretsClient.resolveValue(clientId, clientIdFile),
                SecretsClient.resolveValue(clientSecret, clientSecretFile));
    }
}
