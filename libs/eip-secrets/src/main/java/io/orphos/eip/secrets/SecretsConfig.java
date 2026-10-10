package io.orphos.eip.secrets;

import org.springframework.core.env.Environment;

/**
 * Shared helpers for constructing a {@link SecretsClient} from {@code secrets.*}
 * properties and parsing {@code ENV=path#field} binding entries. Centralised so
 * the environment post-processor, the runtime binding backstop, and the
 * auto-configuration all read configuration identically.
 */
final class SecretsConfig {

    private SecretsConfig() {}

    /**
     * Build a {@link SecretsClient} from the {@code secrets.*} properties.
     *
     * <p>Scalars are read via {@code getProperty} (not {@code Binder.bind}) so
     * relaxed env-var names like {@code INFISICAL_CLIENT_SECRET_FILE} resolve
     * through {@code SystemEnvironmentPropertySource} even when the property is
     * not declared in {@code application.yml}.</p>
     */
    static SecretsClient from(Environment env) {
        String host         = env.getProperty("secrets.host",         "");
        String environment  = env.getProperty("secrets.environment",  "prod");
        String projectId    = SecretsClient.resolveValue(
                env.getProperty("secrets.project-id",          ""),
                env.getProperty("secrets.project-id-file",     ""));
        String clientId     = SecretsClient.resolveValue(
                env.getProperty("secrets.client-id",           ""),
                env.getProperty("secrets.client-id-file",      ""));
        String clientSecret = SecretsClient.resolveValue(
                env.getProperty("secrets.client-secret",       ""),
                env.getProperty("secrets.client-secret-file",  ""));
        return new SecretsClient(host, projectId, environment, clientId, clientSecret);
    }

    /** A parsed {@code ENV_NAME=path#field} binding entry. */
    record Binding(String envName, String path, String field) {
        static Binding parse(String raw) {
            if (raw == null) return null;
            String s = raw.trim();
            int eq = s.indexOf('=');
            int hash = s.indexOf('#', eq + 1);
            if (eq <= 0 || hash <= eq + 1 || hash == s.length() - 1) return null;
            String env   = s.substring(0, eq).trim();
            String path  = s.substring(eq + 1, hash).trim();
            String field = s.substring(hash + 1).trim();
            if (env.isEmpty() || path.isEmpty() || field.isEmpty()) return null;
            return new Binding(env, path, field);
        }
    }
}
