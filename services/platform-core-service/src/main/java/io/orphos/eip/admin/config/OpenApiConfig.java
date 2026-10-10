package io.orphos.eip.admin.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the OpenAPI metadata for platform-core-service and registers a
 * bearer-token security scheme so Swagger UI's "Authorize" dialog accepts a
 * Keycloak JWT.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI platformCoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Orphos EIP — Platform Core Service API")
                        .description("Tenant lifecycle, discovery, authentication/authorization, secrets, onboarding, ops.")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
