package io.orphos.eip.core.dbregistry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Points a service at an operator-defined external database connection at
 * bootstrap. Reads the connections registry file (default
 * {@code /run/eip/connections.json}), looks up <em>this</em> service's
 * assignment (by {@code spring.application.name}), and — for an {@code ARCADEDB}
 * connection — contributes a high-precedence property source that overrides
 * {@code arcadedb.url/user/database} and rewrites the {@code secrets.bindings}
 * entry for {@code ARCADEDB_PASSWORD} to the connection's Infisical ref.
 *
 * <h2>Why rewrite the binding instead of resolving the secret?</h2>
 * The registry never fetches the password itself. It only changes which
 * Infisical path the existing {@code eip-secrets} pipeline
 * ({@link org.springframework.boot.env.EnvironmentPostProcessor} +
 * runtime backstop) reads — so all of that pipeline's readiness/backoff logic
 * keeps working unchanged, and there is no new secret-at-boot code path.
 *
 * <h2>Ordering</h2>
 * Runs after {@code ConfigDataEnvironmentPostProcessor} (so {@code application.yml}
 * — {@code spring.application.name}, {@code secrets.bindings},
 * {@code eip.connections.*} — is visible) but before
 * {@code SecretsEnvironmentPostProcessor} (which sits at
 * {@link Ordered#LOWEST_PRECEDENCE}), so the rewritten binding is what Infisical
 * resolves.
 *
 * <h2>Back-compat</h2>
 * If the file is absent, this service has no assignment, or the assigned
 * connection is missing/non-ArcadeDB, the processor returns silently and the
 * existing {@code ${ARCADEDB_URL:default}} environment behaviour is untouched.
 */
public class ConnectionRegistryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "eip-connection-registry";

    /** Env-var name the ArcadeDB password binding targets (see each service's application.yml). */
    static final String ARCADEDB_PASSWORD_ENV = "ARCADEDB_PASSWORD";

    private static final Logger log = LogManager.getLogger(ConnectionRegistryEnvironmentPostProcessor.class);

    private final ConnectionRegistryLoader loader = new ConnectionRegistryLoader();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String serviceId = serviceId(environment);
        if (serviceId == null || serviceId.isBlank()) {
            return;
        }

        String filePath = environment.getProperty("eip.connections.file",
                environment.getProperty("EIP_CONNECTIONS_FILE"));
        Optional<ConnectionRegistry.File> loaded = loader.load(filePath);
        if (loaded.isEmpty()) {
            return;
        }
        ConnectionRegistry.File file = loaded.get();

        ConnectionRegistry.Assignment assignment = file.assignments().get(serviceId);
        if (assignment == null || assignment.connectionId() == null) {
            return;
        }
        ConnectionRegistry.Connection conn = file.connection(assignment.connectionId());
        if (conn == null) {
            log.warn("Connections registry: service '{}' is assigned to unknown connection '{}' — ignoring",
                    serviceId, assignment.connectionId());
            return;
        }

        ConnectionRegistry.Type type = conn.typeEnum();
        if (type != ConnectionRegistry.Type.ARCADEDB) {
            // POSTGRES / NEO4J have no bootstrap consumers yet — nothing to bind.
            log.info("Connections registry: service '{}' assigned a {} connection ('{}'); no bootstrap binder for this type yet",
                    serviceId, conn.type(), conn.id());
            return;
        }

        Map<String, Object> props = new LinkedHashMap<>();

        String url = ConnectionUrls.urlFor(conn);
        if (url == null || url.isBlank()) {
            log.warn("Connections registry: connection '{}' has no derivable URL — leaving arcadedb.url default", conn.id());
            return;
        }
        props.put("arcadedb.url", url);
        if (conn.user() != null && !conn.user().isBlank()) {
            props.put("arcadedb.user", conn.user().trim());
        }
        if (assignment.database() != null && !assignment.database().isBlank()) {
            props.put("arcadedb.database", assignment.database().trim());
        }

        // Surface the assignment's schema mode so this service's schema-bootstrap
        // runner can honour it: DEFAULT → the platform may create/apply its schema
        // (CREATE ... IF NOT EXISTS); EXISTING → the operator manages the schema,
        // so the runner must not create the database or apply any DDL. Read via
        // ${eip.connections.schema-mode:DEFAULT} (see SchemaModeGuard).
        props.put(SchemaModeGuard.PROPERTY, assignment.schemaModeEnum().name());

        rewriteArcadePasswordBinding(environment, conn.credentialRef(), props);

        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
        log.info("Connections registry: service '{}' -> connection '{}' ({}), database '{}', schemaMode '{}'",
                serviceId, conn.id(), url, props.getOrDefault("arcadedb.database", "<default>"),
                assignment.schemaModeEnum());
    }

    /**
     * Rebuilds {@code secrets.bindings} so the {@code ARCADEDB_PASSWORD} entry
     * points at {@code credentialRef}, preserving every other binding. Written
     * as indexed keys ({@code secrets.bindings[i]}) into {@code props}. The new
     * list is never shorter than the original (we only swap or append), so no
     * stale lower-precedence entry can leak through.
     */
    private void rewriteArcadePasswordBinding(ConfigurableEnvironment environment,
                                              String credentialRef, Map<String, Object> props) {
        if (credentialRef == null || credentialRef.isBlank()) {
            // No ref on the connection — keep the service's existing binding.
            return;
        }
        String newEntry = ARCADEDB_PASSWORD_ENV + "=" + credentialRef.trim();

        List<String> current = Binder.get(environment)
                .bind("secrets.bindings", Bindable.listOf(String.class))
                .orElse(List.of());

        List<String> rewritten = new ArrayList<>(current);
        boolean replaced = false;
        for (int i = 0; i < rewritten.size(); i++) {
            if (envNameOf(rewritten.get(i)).equals(ARCADEDB_PASSWORD_ENV)) {
                rewritten.set(i, newEntry);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            rewritten.add(newEntry);
        }

        for (int i = 0; i < rewritten.size(); i++) {
            props.put("secrets.bindings[" + i + "]", rewritten.get(i));
        }
    }

    /** ENV name from an {@code ENV=path#field} binding entry (empty if malformed). */
    private static String envNameOf(String entry) {
        if (entry == null) return "";
        int eq = entry.indexOf('=');
        return eq <= 0 ? "" : entry.substring(0, eq).trim();
    }

    private static String serviceId(ConfigurableEnvironment environment) {
        String override = environment.getProperty("EIP_SERVICE_ID");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return environment.getProperty("spring.application.name");
    }

    @Override
    public int getOrder() {
        // Before SecretsEnvironmentPostProcessor (LOWEST_PRECEDENCE) so our
        // rewritten secrets.bindings is what Infisical resolves; still well after
        // ConfigDataEnvironmentPostProcessor so application.yml is visible.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
