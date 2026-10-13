package com.rohit.nyvra.user;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rohit.nyvra.common.exception.UnprocessableEntityException;

/**
 * Changes the caller's own profile. Today that is only the date of birth, which the health score uses for its
 * age-based equity target.
 */
@Service
public class UserProfileService {

    /** Oldest age accepted, which rules out typos such as a year of 190. */
    static final int MAX_AGE_YEARS = 120;
    /** The user's display time zone, which decides what "today" is. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Kolkata");

    /** Resolves the caller. */
    private final CurrentUserService currentUserService;
    /** Source of today's date in the user's zone. */
    private final Clock clock = Clock.system(USER_ZONE);

    /**
     * Creates the service.
     *
     * @param currentUserService resolves the authenticated user
     */
    public UserProfileService(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    /**
     * Sets or clears the caller's date of birth.
     *
     * @param dateOfBirth the new date of birth, or {@code null} to clear it
     * @return the updated profile
     * @throws UnprocessableEntityException {@code INVALID_DATE_OF_BIRTH} when the date is in the future or
     *                                      more than {@value #MAX_AGE_YEARS} years ago
     */
    @Transactional
    public UserProfile updateDateOfBirth(LocalDate dateOfBirth) {
        if (dateOfBirth != null) {
            LocalDate today = LocalDate.now(clock);
            if (dateOfBirth.isAfter(today) || dateOfBirth.isBefore(today.minusYears(MAX_AGE_YEARS))) {
                throw new UnprocessableEntityException("INVALID_DATE_OF_BIRTH",
                    "Date of birth must be in the past and within the last " + MAX_AGE_YEARS + " years");
            }
        }
        UserProfile profile = currentUserService.currentUser();
        profile.setDateOfBirth(dateOfBirth);
        return profile;
    }
}
