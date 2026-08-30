package com.rohit.nyvra.user;

import java.util.UUID;

/**
 * Builds {@link UserProfile} instances for tests. One method per aggregate today; add a sibling
 * mother class per aggregate as Phase 2 introduces more entities, rather than growing this one.
 */
public final class UserProfileMother {

    private UserProfileMother() {
    }

    public static UserProfile aUserProfile() {
        return aUserProfile("test-subject-" + UUID.randomUUID());
    }

    public static UserProfile aUserProfile(String keycloakSubject) {
        return new UserProfile(keycloakSubject, keycloakSubject + "@nyvra.local", "Test User");
    }
}
