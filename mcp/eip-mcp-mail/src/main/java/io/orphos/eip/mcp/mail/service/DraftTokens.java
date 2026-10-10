package io.orphos.eip.mcp.mail.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Stateless, HMAC-signed confirmation tokens binding a {@code mail.send_email} to a prior
 * {@code mail.draft_reply} (TO-BE design §14 send-agent contract; Jul-10 §5.1/§6.1: "send must
 * require a prior draft and a confirmation token; never a free-standing autonomous action").
 *
 * <p>A token encodes {@code itemId | tenantId | sha256(action\nsubject\nbody) | issuedAt} and is
 * signed with HMAC-SHA256 using the shared internal-service token as the key. Because it is
 * self-contained it survives an MCP-server restart and the (possibly long) human-approval delay
 * without any datastore — and it cannot be forged. {@code send_email} recomputes the content hash
 * from the arguments it is actually given and refuses if it does not match the token, so the agent
 * can only send <em>exactly what it drafted</em>, and only after drafting.</p>
 */
public final class DraftTokens {

    private static final Logger log = LogManager.getLogger(DraftTokens.class);
    private static final String HMAC = "HmacSHA256";
    /** Generous TTL so the token outlives the approval-inbox delay; still bounds replay. */
    private static final long TTL_SECONDS = 7L * 24 * 3600;

    private final byte[] key;

    public DraftTokens(String signingKey) {
        String k = (signingKey == null || signingKey.isBlank()) ? "eip-mcp-mail-unsigned-dev-key" : signingKey;
        this.key = k.getBytes(StandardCharsets.UTF_8);
    }

    /** Issues a token bound to the drafted item + content. */
    public String issue(String itemId, String tenantId, String action, String subject, String body) {
        String payload = itemId + "|" + tenantId + "|" + contentHash(action, subject, body)
                + "|" + Instant.now().getEpochSecond();
        String p = b64(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + b64(sign(p));
    }

    /** True when {@code token} was issued for exactly this item/tenant/content and has not expired. */
    public boolean verify(String token, String itemId, String tenantId, String action, String subject, String body) {
        if (token == null || token.isBlank()) return false;
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return false;
        String p = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!constantTimeEquals(b64(sign(p)), sig)) {
            return false;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return false;
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4) return false;
        String tItem = parts[0], tTenant = parts[1], tHash = parts[2];
        long issuedAt;
        try {
            issuedAt = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Instant.now().getEpochSecond() - issuedAt > TTL_SECONDS) {
            log.debug("Draft token expired for item {}", itemId);
            return false;
        }
        return tItem.equals(itemId)
                && tTenant.equals(tenantId)
                && tHash.equals(contentHash(action, subject, body));
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign draft token: " + e.getMessage(), e);
        }
    }

    private static String contentHash(String action, String subject, String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String canonical = norm(action) + "\n" + norm(subject) + "\n" + norm(body);
            return b64(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash draft content: " + e.getMessage(), e);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip();
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
