package com.rohit.nyvra.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link UserProfile}. */
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    /**
     * Finds a profile by the token {@code sub}.
     *
     * @param keycloakSubject the Keycloak subject
     * @return the profile, or empty
     */
    Optional<UserProfile> findByKeycloakSubject(String keycloakSubject);

    /**
     * Finds a profile by the email blind index.
     *
     * @param emailHash keyed hash of the normalised email
     * @return the profile, or empty
     */
    Optional<UserProfile> findByEmailHash(String emailHash);
}
