package io.orphos.eip.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Comparison of the shared internal-service token, for in-controller checks. */
public final class InternalTokens {

    private InternalTokens() {}

    /**
     * Constant-time comparison: {@code String.equals} returns at the first differing
     * character, which lets a caller who can reach the endpoint recover the token byte
     * by byte from response timings. A blank expected token never matches.
     */
    public static boolean matches(String expected, String supplied) {
        if (expected == null || expected.isBlank() || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
