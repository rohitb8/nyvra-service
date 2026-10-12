package com.rohit.nyvra.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.user.RiskBand;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checks that {@link DriverMetricsAssembler} reads the user's risk band and emergency-fund target from
 * {@code user_preferences}, and falls back to the defaults when the user has no row.
 */
class DriverMetricsPreferencesIntegrationTest extends AbstractIntegrationTest {

    /** The assembler under test. */
    @Autowired
    private DriverMetricsAssembler assembler;

    /** Creates users directly so tests know their ids. */
    @Autowired
    private UserProfileRepository users;

    /** Raw SQL, used to insert preference rows no API writes yet. */
    @Autowired
    private JdbcTemplate jdbc;

    /** A user with a stored risk band and emergency target has both carried into the facts. */
    @Test
    void storedPreferencesReachTheFacts() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        jdbc.update("INSERT INTO user_preferences (user_id, risk_band, emergency_fund_months_target) "
            + "VALUES (?, 'AGGRESSIVE', 4.5)", user.getId());

        DriverFacts facts = assembler.facts(user.getId(), LocalDate.now(), Instant.now());

        assertThat(facts.riskBand()).isEqualTo(RiskBand.AGGRESSIVE);
        assertThat(facts.userEmergencyTarget()).isEqualByComparingTo("4.5");
    }

    /** A user with no preferences row is balanced and has no emergency override. */
    @Test
    void missingPreferencesFallBackToDefaults() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());

        DriverFacts facts = assembler.facts(user.getId(), LocalDate.now(), Instant.now());

        assertThat(facts.riskBand()).isEqualTo(RiskBand.BALANCED);
        assertThat(facts.userEmergencyTarget()).isNull();
    }
}
