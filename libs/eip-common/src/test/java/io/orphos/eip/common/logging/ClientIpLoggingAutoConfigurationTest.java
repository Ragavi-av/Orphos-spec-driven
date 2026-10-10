package io.orphos.eip.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the contract services rely on: depending on the {@code eip-common} jar is
 * enough to get the client-IP filter registered — no {@code @Component} scan, no
 * per-service configuration class. This is the property that let 30 duplicated
 * copies of the filter be deleted, so it is worth a test of its own.
 */
class ClientIpLoggingAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ClientIpLoggingAutoConfiguration.class));

    @Test
    @DisplayName("a servlet app gets the filter registered at highest precedence, for all paths")
    void registeredByDefaultInServletApp() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            FilterRegistrationBean<?> reg = context.getBean(FilterRegistrationBean.class);
            assertThat(reg.getFilter()).isInstanceOf(ClientIpLoggingFilter.class);
            assertThat(reg.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(reg.getUrlPatterns()).containsExactly("/*");
        });
    }

    @Test
    @DisplayName("a service can opt out with eip.logging.client-ip.enabled=false")
    void canBeDisabled() {
        webRunner.withPropertyValues("eip.logging.client-ip.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    @DisplayName("a non-web app does not register a servlet filter")
    void notRegisteredOutsideServletApp() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ClientIpLoggingAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }
}
