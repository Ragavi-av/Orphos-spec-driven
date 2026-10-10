package io.orphos.eip.docingest.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.docingest.secrets.SecretRefService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Resolves a stored folder connection (from the shared pool) into a ready-to-use
 * {@link SourceConnector} + {@link SourceConnector.Descriptor}, so both the storage layer
 * (StorageRouter) and the admin API can act on a connection by id without duplicating the
 * config-parse + credential-resolve dance. Tenant-scoped via {@link SourceConnectionRepository}.
 */
@Service
public class FolderConnectionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourceConnectionRepository repo;
    private final SourceConnectorRegistry registry;
    private final SecretRefService secrets;

    public FolderConnectionService(SourceConnectionRepository repo,
                                   SourceConnectorRegistry registry,
                                   SecretRefService secrets) {
        this.repo = repo;
        this.registry = registry;
        this.secrets = secrets;
    }

    /** A connection resolved to its connector + descriptor (config + decrypted credential). */
    public record Resolved(String id, SourceKind kind, SourceConnector connector,
                           SourceConnector.Descriptor descriptor) {}

    /**
     * @throws NoSuchElementException        if the connection id is unknown for this tenant
     * @throws UnsupportedOperationException if its kind has no connector implementation yet
     */
    public Resolved resolve(String connectionId) {
        Map<String, Object> row = repo.findById(connectionId);
        if (row == null) {
            throw new NoSuchElementException("Unknown folder connection: " + connectionId);
        }
        SourceKind kind;
        try {
            kind = SourceKind.valueOf(String.valueOf(row.get("kind")));
        } catch (IllegalArgumentException e) {
            throw new UnsupportedOperationException("Unknown connection kind: " + row.get("kind"));
        }
        if (!registry.supports(kind)) {
            throw new UnsupportedOperationException(kind + " connector is not implemented yet");
        }
        Map<String, Object> config = parseConfig(String.valueOf(row.getOrDefault("configJson", "")));
        String ref = String.valueOf(row.getOrDefault("credentialRef", ""));
        String credentialJson = (ref != null && !ref.isBlank()) ? secrets.resolve(ref).orElse("") : "";
        return new Resolved(connectionId, kind, registry.require(kind),
                new SourceConnector.Descriptor(kind, config, credentialJson));
    }

    private static Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return JSON.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return Map.of(); }
    }
}
