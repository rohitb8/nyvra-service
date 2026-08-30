package com.rohit.nyvra.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises {@code GET /api/v1/users/me} through the real filter chain, authenticating with
 * {@code SecurityMockMvcRequestPostProcessors.jwt()} rather than a live token — see
 * {@link com.rohit.nyvra.config.TestSecurityConfig} for why. Proves the just-in-time
 * {@link UserProfile} provisioning {@link CurrentUserService} does on first authenticated call.
 */
@AutoConfigureMockMvc
class UserControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileRepository repository;

    @Test
    void meProvisionsAndReturnsTheCallersProfile() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                .with(jwt().jwt(jwt -> jwt
                    .subject("controller-test-subject")
                    .claim("email", "controller-test@nyvra.local")
                    .claim("name", "Controller Test User"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email", equalTo("controller-test@nyvra.local")))
            .andExpect(jsonPath("$.displayName", equalTo("Controller Test User")))
            .andExpect(jsonPath("$.baseCurrency", equalTo("INR")));

        assertThat(repository.findByKeycloakSubject("controller-test-subject")).isPresent();
    }
}
