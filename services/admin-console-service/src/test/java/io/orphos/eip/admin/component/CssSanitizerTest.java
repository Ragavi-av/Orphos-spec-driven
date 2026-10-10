package io.orphos.eip.admin.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CssSanitizerTest {

    private final CssSanitizer s = new CssSanitizer();

    @Test
    void stripsAtImport() {
        var r = s.sanitize("@import url('http://evil.example/x.css'); body { color: red; }");
        assertTrue(r.ok());
        assertFalse(r.sanitized().toLowerCase().contains("@import"));
        assertTrue(r.issues().contains("@import removed"));
    }

    @Test
    void stripsJavascriptUrls() {
        var r = s.sanitize("body { background: url(javascript:alert(1)); }");
        assertFalse(r.sanitized().toLowerCase().contains("javascript:"));
    }

    @Test
    void stripsHtmlTagsEmbeddedInCss() {
        var r = s.sanitize("body{}</style><script>alert(1)</script>");
        assertFalse(r.sanitized().contains("<script"));
    }

    @Test
    void rejectsOversizedCss() {
        String big = "a".repeat(65 * 1024);
        var r = s.sanitize(big);
        assertFalse(r.ok());
    }

    @Test
    void retainsBenignDeclarations() {
        var r = s.sanitize(":root { --primary: #112233; } body { color: var(--primary); }");
        assertTrue(r.ok());
        assertTrue(r.sanitized().contains("--primary"));
        assertTrue(r.issues().isEmpty());
    }
}
