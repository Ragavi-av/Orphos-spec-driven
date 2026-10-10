package io.orphos.eip.docingest.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pulls documents from an S3 bucket (or any S3-compatible store — MinIO, Ceph —
 * via {@code endpoint} + {@code forcePathStyle}). Objects under {@code prefix}
 * that are a supported document type and were modified after the watermark are
 * returned for ingestion.
 *
 * <p>Config ({@code configJson}): {@code bucket} (required), {@code prefix},
 * {@code region} (default {@code us-east-1}), {@code endpoint} (optional, for
 * S3-compatible stores), {@code pathStyle} (boolean, default false).
 * Credentials ({@code credentialJson}): {@code {"accessKeyId":..,"secretAccessKey":..}}.
 */
@Component
public class S3SourceConnector implements SourceConnector {

    private static final Logger log = LogManager.getLogger(S3SourceConnector.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public SourceKind kind() { return SourceKind.S3; }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.INGEST, Capability.BROWSE, Capability.READ, Capability.WRITE);
    }

    @Override
    public ProbeResult test(Descriptor desc) {
        String bucket = str(desc.config(), "bucket");
        if (bucket.isBlank()) return ProbeResult.fail("bucket is required");
        try (S3Client s3 = buildClient(desc)) {
            ListObjectsV2Response resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(str(desc.config(), "prefix"))
                    .maxKeys(10)
                    .build());
            return ProbeResult.ok(resp.keyCount() == null ? 0 : resp.keyCount());
        } catch (Exception e) {
            log.debug("S3 probe failed for bucket {}: {}", bucket, e.toString());
            return ProbeResult.fail(rootMessage(e));
        }
    }

    @Override
    public List<RemoteEntry> list(Descriptor desc, Instant since) {
        String bucket = str(desc.config(), "bucket");
        String prefix = str(desc.config(), "prefix");
        Instant floor = since == null ? Instant.EPOCH : since;

        List<RemoteEntry> out = new ArrayList<>();
        try (S3Client s3 = buildClient(desc)) {
            String continuation = null;
            do {
                ListObjectsV2Response resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .continuationToken(continuation)
                        .build());
                for (S3Object obj : resp.contents()) {
                    String key = obj.key();
                    // Skip "directory marker" keys and anything not modified since the watermark.
                    if (key.endsWith("/")) continue;
                    Instant modified = obj.lastModified() == null ? Instant.EPOCH : obj.lastModified();
                    if (!modified.isAfter(floor)) continue;
                    String fileName = leaf(key);
                    if (!SupportedFormats.isSupported(fileName)) continue;
                    out.add(new RemoteEntry(
                            key,
                            "s3://" + bucket + "/" + key,
                            fileName,
                            obj.size() == null ? -1L : obj.size(),
                            modified));
                }
                continuation = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
            } while (continuation != null);
        }
        return out;
    }

    @Override
    public InputStream open(Descriptor desc, RemoteEntry entry) throws IOException {
        try {
            S3Client s3 = buildClient(desc);
            InputStream raw = s3.getObject(GetObjectRequest.builder()
                    .bucket(str(desc.config(), "bucket"))
                    .key(entry.key())
                    .build());
            // Close the client when the caller closes the stream.
            return new java.io.FilterInputStream(raw) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { s3.close(); }
                }
            };
        } catch (Exception e) {
            throw new IOException("S3 getObject failed for " + entry.path() + ": " + rootMessage(e), e);
        }
    }

    @Override
    public List<FolderEntry> browse(Descriptor desc, String path) {
        String bucket = str(desc.config(), "bucket");
        String base = str(desc.config(), "prefix");
        String fullPrefix = joinPrefix(base, path);
        List<FolderEntry> out = new ArrayList<>();
        try (S3Client s3 = buildClient(desc)) {
            String continuation = null;
            do {
                ListObjectsV2Response resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(fullPrefix)
                        .delimiter("/")
                        .continuationToken(continuation)
                        .build());
                for (CommonPrefix cp : resp.commonPrefixes()) {
                    // cp.prefix() is like "base/rel/sub/"; expose a connection-relative path + leaf name.
                    out.add(new FolderEntry(leafFolder(cp.prefix()), stripBase(base, cp.prefix()), true));
                }
                continuation = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
            } while (continuation != null);
        }
        out.sort(Comparator.comparing(FolderEntry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    @Override
    public void createFolder(Descriptor desc, String path) {
        String key = joinPrefix(str(desc.config(), "prefix"), path); // zero-byte "folder marker" key
        try (S3Client s3 = buildClient(desc)) {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(str(desc.config(), "bucket"))
                    .key(key)
                    .build(), RequestBody.empty());
        }
    }

    @Override
    public String write(Descriptor desc, String relPath, java.nio.file.Path sourceFile, String contentType)
            throws IOException {
        try (S3Client s3 = buildClient(desc)) {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(str(desc.config(), "bucket"))
                    .key(objectKey(str(desc.config(), "prefix"), relPath))
                    .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                    .build(), RequestBody.fromFile(sourceFile));
        } catch (Exception e) {
            throw new IOException("S3 put failed for " + relPath + ": " + rootMessage(e), e);
        }
        return relPath;
    }

    @Override
    public InputStream readObject(Descriptor desc, String relPath) throws IOException {
        try {
            S3Client s3 = buildClient(desc);
            InputStream raw = s3.getObject(GetObjectRequest.builder()
                    .bucket(str(desc.config(), "bucket"))
                    .key(objectKey(str(desc.config(), "prefix"), relPath))
                    .build());
            return new java.io.FilterInputStream(raw) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { s3.close(); }
                }
            };
        } catch (Exception e) {
            throw new IOException("S3 get failed for " + relPath + ": " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteObject(Descriptor desc, String relPath) throws IOException {
        try (S3Client s3 = buildClient(desc)) {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(str(desc.config(), "bucket"))
                    .key(objectKey(str(desc.config(), "prefix"), relPath))
                    .build());
        } catch (Exception e) {
            throw new IOException("S3 delete failed for " + relPath + ": " + rootMessage(e), e);
        }
    }

    // ── S3 client construction ────────────────────────────────────────────────

    private S3Client buildClient(Descriptor desc) {
        Map<String, Object> cfg = desc.config();
        String region = str(cfg, "region");
        if (region.isBlank()) region = "us-east-1";

        S3ClientBuilder b = S3Client.builder().region(Region.of(region));

        Creds creds = parseCreds(desc.credentialJson());
        if (creds != null) {
            b.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(creds.accessKeyId, creds.secretAccessKey)));
        }
        // else: fall back to the default credential chain (instance role / env),
        // which lets a deployment use IAM roles without storing static keys.

        String endpoint = str(cfg, "endpoint");
        if (!endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
        if (bool(cfg, "pathStyle")) b.forcePathStyle(true);
        return b.build();
    }

    private static Creds parseCreds(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(json);
            String ak = n.path("accessKeyId").asText("");
            String sk = n.path("secretAccessKey").asText("");
            if (ak.isBlank() || sk.isBlank()) return null;
            return new Creds(ak, sk);
        } catch (Exception e) {
            log.warn("Malformed S3 credential JSON — falling back to default credential chain");
            return null;
        }
    }

    private record Creds(String accessKeyId, String secretAccessKey) {}

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String leaf(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    /** Join a base prefix + connection-relative path into an S3 key-prefix ending in '/' (blank ⇒ ""). */
    private static String joinPrefix(String base, String rel) {
        String b = trimSlashes(base);
        String r = trimSlashes(rel);
        String joined = b.isEmpty() ? r : (r.isEmpty() ? b : b + "/" + r);
        return joined.isEmpty() ? "" : joined + "/";
    }

    /** Strip the connection's base prefix from a full key-prefix → connection-relative path. */
    private static String stripBase(String base, String fullPrefix) {
        String b = trimSlashes(base);
        String full = trimSlashes(fullPrefix);
        if (!b.isEmpty() && full.startsWith(b + "/")) return full.substring(b.length() + 1);
        if (!b.isEmpty() && full.equals(b)) return "";
        return full;
    }

    /** Join base prefix + connection-relative path into a full object key (no trailing slash). */
    private static String objectKey(String base, String relPath) {
        String b = trimSlashes(base);
        String r = trimSlashes(relPath);
        return b.isEmpty() ? r : (r.isEmpty() ? b : b + "/" + r);
    }

    private static String leafFolder(String prefix) {
        String p = trimSlashes(prefix);
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    private static String trimSlashes(String s) {
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

    private static boolean bool(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Boolean bb ? bb : Boolean.parseBoolean(String.valueOf(v));
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.getClass().getSimpleName() : r.getMessage();
    }
}
