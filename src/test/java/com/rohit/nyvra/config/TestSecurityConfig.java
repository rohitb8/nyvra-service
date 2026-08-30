package com.rohit.nyvra.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Stands in for the real, autoconfigured {@link JwtDecoder} in tests. Spring Boot's OAuth2
 * resource-server autoconfiguration builds its {@code JwtDecoder} from
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, resolving that issuer's discovery
 * document at bean-creation time — i.e. at application context startup. {@code application-test.yml}
 * deliberately points that property at an unreachable placeholder, so without this bean, any
 * {@code @SpringBootTest} would fail to start.
 *
 * <p>This bean is never actually invoked: tests authenticate MockMvc requests with
 * {@code SecurityMockMvcRequestPostProcessors.jwt()}, which injects an already-built {@link Jwt}
 * directly into the security context, bypassing decoding entirely. It exists purely so the real
 * autoconfiguration — which backs off via {@code @ConditionalOnMissingBean(JwtDecoder.class)} — never
 * runs and never makes a network call.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            throw new JwtException("TestSecurityConfig's JwtDecoder is a stub — use "
                + "SecurityMockMvcRequestPostProcessors.jwt() instead of a real bearer token in tests.");
        };
    }
}
