package com.rohit.nyvra.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc / OpenAPI metadata. The generated spec at {@code /v3/api-docs} is the source of truth
 * for the frontend's generated TypeScript client.
 */
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "nyvra API",
        version = "v1",
        description = "Personal finance accountant — REST API. All endpoints are under /api/v1."),
    servers = @Server(url = "/", description = "Current host"))
@SecurityScheme(
    name = "keycloak",
    type = SecuritySchemeType.OAUTH2,
    flows = @OAuthFlows(
        authorizationCode = @OAuthFlow(
            authorizationUrl = "${nyvra.oidc.auth-url:http://localhost:8081/realms/nyvra/protocol/openid-connect/auth}",
            tokenUrl = "${nyvra.oidc.token-url:http://localhost:8081/realms/nyvra/protocol/openid-connect/token}")))
public class OpenApiConfig {
}
