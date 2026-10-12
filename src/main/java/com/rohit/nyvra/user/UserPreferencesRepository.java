package com.rohit.nyvra.user;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads {@link UserPreferences} by user id. Prefer {@link UserPreferencesReader} from other modules, which
 * applies the defaults for a user who has no row.
 */
public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {
}
