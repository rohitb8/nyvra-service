package com.rohit.nyvra.user.dto;

import java.time.LocalDate;

/**
 * Body of {@code PUT /api/v1/users/me/date-of-birth}.
 *
 * @param dateOfBirth the new date of birth; {@code null} clears it, so scoring falls back to the assumed age
 */
public record UpdateDateOfBirthRequest(LocalDate dateOfBirth) {
}
