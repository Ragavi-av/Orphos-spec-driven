package io.orphos.eip.core.arcadedb;

import io.orphos.eip.core.dbregistry.ConnectionRegistry;
import io.orphos.eip.core.dbregistry.ConnectionRegistryLoader;
import io.orphos.eip.core.dbregistry.ConnectionUrls;
import io.orphos.eip.secrets.SecretsClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a per-tenant {@link ArcadeDbTemplate} for a chosen DB-connection at
 * <em>runtime</em>. A DEDICATED tenant may live on a different ArcadeDB
 * <em>server</em> than the platform default; since {@link ArcadeDbTemplate}
 * only varies the database name (not the server/credential) between calls,
 * routing to another server requires a distinct template built from that
 * connection's URL + user + password.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>{@code connectionId} null / blank / {@value #BUNDLED_ID} → the injected
 *       <b>default</b> template (the platform ArcadeDB, {@code arcadedb.*}). No
 *       registry-file or secret access — the zero-config / backward-compatible
 *       path.</li>
 *   <li>Registry file absent → default template (a service that has no
 *       connections registry behaves exactly as before).</li>
 *   <li>Connection unknown, not {@code ARCADEDB}, has no derivable URL, or its
 *       credential cannot be resolved → <b>fail closed</b> ({@link ArcadeDbTemplate.ArcadeDbException}).
 *       Never silently fall back to the platform server — that would read/write
 *       a tenant's data on the wrong ArcadeDB.</li>
 * </ul>
 *
 * <p>Built templates are cached by {@code connectionId} with a write-TTL so an
 * edited host/credential propagates within one TTL; {@link #invalidate} forces
 * an immediate rebuild (call it after an auth failure, then retry once).</p>
 *
 * <p>This is a plain class (no Spring stereotype) so it can be unit-tested and
 * is registered as a bean by {@code ArcadeDbAutoConfiguration}.</p>
 */
public class ArcadeConnectionResolver {

    /** The bundled/platform connection id — treated as "use the default template". */
    public static final String BUNDLED_ID = "arcadedb-bundled";

    private static final Logger log = LogManager.getLogger(ArcadeConnectionResolver.class);

    private final ArcadeDbTemplate defaultTemplate;
    private final ConnectionRegistryLoader loader;
    private final SecretsClient secrets;      // may be null when eip-secrets is absent
    private final String connectionsFile;
    private final long ttlMillis;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(ArcadeDbTemplate template, long expiresAt) {}

    public ArcadeConnectionResolver(ArcadeDbTemplate defaultTemplate,
                                    ConnectionRegistryLoader loader,
                                    SecretsClient secrets,
                                    String connectionsFile,
                                    Duration ttl) {
        this.defaultTemplate = defaultTemplate;
        this.loader = loader != null ? loader : new ConnectionRegistryLoader();
        this.secrets = secrets;
        this.connectionsFile = connectionsFile;
        this.ttlMillis = (ttl != null ? ttl : Duration.ofMinutes(10)).toMillis();
    }

    /**
     * Template for the given connection. Returns the default template for the
     * bundled/unset case; throws for a real connection that cannot be resolved.
     * Never returns {@code null}.
     */
    public ArcadeDbTemplate templateFor(String connectionId) {
        if (isDefault(connectionId)) {
            return defaultTemplate;
        }
        long now = System.currentTimeMillis();
        Cached c = cache.get(connectionId);
        if (c != null && c.expiresAt() > now) {
            return c.template();
        }
        ArcadeDbTemplate built = build(connectionId);
        // A registry-file-absent build returns the default template; don't cache
        // that under the id (the file may appear later).
        if (built != defaultTemplate) {
            cache.put(connectionId, new Cached(built, now + ttlMillis));
        }
        return built;
    }

    /** Drop the cached template for a connection (e.g. after an auth failure). */
    public void invalidate(String connectionId) {
        if (connectionId != null) {
            cache.remove(connectionId);
        }
    }

    private boolean isDefault(String id) {
        return id == null || id.isBlank() || id.trim().equals(BUNDLED_ID);
    }

    private ArcadeDbTemplate build(String connectionId) {
        Optional<ConnectionRegistry.File> file = loader.load(connectionsFile);
        if (file.isEmpty()) {
            // No registry present at all → behave as the default (back-compat).
            log.debug("Connections registry absent; using default ArcadeDB for connection '{}'", connectionId);
            return defaultTemplate;
        }
        ConnectionRegistry.Connection conn = file.get().connection(connectionId);
        if (conn == null || conn.typeEnum() != ConnectionRegistry.Type.ARCADEDB) {
            throw new ArcadeDbTemplate.ArcadeDbException(
                    "No ARCADEDB connection '" + connectionId + "' in the connections registry");
        }
        String url = ConnectionUrls.urlFor(conn);
        if (url == null || url.isBlank()) {
            throw new ArcadeDbTemplate.ArcadeDbException(
                    "ArcadeDB connection '" + connectionId + "' has no derivable URL");
        }
        String password = resolveCredential(connectionId, conn.credentialRef());

        ArcadeDbProperties props = new ArcadeDbProperties();
        props.setUrl(url);
        props.setUser(conn.user());
        props.setPassword(password);
        props.setDatabase(null);   // db name is supplied per call via commandNoResultOn/queryOn
        log.info("Built ArcadeDB template for connection '{}' ({})", connectionId, url);
        return new ArcadeDbTemplate(props);
    }

    private String resolveCredential(String connectionId, String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return "";   // unauthenticated connection (rare, but allowed)
        }
        if (secrets == null) {
            throw new ArcadeDbTemplate.ArcadeDbException(
                    "Cannot resolve credential for connection '" + connectionId + "': secrets backend unavailable");
        }
        int hash = credentialRef.indexOf('#');
        if (hash <= 0 || hash == credentialRef.length() - 1) {
            throw new ArcadeDbTemplate.ArcadeDbException(
                    "Malformed credentialRef for connection '" + connectionId + "': " + credentialRef);
        }
        return secrets.readField(credentialRef.substring(0, hash), credentialRef.substring(hash + 1))
                .orElseThrow(() -> new ArcadeDbTemplate.ArcadeDbException(
                        "Could not resolve credential for connection '" + connectionId + "'"));
    }
}
