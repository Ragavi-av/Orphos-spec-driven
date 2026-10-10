package io.orphos.eip.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Tunables for {@link DefaultSecurityFilterChainAutoConfiguration}.
 *
 * <p>Services with no custom authorization rules beyond "permit actuator
 * health/metrics, require a valid JWT for everything else" get a zero-code
 * {@code SecurityFilterChain} just by depending on {@code eip-core-security}.
 * Services with genuinely custom rules keep their own {@code SecurityConfig}
 * bean — this auto-configuration backs off via {@code @ConditionalOnMissingBean}.</p>
 */
@ConfigurationProperties(prefix = "eip.security.default-chain")
public class EipDefaultSecurityProperties {

    /** Set false to opt out entirely and rely on a service-local SecurityConfig instead. */
    private boolean enabled = true;

    /** Request patterns permitted without authentication, in addition to the built-in actuator set. */
    private List<String> permitAll = new ArrayList<>();

    /**
     * Enable {@code @PreAuthorize}/{@code @PostAuthorize} method security for this service.
     * On by default so an annotation is never silently ignored; a service that already
     * enables method security itself is left alone.
     */
    private boolean methodSecurity = true;

    /**
     * Require a real role for every non-public request: {@code ROLE_member} (any role
     * beyond guest, derived by eip-common's JWT converter) or an internal-service caller.
     * A guest-only account is awaiting an admin's role assignment and gets 403.
     */
    private boolean requireMember = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getPermitAll() { return permitAll; }
    public void setPermitAll(List<String> permitAll) {
        this.permitAll = permitAll != null ? permitAll : new ArrayList<>();
    }

    public boolean isMethodSecurity() { return methodSecurity; }
    public void setMethodSecurity(boolean methodSecurity) { this.methodSecurity = methodSecurity; }

    public boolean isRequireMember() { return requireMember; }
    public void setRequireMember(boolean requireMember) { this.requireMember = requireMember; }
}
