package io.orphos.eip.docingest.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Document Ingestion Service")
                .description("Structure-aware document parsing, embedding, and Qdrant vector storage")
                .version("1.0.0"));
    }
}
