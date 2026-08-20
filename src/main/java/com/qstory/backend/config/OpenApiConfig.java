package com.qstory.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI qstoryOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Q-story Speech API")
                        .description("Spring Boot backend for Q-story: question/narration pipelines, beta events, voice research, and story content.")
                        .version("0.1.0"));
    }
}
