package com.rohit.nyvra.user;

import java.util.UUID;

/**
 * Builds {@link UserProfile} instances for tests. One method per aggregate today; add a sibling
 * mother class per aggregate as Phase 2 introduces more entities, rather than growing this one.
 */
public final class UserProfileMother {

    /** Not instantiable. */
    private UserProfileMother() {
    }

    /**
     * Builds a profile with a random subject.
     *
     * @return an unsaved profile
     */
    public static UserProfile aUserProfile() {
        return aUserProfile("test-subject-" + UUID.randomUUID());
    }

    /**
     * Builds a profile for the given subject, with a derived email and blind index.
     *
     * @param keycloakSubject token {@code sub}
     * @return an unsaved profile
     */
    public static UserProfile aUserProfile(String keycloakSubject) {
        return new UserProfile(keycloakSubject, keycloakSubject + "@nyvra.local", "hash-" + keycloakSubject, "Test User");
    }
}
