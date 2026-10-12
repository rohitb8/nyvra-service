package com.rohit.nyvra.user;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of the {@code user_preferences} row (V1): only the columns other modules need to
 * personalise their calculations. The row is keyed by the user id rather than carrying its own, so this does
 * not extend {@code AbstractEntity}. Nothing writes preferences yet.
 */
@Entity
@Immutable
@Table(name = "user_preferences")
public class UserPreferences {

    /** The owning user; also the primary key. */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** The user's risk appetite. */
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_band", nullable = false)
    private RiskBand riskBand;

    /** The user's own emergency-fund target in months of essential spending; {@code null} when unset. */
    @Column(name = "emergency_fund_months_target")
    private BigDecimal emergencyFundMonthsTarget;

    /** Required by JPA. */
    protected UserPreferences() {
    }

    /**
     * Returns the owning user's id.
     *
     * @return the user id
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * Returns the user's risk appetite.
     *
     * @return the risk band, never {@code null}
     */
    public RiskBand getRiskBand() {
        return riskBand;
    }

    /**
     * Returns the user's own emergency-fund target.
     *
     * @return months of essential spending, or {@code null} when the user has not set one
     */
    public BigDecimal getEmergencyFundMonthsTarget() {
        return emergencyFundMonthsTarget;
    }
}
