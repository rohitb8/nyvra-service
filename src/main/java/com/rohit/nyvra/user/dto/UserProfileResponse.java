package com.rohit.nyvra.user.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.user.UserProfile;

/**
 * The caller's profile as returned by {@code /api/v1/users/me}.
 *
 * @param id           the user id
 * @param email        the decrypted email, or {@code null}
 * @param displayName  the display name, or {@code null}
 * @param baseCurrency the ISO-4217 reporting currency
 * @param dateOfBirth  the date of birth, or {@code null} when the user has not given one
 * @param createdAt    when the profile was created
 */
public record UserProfileResponse(
    UUID id,
    String email,
    String displayName,
    String baseCurrency,
    LocalDate dateOfBirth,
    Instant createdAt) {

    /**
     * Maps a profile entity to its response.
     *
     * @param profile the profile
     * @return the response DTO
     */
    public static UserProfileResponse from(UserProfile profile) {
        return new UserProfileResponse(
            profile.getId(),
            profile.getEmail(),
            profile.getDisplayName(),
            profile.getBaseCurrency(),
            profile.getDateOfBirth(),
            profile.getCreatedAt());
    }
}
