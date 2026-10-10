package io.orphos.eip.docingest.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
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
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

/**
 * SFTP folder connection (SSH File Transfer) via jsch. Usable as an ingestion source (list/open)
 * and as a browsable storage location (browse/createFolder/write/read/delete).
 *
 * <p>Config ({@code configJson}): {@code host} (required), {@code port} (default 22),
 * {@code basePath} (default "/"), {@code strictHostKey} (boolean, default false).
 * Credentials ({@code credentialJson}): {@code {"username":..,"password":..}}.</p>
 */
@Component
public class SftpSourceConnector implements SourceConnector {

    private static final Logger log = LogManager.getLogger(SftpSourceConnector.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public SourceKind kind() { return SourceKind.SFTP; }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.INGEST, Capability.BROWSE, Capability.READ, Capability.WRITE);
    }

    @Override
    public ProbeResult test(Descriptor desc) {
        if (str(desc.config(), "host").isBlank()) return ProbeResult.fail("host is required");
        Sftp s = null;
        try {
            s = connect(desc);
            Vector<ChannelSftp.LsEntry> listed = ls(s.channel, basePath(desc));
            return ProbeResult.ok(listed == null ? 0 : listed.size());
        } catch (Exception e) {
            return ProbeResult.fail(rootMessage(e));
        } finally {
            close(s);
        }
    }

    @Override
    public List<FolderEntry> browse(Descriptor desc, String path) {
        String full = join(basePath(desc), path);
        Sftp s = null;
        try {
            s = connect(desc);
            List<FolderEntry> out = new ArrayList<>();
            for (ChannelSftp.LsEntry e : ls(s.channel, full)) {
                String name = e.getFilename();
                if (name == null || name.equals(".") || name.equals("..")) continue;
                if (!e.getAttrs().isDir()) continue;
                String rel = (path == null || path.isBlank()) ? name : trim(path) + "/" + name;
                out.add(new FolderEntry(name, rel, true));
            }
            out.sort(Comparator.comparing(FolderEntry::name, String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (Exception e) {
            log.debug("SFTP browse '{}' failed: {}", full, e.toString());
            return List.of();
        } finally {
            close(s);
        }
    }

    @Override
    public void createFolder(Descriptor desc, String path) throws IOException {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Folder path is required");
        Sftp s = null;
        try {
            s = connect(desc);
            mkdirs(s.channel, join(basePath(desc), path));
        } catch (Exception e) {
            throw new IOException("SFTP createFolder failed: " + rootMessage(e), e);
        } finally {
            close(s);
        }
    }

    @Override
    public String write(Descriptor desc, String relPath, java.nio.file.Path sourceFile, String contentType)
            throws IOException {
        String full = join(basePath(desc), relPath);
        Sftp s = null;
        try {
            s = connect(desc);
            int slash = full.lastIndexOf('/');
            if (slash > 0) mkdirs(s.channel, full.substring(0, slash));
            try (InputStream in = java.nio.file.Files.newInputStream(sourceFile)) {
                s.channel.put(in, full);
            }
            return relPath;
        } catch (Exception e) {
            throw new IOException("SFTP write failed for " + full + ": " + rootMessage(e), e);
        } finally {
            close(s);
        }
    }

    @Override
    public InputStream readObject(Descriptor desc, String relPath) throws IOException {
        String full = join(basePath(desc), relPath);
        Sftp s = connect(desc);
        try {
            InputStream raw = s.channel.get(full);
            // Keep the session open until the caller closes the stream.
            final Sftp session = s;
            return new java.io.FilterInputStream(raw) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { SftpSourceConnector.close(session); }
                }
            };
        } catch (Exception e) {
            close(s);
            throw new IOException("SFTP read failed for " + full + ": " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteObject(Descriptor desc, String relPath) throws IOException {
        String full = join(basePath(desc), relPath);
        Sftp s = null;
        try {
            s = connect(desc);
            s.channel.rm(full);
        } catch (Exception e) {
            throw new IOException("SFTP delete failed for " + full + ": " + rootMessage(e), e);
        } finally {
            close(s);
        }
    }

    @Override
    public List<RemoteEntry> list(Descriptor desc, Instant since) {
        Instant floor = since == null ? Instant.EPOCH : since;
        String base = basePath(desc);
        Sftp s = null;
        try {
            s = connect(desc);
            List<RemoteEntry> out = new ArrayList<>();
            for (ChannelSftp.LsEntry e : ls(s.channel, base)) {
                if (e.getAttrs().isDir()) continue;
                String name = e.getFilename();
                if (!SupportedFormats.isSupported(name)) continue;
                Instant modified = Instant.ofEpochSecond(e.getAttrs().getMTime());
                if (!modified.isAfter(floor)) continue;
                String rel = base.equals("/") || base.isBlank() ? name : trim(base) + "/" + name;
                out.add(new RemoteEntry(rel, "sftp:" + join(base, name), name, e.getAttrs().getSize(), modified));
            }
            return out;
        } catch (Exception e) {
            log.debug("SFTP list failed: {}", e.toString());
            return List.of();
        } finally {
            close(s);
        }
    }

    @Override
    public InputStream open(Descriptor desc, RemoteEntry entry) throws IOException {
        return readObject(desc, entry.key());
    }

    // ── connection ─────────────────────────────────────────────────────────────

    private record Sftp(Session session, ChannelSftp channel) {}

    private Sftp connect(Descriptor desc) throws IOException {
        Map<String, Object> cfg = desc.config();
        Creds creds = parseCreds(desc.credentialJson());
        String user = creds == null ? "" : creds.username;
        try {
            JSch jsch = new JSch();
            Session session = jsch.getSession(user, str(cfg, "host"), intVal(cfg, "port", 22));
            if (creds != null && creds.password != null) session.setPassword(creds.password);
            Properties props = new Properties();
            props.put("StrictHostKeyChecking", bool(cfg, "strictHostKey", false) ? "yes" : "no");
            session.setConfig(props);
            session.setTimeout(20000);
            session.connect();
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(20000);
            return new Sftp(session, channel);
        } catch (Exception e) {
            throw new IOException("SFTP connect failed: " + rootMessage(e), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Vector<ChannelSftp.LsEntry> ls(ChannelSftp c, String path) throws Exception {
        return c.ls(path == null || path.isBlank() ? "." : path);
    }

    /** Recursive mkdir; ignores "already exists". */
    private static void mkdirs(ChannelSftp c, String dirPath) throws Exception {
        StringBuilder cur = new StringBuilder();
        for (String seg : dirPath.split("/")) {
            if (seg.isBlank()) continue;
            cur.append('/').append(seg);
            try { c.mkdir(cur.toString()); } catch (Exception ignored) { /* exists */ }
        }
    }

    private static void close(Sftp s) {
        if (s == null) return;
        try { if (s.channel != null) s.channel.disconnect(); } catch (Exception ignored) { }
        try { if (s.session != null) s.session.disconnect(); } catch (Exception ignored) { }
    }

    private static Creds parseCreds(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(json);
            String u = n.path("username").asText("");
            if (u.isBlank()) return null;
            return new Creds(u, n.path("password").asText(""));
        } catch (Exception e) { return null; }
    }

    private record Creds(String username, String password) {}

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String basePath(Descriptor desc) {
        String bp = str(desc.config(), "basePath");
        return bp.isBlank() ? "/" : bp;
    }

    private static String join(String base, String rel) {
        String b = trim(base), r = trim(rel);
        if (r.isEmpty()) return b.isEmpty() ? "/" : "/" + b;
        return "/" + (b.isEmpty() ? r : b + "/" + r);
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
