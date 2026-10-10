package io.orphos.eip.dataingestion.component;

import io.orphos.eip.secrets.SecretsClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Bridge between data-ingestion code and Infisical KV storage, using the
 * {@link SecretsClient} from {@code eip-secrets}. Deliberately duplicated from
 * okg-ingest-service's identical class — each service keeps a self-contained
 * wrapper with its own namespace, no cross-service dependency.
 *
 * <p>Secret references use the URI form: {@code vault:<path>#<field>}.
 * Only refs are ever persisted; raw credentials never touch ArcadeDB or logs.</p>
 */
@Component
public class SecretRefService {

    private static final Logger log = LogManager.getLogger(SecretRefService.class);
    public static final String VAULT_PREFIX = "vault:";

    private final SecretsClient client;

    @Value("${ingestion.secrets.namespace:data-ingestion}")
    private String namespace;

    public SecretRefService(SecretsClient client) {
        this.client = client;
    }

    /** True if a ref looks like a managed vault reference (regardless of whether it resolves). */
    public static boolean isManaged(String ref) {
        return ref != null && ref.startsWith(VAULT_PREFIX);
    }

    /**
     * Store {@code value} under a freshly-generated path inside the given
     * {@code category} (e.g. {@code jdbc}). Returns the opaque ref to persist
     * on the calling document.
     */
    public String store(String category, String field, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("cannot store empty secret");
        }
        if (!client.isConfigured()) {
            throw new IllegalStateException(
                    "Secrets backend is not configured; refusing to persist secret");
        }
        String safeCategory = sanitise(category);
        String safeField    = sanitise(field);
        String path = namespace + "/" + safeCategory + "/" + UUID.randomUUID();
        client.writeField(path, safeField, value);
        return VAULT_PREFIX + path + "#" + safeField;
    }

    /** Update an existing vault ref in place (same path) — used when rotating a credential. */
    public String overwrite(String existingRef, String value) {
        if (!isManaged(existingRef)) {
            return store("rotated", "value", value);
        }
        Parsed parsed = parse(existingRef);
        if (parsed == null) {
            log.warn("malformed vault ref {}; storing under default category instead", existingRef);
            return store("rotated", "value", value);
        }
        if (!client.isConfigured()) {
            throw new IllegalStateException("Secrets backend is not configured");
        }
        client.writeField(parsed.path, parsed.field, value);
        return existingRef;
    }

    /** Resolve a vault ref. Returns {@link Optional#empty()} if missing or non-vault. */
    public Optional<String> resolve(String ref) {
        if (!isManaged(ref)) return Optional.empty();
        Parsed parsed = parse(ref);
        if (parsed == null) return Optional.empty();
        return client.readField(parsed.path, parsed.field);
    }

    /** Permanently delete the secret at the given vault ref. No-op for non-vault refs. */
    public void delete(String ref) {
        if (!isManaged(ref) || !client.isConfigured()) return;
        Parsed parsed = parse(ref);
        if (parsed == null) return;
        client.delete(parsed.path);
    }

    private static Parsed parse(String ref) {
        String body = ref.substring(VAULT_PREFIX.length());
        int hash = body.indexOf('#');
        if (hash <= 0 || hash == body.length() - 1) return null;
        return new Parsed(body.substring(0, hash), body.substring(hash + 1));
    }

    private static String sanitise(String s) {
        if (s == null) return "x";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '/') {
                b.append(c);
            } else {
                b.append('_');
            }
        }
        return b.length() == 0 ? "x" : b.toString();
    }

    private record Parsed(String path, String field) {}
}
