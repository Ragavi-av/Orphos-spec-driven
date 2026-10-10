package io.orphos.eip.mailinsight.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mailInsightOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Mail Insight Service")
                .version("v1")
                .description("Scans Microsoft 365 mail/calendar and infers Tasks for Action and Notifications."));
    }
}
