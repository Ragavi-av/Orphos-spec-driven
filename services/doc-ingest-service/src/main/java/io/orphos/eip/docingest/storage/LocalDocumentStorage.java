package io.orphos.eip.docingest.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stores documents in a configurable local directory, optionally encrypted
 * with AES-256-GCM. Files are organised as
 * {baseDir}/{tenantId}/{relativeDir}/{documentId}, where {relativeDir} is the
 * tenant's folder / department / file-type layout (may be empty). Because the
 * physical path now varies per document, reads and deletes operate on the
 * absolute location returned by {@link #store} (persisted in the registry),
 * not a recomputed path.
 */
@Component
public class LocalDocumentStorage {

    private static final Logger log = LogManager.getLogger(LocalDocumentStorage.class);

    @Value("${doc-ingest.storage.local.base-dir:/data/doc-ingest}")
    private String baseDir;

    @Value("${doc-ingest.storage.local.encrypt:false}")
    private boolean encrypt;

    @Value("${doc-ingest.storage.local.encryption-key-base64:}")
    private String encryptionKeyBase64;

    private SecretKey secretKey;

    @jakarta.annotation.PostConstruct
    void init() throws IOException {
        if (encrypt) {
            secretKey = AesGcmFileCrypto.keyFromBase64(encryptionKeyBase64);
            log.info("Local document storage: encryption ENABLED");
        }
        Files.createDirectories(Paths.get(baseDir));
        log.info("Local document storage base dir: {}", baseDir);
    }

    /**
     * Stores {@code source} under {baseDir}/{tenantId}/{relativeDir}/{documentId},
     * applying AES-256-GCM encryption if configured. {@code relativeDir} segments
     * are sanitised to block path traversal; blank/null nests directly under the
     * tenant directory.
     *
     * @return the absolute storage path (persist this for later read/delete)
     */
    public String store(Path source, String tenantId, String relativeDir, String documentId)
            throws IOException {
        Path dir = Paths.get(baseDir, sanitizeSegment(tenantId));
        if (relativeDir != null && !relativeDir.isBlank()) {
            for (String seg : relativeDir.split("/")) {
                if (!seg.isBlank()) dir = dir.resolve(sanitizeSegment(seg));
            }
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(documentId);

        if (encrypt) {
            AesGcmFileCrypto.encrypt(secretKey, source, target);
        } else {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("Stored document {} for tenant {} at {}", documentId, tenantId, target);
        return target.toString();
    }

    /**
     * Opens a stored document for reading by its absolute {@code location}
     * (the value returned by {@link #store}), decrypting on the fly if needed.
     */
    public InputStream openForRead(String location) throws IOException {
        InputStream raw = Files.newInputStream(Paths.get(location));
        return encrypt ? AesGcmFileCrypto.decryptingStream(secretKey, raw) : raw;
    }

    public void delete(String location) throws IOException {
        if (location == null || location.isBlank()) return;
        Files.deleteIfExists(Paths.get(location));
    }

    /**
     * Lists the immediate sub-directory names directly under the tenant's
     * storage root — these are the top-level "folder" segments of the storage
     * layout, exactly what the ingestion "Default folder" represents. Returns
     * an empty list when the tenant has no storage directory yet.
     */
    public List<String> listFolders(String tenantId) {
        Path tenantDir = Paths.get(baseDir, sanitizeSegment(tenantId));
        if (!Files.isDirectory(tenantDir)) return List.of();
        try (Stream<Path> entries = Files.list(tenantDir)) {
            return entries
                .filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        } catch (IOException e) {
            log.warn("Failed to list folders for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Lists the immediate sub-directories under {tenantRoot}/{relPath} for the
     * Explorer-style folder browser. Each segment of {@code relPath} is
     * sanitised, so a crafted path can never escape the tenant's root.
     * Returns an empty list when the directory doesn't exist yet.
     */
    public List<FolderEntry> browse(String tenantId, String relPath) {
        Path dir = resolveTenantPath(tenantId, relPath);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                .filter(Files::isDirectory)
                .map(p -> {
                    String name = p.getFileName().toString();
                    boolean hasChildren;
                    try (Stream<Path> kids = Files.list(p)) {
                        hasChildren = kids.anyMatch(Files::isDirectory);
                    } catch (IOException e) {
                        hasChildren = false;
                    }
                    String childPath = relPath == null || relPath.isBlank() ? name : relPath + "/" + name;
                    return new FolderEntry(name, childPath, hasChildren);
                })
                .sorted(java.util.Comparator.comparing(FolderEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        } catch (IOException e) {
            log.warn("Failed to browse '{}' for tenant {}: {}", relPath, tenantId, e.getMessage());
            return List.of();
        }
    }

    /** Creates {tenantRoot}/{relPath} (all missing parents included). */
    public void createFolder(String tenantId, String relPath) throws IOException {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("Folder path is required");
        }
        Files.createDirectories(resolveTenantPath(tenantId, relPath));
    }

    /** {tenantRoot}/{relPath} with every segment sanitised — traversal-safe by construction. */
    private Path resolveTenantPath(String tenantId, String relPath) {
        Path dir = Paths.get(baseDir, sanitizeSegment(tenantId));
        if (relPath != null && !relPath.isBlank()) {
            for (String seg : relPath.split("/")) {
                if (!seg.isBlank()) dir = dir.resolve(sanitizeSegment(seg));
            }
        }
        return dir;
    }

    /** One folder in the browse listing. {@code path} is tenant-relative, '/'-separated. */
    public record FolderEntry(String name, String path, boolean hasChildren) {}

    /** Strips path separators and traversal so a folder/department name can't escape baseDir. */
    private static String sanitizeSegment(String s) {
        if (s == null) return "_";
        String cleaned = s.replaceAll("[\\\\/]", "_").replace("..", "_").trim();
        return cleaned.isBlank() ? "_" : cleaned;
    }

    public static String sha256Hex(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }
}
