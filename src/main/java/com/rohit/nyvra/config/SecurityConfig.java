package com.rohit.nyvra.config;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.nyvra.common.idempotency.IdempotencyFilter;
import com.rohit.nyvra.common.idempotency.IdempotencyStore;
import com.rohit.nyvra.common.logging.CorrelationIdFilter;
import com.rohit.nyvra.common.logging.UserIdMdcFilter;
import com.rohit.nyvra.common.security.ApiErrorAccessDeniedHandler;
import com.rohit.nyvra.common.security.ApiErrorAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless OAuth2 resource-server security. Keycloak issues the tokens; nyvra only validates them.
 * See {@code docs/CLAUDE.md} "Auth model" and {@code docs/operations/ENVIRONMENTS.md} section 5.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Origins allowed by CORS ({@code nyvra.cors.allowed-origins}). */
    private final List<String> allowedOrigins;

    /** Mapper used to write error bodies from filters. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the security configuration.
     *
     * @param allowedOrigins CORS origins for the web client
     * @param objectMapper   mapper for filter-written error bodies
     */
    public SecurityConfig(
            @Value("${nyvra.cors.allowed-origins:http://localhost:4200}") List<String> allowedOrigins,
            ObjectMapper objectMapper) {
        this.allowedOrigins = allowedOrigins;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the stateless filter chain: bearer-token auth, then correlation, user-id and idempotency filters.
     *
     * @param http             the Spring Security builder
     * @param idempotencyStore Redis-backed store behind the {@code Idempotency-Key} support
     * @return the filter chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, IdempotencyStore idempotencyStore) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health/**",
                    "/actuator/info",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html")
                .permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                .authenticationEntryPoint(new ApiErrorAuthenticationEntryPoint(objectMapper))
                .accessDeniedHandler(new ApiErrorAccessDeniedHandler(objectMapper)))
            // CorrelationIdFilter wraps the whole request, including auth failures; UserIdMdcFilter
            // runs right after BearerTokenAuthenticationFilter, once the JWT principal is resolved.
            // Neither is a @Component — registered here explicitly so their position is unambiguous
            // rather than order-guessed. See their Javadoc for why.
            .addFilterBefore(new CorrelationIdFilter(), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new UserIdMdcFilter(), BearerTokenAuthenticationFilter.class)
            // Needs the resolved caller, so it follows the auth filters (see its Javadoc).
            .addFilterAfter(new IdempotencyFilter(idempotencyStore, objectMapper), UserIdMdcFilter.class);
        return http.build();
    }

    /**
     * Maps Keycloak {@code realm_access.roles} and {@code resource_access.*.roles} to {@code ROLE_*} authorities.
     *
     * @return the converter used for JWT authentication
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::extractAuthorities);
        return converter;
    }

    /** Pulls the realm roles out of the token as {@code ROLE_*} authorities. */
    @SuppressWarnings("unchecked")
    private static Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        var realmAccess = jwt.getClaimAsMap("realm_access");
        Stream<String> realmRoles = realmAccess == null
            ? Stream.empty()
            : ((List<String>) realmAccess.getOrDefault("roles", List.of())).stream();
        return realmRoles
            .map(role -> "ROLE_" + role.toUpperCase())
            .distinct()
            .map(SimpleGrantedAuthority::new)
            .map(GrantedAuthority.class::cast)
            .toList();
    }

    /**
     * CORS for {@code /api/**}; exposes {@code Idempotent-Replayed} and accepts {@code Idempotency-Key}.
     *
     * @return the CORS configuration source
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key"));
        config.setExposedHeaders(List.of("Location", "Idempotent-Replayed"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
