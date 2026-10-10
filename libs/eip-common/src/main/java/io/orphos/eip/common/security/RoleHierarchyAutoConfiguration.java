package io.orphos.eip.common.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.web.access.expression.DefaultHttpSecurityExpressionHandler;

/**
 * Wires a shared role hierarchy so server-side {@code hasRole(...)} checks
 * mirror the SPA's {@code useRole} expansion.
 *
 * <p>The SPA treats {@code platform_admin} as implying {@code admin} (and a
 * few other relations) — a user with only {@code ROLE_platform_admin} sees
 * admin pages in the UI. Without a server-side hierarchy, a backend rule of
 * {@code hasRole("admin")} would still reject that user with 403, surfacing
 * as the "Admin access denied by the server" toast in App.jsx.</p>
 *
 * <p>This auto-config registers a {@link RoleHierarchy} bean that
 * {@link DefaultMethodSecurityExpressionHandler} and Spring Security's
 * URL-level {@code AuthorityAuthorizationManager} both pick up automatically,
 * so {@code @PreAuthorize("hasRole('admin')")} accepts {@code platform_admin}
 * holders.</p>
 *
 * <p>The edges come from {@link EipRoles}, the single role catalog: stronger
 * roles imply weaker ones. Services that need a different hierarchy can
 * declare their own {@link RoleHierarchy} bean to override.</p>
 */
@AutoConfiguration
@ConditionalOnClass(RoleHierarchy.class)
@EnableConfigurationProperties(SecurityProperties.class)
public class RoleHierarchyAutoConfiguration {

    private static final Logger log = LogManager.getLogger(RoleHierarchyAutoConfiguration.class);

    /**
     * The platform hierarchy from {@link EipRoles} (the same edges {@link EipRoles#expand}
     * walks). Services override the {@code RoleHierarchy} bean to customise.
     */
    @Bean
    @ConditionalOnMissingBean
    public RoleHierarchy roleHierarchy() {
        String chain = EipRoles.springHierarchy();
        log.info("Role hierarchy registered:\n{}", chain);
        return RoleHierarchyImpl.fromHierarchy(chain);
    }

    /**
     * Exposes the hierarchy to {@code @PreAuthorize}/{@code @PostAuthorize}.
     * Without this, method-level role checks ignore the hierarchy even when
     * the bean is present.
     */
    @Bean
    @ConditionalOnMissingBean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }

    /**
     * Exposes the hierarchy to URL-level {@code .hasRole(...)} rules in
     * {@code HttpSecurity}. Spring Security picks up this bean by type via
     * its expression-handler lookup.
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultHttpSecurityExpressionHandler httpSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultHttpSecurityExpressionHandler handler = new DefaultHttpSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }
}
