package io.orphos.eip.admin.dbconnections.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.orphos.eip.core.dbregistry.ConnectionRegistry;
import io.orphos.eip.core.dbregistry.ConnectionRegistryLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Reads and atomically writes the connections registry file — the authoritative
 * store for connection definitions and service→connection assignments. The
 * bootstrap resolver in {@code eip-core-dbregistry} reads the same file at each
 * service's startup.
 *
 * <p>Writes go to a temp sibling and are moved into place (atomic where the OS
 * supports it) so a concurrent reader never sees a half-written file. The path
 * defaults to {@code /run/eip/connections.json} and must be a read-write mount
 * for this (platform-core) container.</p>
 */
@Component
public class ConnectionsFileStore {

    private static final Logger log = LogManager.getLogger(ConnectionsFileStore.class);

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ConnectionRegistryLoader loader = new ConnectionRegistryLoader();
    private final Path path;

    public ConnectionsFileStore(
            @Value("${eip.connections.file:" + ConnectionRegistryLoader.DEFAULT_FILE + "}") String filePath) {
        this.path = Path.of(filePath);
    }

    /** Current registry, or an empty one if the file is absent/unparseable. */
    public synchronized ConnectionRegistry.File read() {
        return loader.load(path.toString()).orElseGet(() -> new ConnectionRegistry.File(List.of(), Map.of()));
    }

    /** Atomically replace the registry file. */
    public synchronized void write(ConnectionRegistry.File file) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(file), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not write connections registry file " + path + " — is it mounted read-write? ("
                            + e.getMessage() + ")", e);
        }
    }

    public Path path() {
        return path;
    }
}
