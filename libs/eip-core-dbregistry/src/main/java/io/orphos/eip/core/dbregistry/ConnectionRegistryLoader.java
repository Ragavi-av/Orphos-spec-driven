package io.orphos.eip.core.dbregistry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads and parses the connections registry file. Kept free of any Spring
 * context so it can run at the earliest bootstrap phase (from an
 * {@link org.springframework.boot.env.EnvironmentPostProcessor}) as well as
 * from ordinary beans.
 *
 * <p>A missing, empty, or malformed file yields {@link Optional#empty()} and a
 * warning — never an exception — so a service always degrades to its
 * {@code ${ARCADEDB_URL:default}} environment behaviour rather than failing to
 * boot.</p>
 */
public final class ConnectionRegistryLoader {

    /** Default on-disk location; overridable via {@code eip.connections.file} / {@code EIP_CONNECTIONS_FILE}. */
    public static final String DEFAULT_FILE = "/run/eip/connections.json";

    private static final Logger log = LogManager.getLogger(ConnectionRegistryLoader.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public Optional<ConnectionRegistry.File> load(String filePath) {
        String p = (filePath == null || filePath.isBlank()) ? DEFAULT_FILE : filePath.trim();
        Path path = Path.of(p);
        if (!Files.isReadable(path)) {
            // Absent file is the normal zero-config case — debug, not warn.
            log.debug("Connections registry file {} not present/readable; using environment defaults", p);
            return Optional.empty();
        }
        try {
            String json = Files.readString(path);
            if (json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(mapper.readValue(json, ConnectionRegistry.File.class));
        } catch (Exception e) {
            log.warn("Connections registry file {} could not be parsed ({}); using environment defaults",
                    p, e.getMessage());
            return Optional.empty();
        }
    }
}
