package io.orphos.eip.admin.component;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B8 - Branding validation / sanitisation for raw CSS.
 *
 * <p>OWASP's HTML sanitizer doesn't cover stand-alone stylesheets, so we apply
 * targeted lexical rules:</p>
 * <ul>
 *   <li>Strip {@code @import}, {@code expression(...)}, {@code behavior:} and
 *       {@code javascript:} URLs.</li>
 *   <li>Block {@code url(http(s)://...)} pointing to external hosts - assets
 *       must reference {@code /api/branding/asset/...}.</li>
 *   <li>Refuse declarations longer than 64 KiB to bound DoS surface.</li>
 * </ul>
 *
 * <p>This is intentionally conservative. A "valid" CSS feature that doesn't
 * pass these filters can be re-allowed by widening the rules deliberately,
 * not by accident.</p>
 */
@Component
public class CssSanitizer {

    private static final int MAX_LEN = 64 * 1024;
    private static final Pattern AT_IMPORT = Pattern.compile("(?i)@import[^;]*;?");
    private static final Pattern EXPRESSION = Pattern.compile("(?i)expression\\s*\\([^)]*\\)");
    private static final Pattern BEHAVIOR = Pattern.compile("(?i)behavior\\s*:[^;]*;?");
    private static final Pattern JS_URL = Pattern.compile("(?i)url\\s*\\(\\s*['\"]?\\s*javascript:[^)]*\\)");
    private static final Pattern EXTERNAL_URL = Pattern.compile(
            "(?i)url\\s*\\(\\s*['\"]?\\s*https?://[^)]*\\)");
    private static final Pattern HTML_TAGS = Pattern.compile("(?i)</?\\s*(script|style|iframe|object|embed|link)[^>]*>");

    public Result sanitize(String css) {
        if (css == null || css.isBlank()) return new Result("", true, "");
        if (css.length() > MAX_LEN) {
            return new Result("", false, "CSS exceeds " + MAX_LEN + " bytes");
        }
        String out = css;
        StringBuilder issues = new StringBuilder();
        out = stripAndReport(out, AT_IMPORT, "@import removed", issues);
        out = stripAndReport(out, EXPRESSION, "expression() removed", issues);
        out = stripAndReport(out, BEHAVIOR, "behavior: removed", issues);
        out = stripAndReport(out, JS_URL, "javascript: URL removed", issues);
        out = stripAndReport(out, EXTERNAL_URL, "external URL removed", issues);
        out = stripAndReport(out, HTML_TAGS, "HTML tag removed", issues);
        return new Result(out, true, issues.toString().trim());
    }

    private static String stripAndReport(String css, Pattern p, String label, StringBuilder issues) {
        Matcher m = p.matcher(css);
        if (m.find()) {
            if (issues.length() > 0) issues.append("; ");
            issues.append(label);
        }
        return m.replaceAll("/*sanitised*/");
    }

    public record Result(String sanitized, boolean ok, String issues) {}
}
