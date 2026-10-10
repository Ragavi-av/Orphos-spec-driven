package io.orphos.eip.admin.auth.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports whether Keycloak is reachable from admin-service and exposes the
 * key SSO endpoints the SPA renders in the SSO Configuration tab.
 *
 * <p>The reachability probe runs INSIDE the Docker network (using
 * {@code eip-keycloak:8080}); the response carries the BROWSER-FACING URL
 * ({@code http://localhost:8443}) — the SPA needs that for "open admin
 * console" links and diagnostic copy. This matches the multi-issuer JWT
 * split: external URL for the iss claim and the SPA, internal URL for
 * JWKS / discovery fetches.</p>
 *
 * <p>A {@code ?realm=...} query parameter overrides the default realm so
 * the per-tenant SSO configuration UI can probe any tenant's realm.</p>
 */
@RestController
@RequestMapping("/api/admin/sso")
@PreAuthorize("hasAnyRole('admin','platform_admin','secops_admin')")
public class AdminSsoStatusController {

    private static final Logger log = LogManager.getLogger(AdminSsoStatusController.class);

    /** External (browser-facing) Keycloak URL — what users see and what the JWT iss claim carries. */
    @Value("${eip.security.keycloak-issuer-base:http://localhost:8443}")
    private String publicKeycloakUrl;

    /** Internal Keycloak URL — used for the reachability probe and discovery fetch. */
    @Value("${eip.security.internal-issuer-base:${KEYCLOAK_URL:http://eip-keycloak:8080}}")
    private String internalKeycloakUrl;

    /** Default realm to probe when no explicit one is supplied. */
    @Value("${eip.tenant.demo.realm:eip}")
    private String defaultRealm;

    /** Keycloak admin username for the master realm. */
    @Value("${KEYCLOAK_ADMIN_USER:admin}")
    private String keycloakAdminUser;

    /** Keycloak admin password — injected from OpenBao at startup. */
    @Value("${KEYCLOAK_ADMIN_PASSWORD:}")
    private String keycloakAdminPassword;

    private final RestTemplate rest;

    public AdminSsoStatusController() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Fail fast if Keycloak is down so the UI shows the banner quickly
        // instead of hanging the tab on the default 60 s socket timeout.
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        this.rest = new RestTemplate(factory);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestParam(name = "realm", required = false) String realmParam) {
        String realm        = (realmParam != null && !realmParam.isBlank()) ? realmParam.trim() : defaultRealm;
        String publicBase   = trim(publicKeycloakUrl);
        String internalBase = trim(internalKeycloakUrl);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keycloakUrl", publicBase);
        out.put("realm",       realm);
        out.put("issuer",      publicBase + "/realms/" + realm);
        out.put("clientId",    "orphos-spa");
        // Tri-state: TRUE = an OIDC broker IdP exists, FALSE = realm queried and
        // none found, null = could not determine (admin API unavailable). null is
        // serialised as JSON null so the SPA shows "Unknown" rather than a
        // misleading "Not configured" when the check simply couldn't run.
        out.put("azureIdpConfigured", detectAzureIdp(internalBase, realm));

        try {
            String discoveryUri = internalBase + "/realms/" + realm + "/.well-known/openid-configuration";
            @SuppressWarnings("unchecked")
            Map<String, Object> discovery = rest.getForObject(URI.create(discoveryUri), Map.class);
            if (discovery == null) {
                out.put("keycloakReachable", false);
                out.put("error", "Discovery returned empty body");
                return ResponseEntity.ok(out);
            }
            // Rewrite discovery URLs back to the public base — they come back
            // referencing the internal hostname because we probed via it.
            out.put("keycloakReachable",     true);
            out.put("jwksUri",               rewriteToPublic((String) discovery.get("jwks_uri"),               internalBase, publicBase));
            out.put("authorizationEndpoint", rewriteToPublic((String) discovery.get("authorization_endpoint"), internalBase, publicBase));
            out.put("tokenEndpoint",         rewriteToPublic((String) discovery.get("token_endpoint"),         internalBase, publicBase));
            out.put("endSessionEndpoint",    rewriteToPublic((String) discovery.get("end_session_endpoint"),   internalBase, publicBase));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("Keycloak reachability probe failed at {}/realms/{}: {}",
                    internalBase, realm, e.getMessage());
            out.put("keycloakReachable", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return ResponseEntity.ok(out);
        }
    }

    private static String trim(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String rewriteToPublic(String url, String internalBase, String publicBase) {
        if (url == null) return null;
        if (internalBase != null && !internalBase.isBlank() && url.startsWith(internalBase)) {
            return publicBase + url.substring(internalBase.length());
        }
        return url;
    }

    /**
     * Detects whether the given realm has an OIDC broker (Azure Entra) identity
     * provider configured, via the Keycloak Admin REST API.
     *
     * <p>Returns {@code TRUE} when at least one enabled {@code oidc} IdP exists,
     * {@code FALSE} when the realm was queried successfully and none was found,
     * and {@code null} when the check could not run (admin password blank,
     * Keycloak unreachable, permission denied). The {@code null} case is
     * deliberately distinct from {@code FALSE} so the UI does not report
     * "Not configured" when it merely failed to look — a working SSO login
     * with a broken admin probe would otherwise show a false negative.</p>
     */
    @SuppressWarnings("unchecked")
    private Boolean detectAzureIdp(String internalBase, String realm) {
        if (keycloakAdminPassword == null || keycloakAdminPassword.isBlank()) {
            log.debug("KEYCLOAK_ADMIN_PASSWORD not set — cannot determine IdP status");
            return null;
        }
        try {
            // 1. Get a short-lived admin token from the master realm
            String tokenUri = internalBase + "/realms/master/protocol/openid-connect/token";
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "password");
            form.add("client_id",  "admin-cli");
            form.add("username",   keycloakAdminUser);
            form.add("password",   keycloakAdminPassword);
            HttpHeaders formHeaders = new HttpHeaders();
            formHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            Map<String, Object> tokenResp = rest.postForObject(
                URI.create(tokenUri), new HttpEntity<>(form, formHeaders), Map.class);
            if (tokenResp == null || !tokenResp.containsKey("access_token")) return null;
            String accessToken = (String) tokenResp.get("access_token");

            // 2. Query the realm's identity-providers list
            String idpUri = internalBase + "/admin/realms/" + realm + "/identity-providers/instances";
            HttpHeaders authHeaders = new HttpHeaders();
            authHeaders.setBearerAuth(accessToken);
            ResponseEntity<List> idpResp = rest.exchange(
                URI.create(idpUri), HttpMethod.GET, new HttpEntity<>(authHeaders), List.class);
            List<Map<String, Object>> idps = idpResp.getBody();
            if (idps == null) return Boolean.FALSE;
            // Azure Entra is brokered as a generic "oidc" provider. Treat any
            // enabled OIDC broker as the configured Azure IdP.
            for (Map<String, Object> idp : idps) {
                boolean isOidc  = "oidc".equalsIgnoreCase(String.valueOf(idp.get("providerId")));
                boolean enabled = !Boolean.FALSE.equals(idp.get("enabled"));
                if (isOidc && enabled) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Exception e) {
            log.debug("Could not determine IdP status for Keycloak realm '{}': {}", realm, e.getMessage());
            return null;
        }
    }
}
