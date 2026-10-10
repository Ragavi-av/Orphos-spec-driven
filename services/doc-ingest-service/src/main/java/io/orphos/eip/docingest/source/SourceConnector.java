package io.orphos.eip.docingest.source;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A connector for a folder/storage backend. One implementation per {@link SourceKind}.
 * Implementations are stateless Spring beans — all per-connection state (bucket, prefix,
 * credentials) arrives in the {@link Descriptor}, so a single bean serves every tenant's
 * connections.
 *
 * <p>A connector may support pull-based <b>ingestion</b> ({@link #list}/{@link #open}) and/or
 * being used as a <b>storage location</b> that the folder picker can browse ({@link #browse}/
 * {@link #createFolder}) — see {@link #capabilities()}. Ingestion methods have no default
 * (every ingestion connector implements them); the folder-location methods default to
 * "unsupported" so a storage-only connector implements only what it offers.</p>
 */
public interface SourceConnector {

    SourceKind kind();

    /** What this connector can do, so the UI can offer it as an ingestion source and/or a storage folder. */
    enum Capability { INGEST, BROWSE, READ, WRITE }

    /** Default: ingestion only. Storage-capable connectors (LOCAL/S3/FTP) override to add BROWSE/READ/WRITE. */
    default Set<Capability> capabilities() { return EnumSet.of(Capability.INGEST); }

    /** Cheap connectivity + auth probe; never throws — failures come back as {@code ok=false}. */
    ProbeResult test(Descriptor desc);

    /**
     * List supported files modified strictly after {@code since}. {@code since} is
     * the connection's stored watermark ({@link Instant#EPOCH} on first run).
     * Default: unsupported (a storage-only connector need not implement it).
     */
    default List<RemoteEntry> list(Descriptor desc, Instant since) {
        throw new UnsupportedOperationException(kind() + " is not an ingestion source");
    }

    /** Open a stream to one entry's bytes. Caller copies to a temp file and closes the stream. */
    default InputStream open(Descriptor desc, RemoteEntry entry) throws IOException {
        throw new UnsupportedOperationException(kind() + " is not an ingestion source");
    }

    /** One folder in a browse listing. {@code path} is connection-relative, '/'-separated. */
    record FolderEntry(String name, String path, boolean hasChildren) {}

    /**
     * Explorer-style listing of the immediate sub-folders under {@code path} (blank = the
     * connection's root). Enables the shared "Select folder" picker over any backend.
     * Default: unsupported.
     */
    default List<FolderEntry> browse(Descriptor desc, String path) {
        throw new UnsupportedOperationException(kind() + " does not support folder browsing");
    }

    /** Create a folder under {@code path} (parents included). Default: unsupported. */
    default void createFolder(Descriptor desc, String path) throws IOException {
        throw new UnsupportedOperationException(kind() + " does not support folder creation");
    }

    // ── Storage-target operations (used when a connection is the document store) ──

    /**
     * Persist {@code sourceFile} at connection-relative {@code relPath}; returns the connection-relative
     * location token to persist (read/delete use it). Default: unsupported (a browse-only connector).
     */
    default String write(Descriptor desc, String relPath, java.nio.file.Path sourceFile, String contentType)
            throws IOException {
        throw new UnsupportedOperationException(kind() + " does not support storage writes");
    }

    /** Open a stored object at connection-relative {@code relPath} (as returned by {@link #write}). Default: unsupported. */
    default InputStream readObject(Descriptor desc, String relPath) throws IOException {
        throw new UnsupportedOperationException(kind() + " does not support storage reads");
    }

    /** Delete a stored object at connection-relative {@code relPath}. Default: unsupported. */
    default void deleteObject(Descriptor desc, String relPath) throws IOException {
        throw new UnsupportedOperationException(kind() + " does not support storage deletes");
    }

    /**
     * Everything a connector needs for one connection.
     *
     * @param config         non-secret settings (bucket, prefix, region, endpoint…), as stored in {@code configJson}
     * @param credentialJson resolved secret payload (e.g. {@code {"accessKeyId":..,"secretAccessKey":..}}); may be blank
     */
    record Descriptor(SourceKind kind, Map<String, Object> config, String credentialJson) {}

    /** Outcome of {@link #test}. */
    record ProbeResult(boolean ok, String message, int sampleCount) {
        public static ProbeResult ok(int sampleCount)      { return new ProbeResult(true, "OK", sampleCount); }
        public static ProbeResult fail(String message)     { return new ProbeResult(false, message, 0); }
    }
}
