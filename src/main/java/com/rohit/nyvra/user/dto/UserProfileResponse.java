package com.rohit.nyvra.user.dto;

import java.time.Instant;
import java.util.UUID;

import com.rohit.nyvra.user.UserProfile;

public record UserProfileResponse(
    UUID id,
    String email,
    String displayName,
    String baseCurrency,
    Instant createdAt) {

    public static UserProfileResponse from(UserProfile profile) {
        return new UserProfileResponse(
            profile.getId(),
            profile.getEmail(),
            profile.getDisplayName(),
            profile.getBaseCurrency(),
            profile.getCreatedAt());
    }
}
