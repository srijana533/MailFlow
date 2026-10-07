package com.mailflow.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger documentation configuration.
 *
 * <p>Access the UI at: {@code /swagger-ui.html}
 * <p>Access the spec at: {@code /v3/api-docs}
 */
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title       = "MailFlow API",
        version     = "1.0.0",
        description = "Scalable Email Campaign Automation Platform — REST API Reference",
        contact     = @Contact(name = "MailFlow Team", email = "support@mailflow.local"),
        license     = @License(name = "MIT")
    ),
    servers = {
        @Server(url = "http://localhost:8080", description = "Local development"),
        @Server(url = "https://api.mailflow.example.com", description = "Production")
    }
)
@SecurityScheme(
    name        = "bearerAuth",
    type        = SecuritySchemeType.HTTP,
    scheme      = "bearer",
    bearerFormat = "JWT",
    description = "Provide a JWT token obtained from POST /api/auth/login"
)
public class OpenApiConfig {
    // Annotation-driven; no bean methods needed.
}
