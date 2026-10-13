package com.rohit.nyvra.user;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands other modules a user's scoring-related preferences without exposing the entity. A user with no
 * preferences row gets the column defaults ({@link RiskBand#BALANCED}, no emergency-fund override), and a user
 * with no date of birth gets {@code null} for it, so callers never have to handle a missing row.
 */
@Component
public class UserPreferencesReader {

    /** Preference rows. */
    private final UserPreferencesRepository repository;
    /** Profile rows, for the date of birth. */
    private final UserProfileRepository profiles;

    /**
     * Creates the reader.
     *
     * @param repository the preferences repository
     * @param profiles   the profile repository
     */
    public UserPreferencesReader(UserPreferencesRepository repository, UserProfileRepository profiles) {
        this.repository = repository;
        this.profiles = profiles;
    }

    /**
     * A user's preferences that shape the health score.
     *
     * @param riskBand                  the risk appetite
     * @param emergencyFundMonthsTarget the user's own emergency-fund target in months; {@code null} when unset
     * @param dateOfBirth               the user's date of birth; {@code null} when not given
     */
    public record ScorePreferences(RiskBand riskBand, BigDecimal emergencyFundMonthsTarget, LocalDate dateOfBirth) {
    }

    /**
     * Reads a user's score-related preferences.
     *
     * @param userId the user
     * @return the stored preferences, or the defaults when the user has none, plus the date of birth
     */
    @Transactional(readOnly = true)
    public ScorePreferences scorePreferences(UUID userId) {
        LocalDate dateOfBirth = profiles.findById(userId).map(UserProfile::getDateOfBirth).orElse(null);
        return repository.findById(userId)
            .map(p -> new ScorePreferences(p.getRiskBand(), p.getEmergencyFundMonthsTarget(), dateOfBirth))
            .orElseGet(() -> new ScorePreferences(RiskBand.BALANCED, null, dateOfBirth));
    }
}
