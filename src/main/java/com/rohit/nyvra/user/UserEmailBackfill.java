package com.rohit.nyvra.user;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.crypto.BlindIndexHasher;
import com.rohit.nyvra.common.crypto.FieldEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expand/contract step 2 for {@code user_profile.email}: encrypts legacy plaintext emails into
 * {@code email_encrypted} + {@code email_hash}. Idempotent (only touches rows not yet migrated), so it is
 * safe to run on every boot; once it logs zero remaining everywhere, a later migration drops the
 * plaintext column. Needs the app's keys, which is why it is not plain SQL in Flyway.
 */
@Component
class UserEmailBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserEmailBackfill.class);

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final BlindIndexHasher hasher;

    UserEmailBackfill(JdbcTemplate jdbc, FieldEncryptor encryptor, BlindIndexHasher hasher) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.hasher = hasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        int migrated = backfill();
        if (migrated > 0) {
            log.info("Encrypted {} legacy user_profile emails", migrated);
        }
    }

    @Transactional
    int backfill() {
        record Legacy(UUID id, String email) {
        }
        List<Legacy> rows = jdbc.query(
            "SELECT id, email FROM user_profile WHERE email IS NOT NULL AND email_encrypted IS NULL",
            (rs, i) -> new Legacy(rs.getObject("id", UUID.class), rs.getString("email")));
        for (Legacy row : rows) {
            jdbc.update(
                "UPDATE user_profile SET email_encrypted = ?, email_hash = ? WHERE id = ?",
                encryptor.encrypt(row.email()), EmailBlindIndex.of(hasher, row.email()), row.id());
        }
        return rows.size();
    }
}
