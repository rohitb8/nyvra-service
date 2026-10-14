package com.rohit.nyvra.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.rohit.nyvra.AbstractIntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves {@link UserProfileRepository} works end to end against a real, Flyway-migrated Postgres
 * (via {@link AbstractIntegrationTest}) — not just that the code compiles.
 */
class UserProfileRepositoryIntegrationTest extends AbstractIntegrationTest {

    /** Repository under test. */
    @Autowired
    private UserProfileRepository repository;

    /** A saved profile is found by its subject with its email decrypted. */
    @Test
    void savesAndFindsByKeycloakSubject() {
        UserProfile saved = repository.save(UserProfileMother.aUserProfile("subject-under-test"));

        Optional<UserProfile> found = repository.findByKeycloakSubject("subject-under-test");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getEmail()).isEqualTo("subject-under-test@nyvra.local");
    }

    /** An unknown subject yields no profile. */
    @Test
    void findByKeycloakSubjectIsEmptyWhenUnknown() {
        assertThat(repository.findByKeycloakSubject("no-such-subject")).isEmpty();
    }
}
