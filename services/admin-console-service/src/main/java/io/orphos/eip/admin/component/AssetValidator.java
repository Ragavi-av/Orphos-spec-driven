package io.orphos.eip.admin.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * B8 - Validates uploaded asset bytes against admin-configured MIME and size limits.
 *
 * <p>We deliberately don't trust the {@code Content-Type} header alone; we also
 * sniff the magic bytes for the common formats and reject mismatches. SVG is
 * accepted but its body should be sanitised (handled in the upload service).</p>
 */
@Component
public class AssetValidator {

    @Value("${admin.branding.max-logo-bytes:1048576}")
    private long maxLogoBytes;

    @Value("${admin.branding.max-background-bytes:4194304}")
    private long maxBackgroundBytes;

    @Value("${admin.branding.allowed-mime}")
    private List<String> allowedMime;

    public void validate(String kind, String contentType, byte[] bytes) {
        String label = kind == null || kind.isBlank() ? "file" : kind;
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The selected " + label + " is empty — please choose a file.");
        }
        long limit = "background".equals(kind) ? maxBackgroundBytes : maxLogoBytes;
        if (bytes.length > limit) {
            throw new IllegalArgumentException(
                    "The " + label + " is too large (" + humanBytes(bytes.length)
                            + "). Maximum allowed is " + humanBytes(limit) + ".");
        }
        if (contentType == null || !Set.copyOf(allowedMime).contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Unsupported file type" + (contentType == null ? "" : " '" + contentType + "'")
                            + ". Allowed types: " + describeAllowed() + ".");
        }
        if (!magicMatches(contentType, bytes)) {
            throw new IllegalArgumentException(
                    "The file contents don't match its declared type (" + contentType
                            + ") — it may be renamed or corrupted. Re-export it and try again.");
        }
    }

    /** Renders a byte count as a short human-readable size, e.g. "1 MB", "2.3 MB". */
    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return Math.round(b / 1024.0) + " KB";
        double mb = b / (1024.0 * 1024.0);
        return (mb == Math.floor(mb) ? String.valueOf((long) mb) : String.format("%.1f", mb)) + " MB";
    }

    /** Friendly list of accepted formats derived from the configured MIME allow-list. */
    private String describeAllowed() {
        return allowedMime.stream()
                .map(m -> switch (m.toLowerCase()) {
                    case "image/png"     -> "PNG";
                    case "image/jpeg"    -> "JPEG";
                    case "image/webp"    -> "WebP";
                    case "image/svg+xml" -> "SVG";
                    default              -> m;
                })
                .distinct()
                .reduce((a, c) -> a + ", " + c)
                .orElse("PNG, JPEG, WebP, SVG");
    }

    private static boolean magicMatches(String mime, byte[] b) {
        return switch (mime.toLowerCase()) {
            case "image/png" -> b.length > 8
                    && b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "image/jpeg" -> b.length > 3
                    && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
            case "image/webp" -> b.length > 12
                    && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            case "image/svg+xml" -> looksLikeSvg(b);
            default -> false;
        };
    }

    private static boolean looksLikeSvg(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 256)).trim().toLowerCase();
        return head.startsWith("<?xml") || head.startsWith("<svg");
    }
}
