package com.rohit.nyvra.analytics;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.analytics.DriverMetrics.DebtMetrics;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of the health score endpoints and the recording pipeline against a real database:
 * the "no score yet" placeholder, recording and reading back, skip-recompute, the trend's bucketing and
 * validation, and per-user isolation. Each test creates its own user.
 */
@AutoConfigureMockMvc
class HealthScoreIntegrationTest extends AbstractIntegrationTest {

    /** Drives the controller through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Creates users directly so tests know their ids. */
    @Autowired
    private UserProfileRepository users;

    /** The service whose {@code record} entry point the tests call. */
    @Autowired
    private HealthScoreService service;

    /** Raw SQL, used to count and back-date rows. */
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Authenticates a request as the given user.
     *
     * @param user the user, whose Keycloak subject becomes the token {@code sub}
     * @return a request post-processor attaching that JWT
     */
    private static RequestPostProcessor as(UserProfile user) {
        return jwt().jwt(jwt -> jwt.subject(user.getKeycloakSubject()));
    }

    /** @return a freshly stored user */
    private UserProfile newUser() {
        return users.save(UserProfileMother.aUserProfile());
    }

    /**
     * Builds metrics that evaluate savings, spending control and debt, so a numeric score results.
     *
     * @param savingsRate the savings rate to use
     * @return the drivers
     */
    private static DriverMetrics metrics(String savingsRate) {
        return new DriverMetrics(new BigDecimal(savingsRate), new BigDecimal("0.45"), new BigDecimal("0.25"), false,
            null, null, new DebtMetrics(true, null, null, false, false), null, null, false, false, 0, 200, Set.of());
    }

    /** A user with no stored score gets 200 with the INSUFFICIENT_DATA placeholder and a checklist. */
    @Test
    void noScoreYetReturnsInsufficientData() throws Exception {
        UserProfile user = newUser();

        mockMvc.perform(get("/api/v1/health-score").with(as(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.band").value("INSUFFICIENT_DATA"))
            .andExpect(jsonPath("$.overall").doesNotExist())
            .andExpect(jsonPath("$.asOf").doesNotExist())
            .andExpect(jsonPath("$.confidenceLevel").value("LOW"))
            .andExpect(jsonPath("$.missingData[0]").value("INCOME_ENTRY"))
            .andExpect(jsonPath("$.subScores.savings.evaluated").value(false))
            .andExpect(jsonPath("$.subScores.savings.value").doesNotExist())
            .andExpect(jsonPath("$.subScores.savings.weight").value(0.25))
            .andExpect(jsonPath("$.rulesetVersion").value("2026.1"));
    }

    /** A recorded score is served back with its sub-scores, drivers, versions and confidence level. */
    @Test
    void recordedScoreIsReturnedByTheEndpoint() throws Exception {
        UserProfile user = newUser();
        service.record(user.getId(), metrics("0.30"), Instant.now());

        mockMvc.perform(get("/api/v1/health-score").with(as(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.band").value("EXCELLENT"))
            .andExpect(jsonPath("$.overall").value(100.0))
            .andExpect(jsonPath("$.asOf").exists())
            .andExpect(jsonPath("$.subScores.savings.value").value(100.0))
            .andExpect(jsonPath("$.subScores.savings.status").value("OK"))
            .andExpect(jsonPath("$.subScores.savings.evaluated").value(true))
            .andExpect(jsonPath("$.subScores.emergencyFund.evaluated").value(false))
            .andExpect(jsonPath("$.missingData").doesNotExist())
            .andExpect(jsonPath("$.inputsHash").doesNotExist())
            .andExpect(jsonPath("$.scoreSpecVersion").value("2026.1"));
    }

    /** The latest row wins, and an unchanged input writes nothing (skip-recompute). */
    @Test
    void identicalInputsAreNotStoredTwice() {
        UserProfile user = newUser();
        Instant t0 = Instant.now().minusSeconds(120);

        service.record(user.getId(), metrics("0.30"), t0);
        service.record(user.getId(), metrics("0.3000"), t0.plusSeconds(60));
        assertThat(count(user)).isEqualTo(1);

        service.record(user.getId(), metrics("0.20"), t0.plusSeconds(90));
        assertThat(count(user)).isEqualTo(2);
    }

    /** An insufficient result is returned to the caller but never stored. */
    @Test
    void insufficientResultIsNotStored() {
        UserProfile user = newUser();

        HealthScoreResult r = service.record(user.getId(),
            DriverMetrics.nothing(Set.of(MissingData.INCOME_ENTRY)), Instant.now());

        assertThat(r.band()).isEqualTo(ScoreBand.INSUFFICIENT_DATA);
        assertThat(count(user)).isZero();
    }

    /** A capped score stores and returns its cap and appends the reason to the drivers. */
    @Test
    void capIsPersistedAndReturned() throws Exception {
        UserProfile user = newUser();
        service.record(user.getId(), metrics("-0.10"), Instant.now());

        mockMvc.perform(get("/api/v1/health-score").with(as(user)))
            .andExpect(jsonPath("$.overall").value(40.0))
            .andExpect(jsonPath("$.cap.value").value(40))
            .andExpect(jsonPath("$.cap.reason").value("CAP_NEGATIVE_SAVINGS"))
            .andExpect(jsonPath("$.topDrivers[?(@ == 'CAP_NEGATIVE_SAVINGS')]", hasSize(1)));
    }

    /** The trend keeps only the last score per bucket, oldest first, and honours the date range. */
    @Test
    void historyReturnsLastScorePerBucket() throws Exception {
        UserProfile user = newUser();
        // Fixed IST clock times a few days back, so the test is not sensitive to the time of day it runs.
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate day = LocalDate.now(ist).minusDays(5);
        // Two scores on the same IST day, one 20 days earlier.
        service.record(user.getId(), metrics("0.15"), day.minusDays(20).atTime(12, 0).atZone(ist).toInstant());
        service.record(user.getId(), metrics("0.20"), day.atTime(6, 0).atZone(ist).toInstant());
        service.record(user.getId(), metrics("0.30"), day.atTime(18, 0).atZone(ist).toInstant());

        mockMvc.perform(get("/api/v1/health-score/history").param("granularity", "DAY").with(as(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.granularity").value("DAY"))
            .andExpect(jsonPath("$.points", hasSize(2)))
            .andExpect(jsonPath("$.points[1].overall").value(100.0));

        mockMvc.perform(get("/api/v1/health-score/history").param("granularity", "MONTH").with(as(user)))
            .andExpect(jsonPath("$.points").isArray());

        mockMvc.perform(get("/api/v1/health-score/history").param("from", "2000-01-01").param("to", "2000-01-31")
                .with(as(user)))
            .andExpect(jsonPath("$.points", hasSize(0)));
    }

    /** A reversed range and an unknown granularity are rejected with 400. */
    @Test
    void historyRejectsBadParameters() throws Exception {
        UserProfile user = newUser();

        mockMvc.perform(get("/api/v1/health-score/history").param("from", "2026-02-01").param("to", "2026-01-01")
                .with(as(user)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/health-score/history").param("granularity", "YEAR").with(as(user)))
            .andExpect(status().isBadRequest());
    }

    /** One user never sees another user's score or trend. */
    @Test
    void scoresAreIsolatedPerUser() throws Exception {
        UserProfile owner = newUser();
        UserProfile other = newUser();
        service.record(owner.getId(), metrics("0.30"), Instant.now());

        mockMvc.perform(get("/api/v1/health-score").with(as(other)))
            .andExpect(jsonPath("$.band").value("INSUFFICIENT_DATA"));
        mockMvc.perform(get("/api/v1/health-score/history").with(as(other)))
            .andExpect(jsonPath("$.points", hasSize(0)));
    }

    /** Anonymous callers get 401. */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/health-score")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/health-score/history")).andExpect(status().isUnauthorized());
    }

    /**
     * Counts a user's stored scores.
     *
     * @param user the owner
     * @return the number of rows
     */
    private int count(UserProfile user) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM health_score WHERE user_id = ?", Integer.class,
            user.getId());
        return n == null ? 0 : n;
    }

}
