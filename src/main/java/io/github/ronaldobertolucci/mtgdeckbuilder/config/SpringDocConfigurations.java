package io.github.ronaldobertolucci.mtgdeckbuilder.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringDocConfigurations {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("bearer-key",
                                new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer").bearerFormat("JWT")))
                        .info(new Info()
                                .title("MTG Deck Builder API")
                        .description("API REST para criar decks de Magic The Gathering")
                        .contact(new Contact()
                                .name("Ronaldo Bertolucci Jr")
                                .email("ronaldobertoluccijr@gmail.com"))
                        .license(new License()
                                .name("GNU GENERAL PUBLIC LICENSE v3")
                                .url("https://github.com/ronaldobertolucci/mtg-deck-builder?tab=GPL-3.0-1-ov-file")));
    }

}