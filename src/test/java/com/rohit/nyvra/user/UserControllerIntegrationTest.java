package com.rohit.nyvra.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises {@code /api/v1/users/me} and {@code PUT /api/v1/users/me/date-of-birth} through the real filter
 * chain, authenticating with {@code SecurityMockMvcRequestPostProcessors.jwt()} rather than a live token — see
 * {@link com.rohit.nyvra.config.TestSecurityConfig} for why. Proves the just-in-time {@link UserProfile}
 * provisioning {@link CurrentUserService} does on first authenticated call, and the date-of-birth rules.
 */
@AutoConfigureMockMvc
class UserControllerIntegrationTest extends AbstractIntegrationTest {

    /** Sends requests through the real filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Used to confirm what was persisted. */
    @Autowired
    private UserProfileRepository repository;

    /** Used to read a stored date of birth the way the health score does. */
    @Autowired
    private UserPreferencesReader preferencesReader;

    /**
     * A caller with a fresh subject, so tests never share a profile.
     *
     * @return the JWT post-processor
     */
    private static JwtRequestPostProcessor caller() {
        return jwt().jwt(jwt -> jwt.subject("dob-test-" + UUID.randomUUID()));
    }

    /** The first call provisions the caller's profile and returns it, with no date of birth yet. */
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
            .andExpect(jsonPath("$.baseCurrency", equalTo("INR")))
            .andExpect(jsonPath("$.dateOfBirth", nullValue()));

        assertThat(repository.findByKeycloakSubject("controller-test-subject")).isPresent();
    }

    /** A date of birth can be set, is returned by later reads and reaches the score preferences, then cleared. */
    @Test
    void dateOfBirthCanBeSetReadAndCleared() throws Exception {
        JwtRequestPostProcessor caller = jwt().jwt(jwt -> jwt.subject("dob-roundtrip-subject"));

        mockMvc.perform(put("/api/v1/users/me/date-of-birth").with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"dateOfBirth\":\"1990-05-17\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dateOfBirth", equalTo("1990-05-17")));
        mockMvc.perform(get("/api/v1/users/me").with(caller))
            .andExpect(jsonPath("$.dateOfBirth", equalTo("1990-05-17")));

        UUID userId = repository.findByKeycloakSubject("dob-roundtrip-subject").orElseThrow().getId();
        assertThat(preferencesReader.scorePreferences(userId).dateOfBirth()).isEqualTo(LocalDate.of(1990, 5, 17));

        mockMvc.perform(put("/api/v1/users/me/date-of-birth").with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"dateOfBirth\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dateOfBirth", nullValue()));
        assertThat(preferencesReader.scorePreferences(userId).dateOfBirth()).isNull();
    }

    /** A date of birth in the future is rejected with 422 and nothing is stored. */
    @Test
    void futureDateOfBirthIsRejected() throws Exception {
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2).toString();

        mockMvc.perform(put("/api/v1/users/me/date-of-birth").with(caller())
                .contentType(MediaType.APPLICATION_JSON).content("{\"dateOfBirth\":\"" + tomorrow + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("INVALID_DATE_OF_BIRTH")));
    }

    /** A date of birth more than 120 years ago is rejected as a probable typo. */
    @Test
    void implausiblyOldDateOfBirthIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/date-of-birth").with(caller())
                .contentType(MediaType.APPLICATION_JSON).content("{\"dateOfBirth\":\"1850-01-01\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("INVALID_DATE_OF_BIRTH")));
    }

    /** A malformed date is a 400, not a server error. */
    @Test
    void malformedDateIsABadRequest() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/date-of-birth").with(caller())
                .contentType(MediaType.APPLICATION_JSON).content("{\"dateOfBirth\":\"not-a-date\"}"))
            .andExpect(status().isBadRequest());
    }
}
