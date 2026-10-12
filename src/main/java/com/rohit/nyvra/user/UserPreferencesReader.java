package com.rohit.nyvra.user;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands other modules a user's scoring-related preferences without exposing the entity. A user with no
 * preferences row gets the column defaults ({@link RiskBand#BALANCED}, no emergency-fund override), so
 * callers never have to handle absence.
 */
@Component
public class UserPreferencesReader {

    /** Preference rows. */
    private final UserPreferencesRepository repository;

    /**
     * Creates the reader.
     *
     * @param repository the preferences repository
     */
    public UserPreferencesReader(UserPreferencesRepository repository) {
        this.repository = repository;
    }

    /**
     * A user's preferences that shape the health score.
     *
     * @param riskBand                  the risk appetite
     * @param emergencyFundMonthsTarget the user's own emergency-fund target in months; {@code null} when unset
     */
    public record ScorePreferences(RiskBand riskBand, BigDecimal emergencyFundMonthsTarget) {
    }

    /**
     * Reads a user's score-related preferences.
     *
     * @param userId the user
     * @return the stored preferences, or the defaults when the user has none
     */
    @Transactional(readOnly = true)
    public ScorePreferences scorePreferences(UUID userId) {
        return repository.findById(userId)
            .map(p -> new ScorePreferences(p.getRiskBand(), p.getEmergencyFundMonthsTarget()))
            .orElseGet(() -> new ScorePreferences(RiskBand.BALANCED, null));
    }
}
