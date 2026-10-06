package com.rohit.nyvra.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class UserEmailBackfillIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserEmailBackfill backfill;

    @Autowired
    private UserProfileRepository repository;

    @Test
    void encryptsLegacyPlaintextEmailAndIsIdempotent() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile (id, keycloak_subject, email) VALUES (?, ?, ?)",
            id, "legacy-" + id, "Legacy.User@Example.com");

        assertThat(backfill.backfill()).isGreaterThanOrEqualTo(1);
        assertThat(backfill.backfill()).isZero();

        UserProfile loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getEmail()).isEqualTo("Legacy.User@Example.com");
        assertThat(repository.findByEmailHash(loaded.getEmailHash())).isPresent();
        byte[] stored = jdbc.queryForObject("SELECT email_encrypted FROM user_profile WHERE id = ?", byte[].class, id);
        assertThat(new String(stored)).doesNotContain("Example.com");
    }
}
