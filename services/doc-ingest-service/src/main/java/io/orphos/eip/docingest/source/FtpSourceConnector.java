package io.orphos.eip.docingest.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FTP folder connection (Apache commons-net). Usable as an ingestion source (list/open) and as a
 * browsable storage location (browse/createFolder).
 *
 * <p>Config ({@code configJson}): {@code host} (required), {@code port} (default 21),
 * {@code basePath} (default "/"), {@code passive} (boolean, default true).
 * Credentials ({@code credentialJson}): {@code {"username":..,"password":..}} — blank ⇒ anonymous.</p>
 */
@Component
public class FtpSourceConnector implements SourceConnector {

    private static final Logger log = LogManager.getLogger(FtpSourceConnector.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public SourceKind kind() { return SourceKind.FTP; }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.INGEST, Capability.BROWSE, Capability.READ, Capability.WRITE);
    }

    @Override
    public ProbeResult test(Descriptor desc) {
        String host = str(desc.config(), "host");
        if (host.isBlank()) return ProbeResult.fail("host is required");
        FTPClient c = null;
        try {
            c = connect(desc);
            FTPFile[] listed = c.listFiles(basePath(desc));
            return ProbeResult.ok(listed == null ? 0 : listed.length);
        } catch (Exception e) {
            return ProbeResult.fail(rootMessage(e));
        } finally {
            quietDisconnect(c);
        }
    }

    @Override
    public List<FolderEntry> browse(Descriptor desc, String path) {
        String full = join(basePath(desc), path);
        FTPClient c = null;
        try {
            c = connect(desc);
            List<FolderEntry> out = new ArrayList<>();
            FTPFile[] dirs = c.listDirectories(full);
            if (dirs != null) {
                for (FTPFile d : dirs) {
                    String name = d.getName();
                    if (name == null || name.equals(".") || name.equals("..")) continue;
                    String rel = (path == null || path.isBlank()) ? name : trim(path) + "/" + name;
                    out.add(new FolderEntry(name, rel, true));
                }
            }
            out.sort(Comparator.comparing(FolderEntry::name, String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (Exception e) {
            log.debug("FTP browse '{}' failed: {}", full, e.toString());
            return List.of();
        } finally {
            quietDisconnect(c);
        }
    }

    @Override
    public void createFolder(Descriptor desc, String path) throws IOException {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Folder path is required");
        String full = join(basePath(desc), path);
        FTPClient c = null;
        try {
            c = connect(desc);
            if (!c.makeDirectory(full)) {
                throw new IOException("FTP MKD failed for " + full + " (" + c.getReplyString().trim() + ")");
            }
        } finally {
            quietDisconnect(c);
        }
    }

    @Override
    public List<RemoteEntry> list(Descriptor desc, Instant since) {
        Instant floor = since == null ? Instant.EPOCH : since;
        String base = basePath(desc);
        FTPClient c = null;
        try {
            c = connect(desc);
            List<RemoteEntry> out = new ArrayList<>();
            FTPFile[] files = c.listFiles(base);
            if (files != null) {
                for (FTPFile f : files) {
                    if (f == null || !f.isFile()) continue;
                    String name = f.getName();
                    if (!SupportedFormats.isSupported(name)) continue;
                    Instant modified = f.getTimestamp() == null ? Instant.EPOCH : f.getTimestamp().toInstant();
                    if (!modified.isAfter(floor)) continue;
                    String key = join(base, name);
                    out.add(new RemoteEntry(key, "ftp:" + key, name, f.getSize(), modified));
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("FTP list '{}' failed: {}", base, e.toString());
            return List.of();
        } finally {
            quietDisconnect(c);
        }
    }

    @Override
    public InputStream open(Descriptor desc, RemoteEntry entry) throws IOException {
        FTPClient c = connect(desc);
        InputStream raw = c.retrieveFileStream(entry.key());
        if (raw == null) {
            quietDisconnect(c);
            throw new IOException("FTP retrieve failed for " + entry.key() + " (" + c.getReplyString().trim() + ")");
        }
        // Complete the FTP command + disconnect when the caller closes the stream.
        FTPClient client = c;
        return new java.io.FilterInputStream(raw) {
            @Override public void close() throws IOException {
                try { super.close(); client.completePendingCommand(); }
                finally { quietDisconnect(client); }
            }
        };
    }

    @Override
    public String write(Descriptor desc, String relPath, java.nio.file.Path sourceFile, String contentType)
            throws IOException {
        String full = join(basePath(desc), relPath);
        FTPClient c = null;
        try {
            c = connect(desc);
            makeParentDirs(c, full);
            try (InputStream in = java.nio.file.Files.newInputStream(sourceFile)) {
                if (!c.storeFile(full, in)) {
                    throw new IOException("FTP STOR failed for " + full + " (" + c.getReplyString().trim() + ")");
                }
            }
        } finally {
            quietDisconnect(c);
        }
        return relPath;
    }

    @Override
    public InputStream readObject(Descriptor desc, String relPath) throws IOException {
        String full = join(basePath(desc), relPath);
        FTPClient c = connect(desc);
        InputStream raw = c.retrieveFileStream(full);
        if (raw == null) {
            quietDisconnect(c);
            throw new IOException("FTP retrieve failed for " + full + " (" + c.getReplyString().trim() + ")");
        }
        FTPClient client = c;
        return new java.io.FilterInputStream(raw) {
            @Override public void close() throws IOException {
                try { super.close(); client.completePendingCommand(); }
                finally { quietDisconnect(client); }
            }
        };
    }

    @Override
    public void deleteObject(Descriptor desc, String relPath) throws IOException {
        String full = join(basePath(desc), relPath);
        FTPClient c = null;
        try {
            c = connect(desc);
            c.deleteFile(full);
        } finally {
            quietDisconnect(c);
        }
    }

    /** Best-effort recursive mkdir of the parent directory chain of a file path. */
    private static void makeParentDirs(FTPClient c, String fullFilePath) throws IOException {
        int slash = fullFilePath.lastIndexOf('/');
        if (slash <= 0) return;
        StringBuilder cur = new StringBuilder();
        for (String seg : fullFilePath.substring(0, slash).split("/")) {
            if (seg.isBlank()) continue;
            cur.append('/').append(seg);
            c.makeDirectory(cur.toString()); // ignore result — already-exists is fine
        }
    }

    // ── connection ─────────────────────────────────────────────────────────────

    private FTPClient connect(Descriptor desc) throws IOException {
        Map<String, Object> cfg = desc.config();
        String host = str(cfg, "host");
        int port = intVal(cfg, "port", 21);
        FTPClient c = new FTPClient();
        c.connect(host, port);
        if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
            int code = c.getReplyCode();
            quietDisconnect(c);
            throw new IOException("FTP server refused connection (code " + code + ")");
        }
        Creds creds = parseCreds(desc.credentialJson());
        String user = creds == null ? "anonymous" : creds.username;
        String pass = creds == null ? "" : creds.password;
        if (!c.login(user, pass)) {
            quietDisconnect(c);
            throw new IOException("FTP login failed for user " + user);
        }
        if (bool(cfg, "passive", true)) c.enterLocalPassiveMode();
        c.setFileType(FTP.BINARY_FILE_TYPE);
        return c;
    }

    private static void quietDisconnect(FTPClient c) {
        if (c == null) return;
        try { if (c.isConnected()) { c.logout(); c.disconnect(); } } catch (Exception ignored) { }
    }

    private static String basePath(Descriptor desc) {
        String bp = str(desc.config(), "basePath");
        return bp.isBlank() ? "/" : bp;
    }

    private static Creds parseCreds(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(json);
            String u = n.path("username").asText("");
            if (u.isBlank()) return null;
            return new Creds(u, n.path("password").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    private record Creds(String username, String password) {}

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String join(String base, String rel) {
        String b = trim(base);
        String r = trim(rel);
        if (r.isEmpty()) return b.isEmpty() ? "/" : "/" + b;
        String joined = b.isEmpty() ? r : b + "/" + r;
        return "/" + joined;
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static int intVal(Map<String, Object> m, String k, int def) {
        Object v = m == null ? null : m.get(k);
        if (v == null) return def;
        try { return v instanceof Number nn ? nn.intValue() : Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        if (v == null) return def;
        return v instanceof Boolean bb ? bb : Boolean.parseBoolean(String.valueOf(v));
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.getClass().getSimpleName() : r.getMessage();
    }
}
