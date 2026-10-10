package io.orphos.eip.docingest.storage;

import io.orphos.eip.docingest.source.FolderConnectionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Routes document storage operations to the configured backend.
 * Currently LOCAL is supported; the enum and routing logic are in place
 * to add S3 and FTP backends following the same pattern as Ragas MVP.
 */
@Component
public class StorageRouter {

    private static final Logger log = LogManager.getLogger(StorageRouter.class);

    @Value("${doc-ingest.storage.backend:LOCAL}")
    private String backendConfig;

    @Autowired
    private LocalDocumentStorage localStorage;

    @Autowired
    private FolderConnectionService folderConnections;

    /** Persisted-location prefix for a remote folder-connection store: {@code conn:<connectionId>:<relPath>}. */
    private static final String CONN_PREFIX = "conn:";

    public record StoreResult(StorageBackendType backend, String location,
                              long sizeBytes, String contentType, String checksumSha256) {}

    /** Local (or NONE) store — no connection chosen. */
    public StoreResult store(Path sourceFile, String tenantId, String relativeDir,
                             String documentId, String contentType) throws IOException {
        return store(sourceFile, tenantId, relativeDir, documentId, contentType, null);
    }

    /**
     * Stores a document under the tenant's {@code relativeDir} layout. When {@code connectionId} names a
     * remote folder connection (S3/FTP/…) the original is written there and the returned location is a
     * {@code conn:<id>:<relPath>} token; otherwise it falls back to LOCAL (or a LOCAL-kind connection).
     */
    public StoreResult store(Path sourceFile, String tenantId, String relativeDir,
                             String documentId, String contentType, String connectionId) throws IOException {
        if (resolveBackend() == StorageBackendType.NONE) {
            log.debug("Storage backend NONE — original file not persisted for {}", documentId);
            return new StoreResult(StorageBackendType.NONE, null, Files.size(sourceFile), contentType, "");
        }

        String checksum = LocalDocumentStorage.sha256Hex(sourceFile);
        long size = Files.size(sourceFile);

        if (connectionId != null && !connectionId.isBlank()) {
            FolderConnectionService.Resolved r = folderConnections.resolve(connectionId);
            if (r.kind() != io.orphos.eip.docingest.source.SourceKind.LOCAL) {
                String relPath = joinRel(relativeDir, documentId);
                String stored = r.connector().write(r.descriptor(), relPath, sourceFile, contentType);
                String location = CONN_PREFIX + connectionId + ":" + stored;
                log.debug("Stored {} to connection {} ({}) at {}", documentId, connectionId, r.kind(), stored);
                return new StoreResult(StorageBackendType.CONNECTION, location, size, contentType, checksum);
            }
            // LOCAL-kind connection → fall through to the local store below.
        }

        String location = localStorage.store(sourceFile, tenantId, relativeDir, documentId);
        return new StoreResult(StorageBackendType.LOCAL, location, size, contentType, checksum);
    }

    /** Reads a stored original by its persisted {@code location} (local absolute path or a conn: token). */
    public InputStream openForRead(String location) throws IOException {
        if (isConnLocation(location)) {
            String[] cr = parseConn(location);
            FolderConnectionService.Resolved r = folderConnections.resolve(cr[0]);
            return r.connector().readObject(r.descriptor(), cr[1]);
        }
        return localStorage.openForRead(location);
    }

    public void delete(String location) throws IOException {
        if (resolveBackend() == StorageBackendType.NONE) return;
        if (isConnLocation(location)) {
            String[] cr = parseConn(location);
            FolderConnectionService.Resolved r = folderConnections.resolve(cr[0]);
            r.connector().deleteObject(r.descriptor(), cr[1]);
            return;
        }
        localStorage.delete(location);
    }

    private static boolean isConnLocation(String location) {
        return location != null && location.startsWith(CONN_PREFIX);
    }

    /** Splits {@code conn:<id>:<relPath>} → [id, relPath]. The id ("dsc-…") never contains a colon. */
    private static String[] parseConn(String location) {
        String body = location.substring(CONN_PREFIX.length());
        int c = body.indexOf(':');
        if (c < 0) return new String[]{body, ""};
        return new String[]{body.substring(0, c), body.substring(c + 1)};
    }

    private static String joinRel(String relativeDir, String documentId) {
        if (relativeDir == null || relativeDir.isBlank()) return documentId;
        String d = relativeDir.trim();
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        return d.isEmpty() ? documentId : d + "/" + documentId;
    }

    /** Lists the top-level storage folders for a tenant (empty when backend NONE). */
    public List<String> listFolders(String tenantId) {
        if (resolveBackend() == StorageBackendType.NONE) return List.of();
        return localStorage.listFolders(tenantId);
    }

    /**
     * Explorer-style listing of the immediate sub-folders under {@code relPath}
     * (tenant-relative, '/'-separated, blank = the tenant's storage root).
     * Path-prefix navigation maps 1:1 onto an S3 bucket's delimiter listing, so
     * an S3 backend plugs into this same contract when it lands.
     */
    public List<LocalDocumentStorage.FolderEntry> browse(String tenantId, String relPath) {
        if (resolveBackend() == StorageBackendType.NONE) return List.of();
        return localStorage.browse(tenantId, relPath);
    }

    /** Creates a folder under the tenant's storage root (parents included). */
    public void createFolder(String tenantId, String relPath) throws IOException {
        StorageBackendType backend = resolveBackend();
        if (backend == StorageBackendType.NONE) {
            throw new IllegalStateException("Storage backend NONE — folders cannot be created");
        }
        localStorage.createFolder(tenantId, relPath);
    }

    /** Human-readable backend label for the folder browser's title bar. */
    public String backendLabel() {
        return resolveBackend().name();
    }

    private StorageBackendType resolveBackend() {
        try { return StorageBackendType.valueOf(backendConfig.toUpperCase()); }
        catch (IllegalArgumentException e) {
            log.warn("Unknown storage backend '{}', defaulting to LOCAL", backendConfig);
            return StorageBackendType.LOCAL;
        }
    }
}
