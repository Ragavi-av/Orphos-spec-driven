package io.orphos.eip.webbff.client;

import io.orphos.eip.common.http.EipHttpHeaders;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's own credentials, captured once per request and replayed on every
 * downstream call.
 *
 * <p>This BFF deliberately holds no service account of its own: it forwards the
 * browser's bearer token and tenant header unchanged, so downstream authorization
 * and tenant scoping decide exactly what they would have for a direct call. Adding a
 * privileged identity here would turn the BFF into a way to read past those checks.</p>
 *
 * @param authorization the raw {@code Authorization} header, or {@code ""} when absent
 * @param tenantId      the raw {@code X-Tenant-Id} header, or {@code ""} when absent
 */
public record CallerIdentity(String authorization, String tenantId) {

    public static CallerIdentity from(HttpServletRequest request) {
        return new CallerIdentity(
                header(request, "Authorization"),
                header(request, EipHttpHeaders.TENANT_ID));
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value != null ? value : "";
    }
}
