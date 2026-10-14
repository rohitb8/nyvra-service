package com.rohit.nyvra.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.partition.MonthlyPartitions;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;

/**
 * TODO.md Phase 2.2's done-when for Accounts: an account + 1000 transactions spanning two monthly
 * partitions, paged back correctly — plus the 🔒 columns really being ciphertext at rest.
 */
class AccountsRepositoryIntegrationTest extends AbstractIntegrationTest {

    /** User persistence. */
    @Autowired
    private UserProfileRepository users;

    /** Account persistence. */
    @Autowired
    private FinancialAccountRepository accounts;

    /** Ledger persistence. */
    @Autowired
    private AccountTransactionRepository transactions;

    /** Card detail persistence. */
    @Autowired
    private CardDetailRepository cards;

    /** Creates the monthly partitions the inserts need. */
    @Autowired
    private MonthlyPartitions partitions;

    /** Reads raw column values to inspect what is stored. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 1000 transactions across two monthly partitions page back newest first, and rows land in both partitions. */
    @Test
    void persistsAThousandTransactionsAcrossTwoMonthlyPartitionsAndPagesThem() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        FinancialAccount account = accounts.save(anAccount(user.getId()));
        LocalDate march = LocalDate.of(2025, 3, 1);
        LocalDate april = LocalDate.of(2025, 4, 1);
        partitions.ensureRange("transaction", march, april);

        List<AccountTransaction> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            LocalDate valueDate = (i % 2 == 0 ? march : april).plusDays(i % 28);
            rows.add(new AccountTransaction(account.getId(), user.getId(), valueDate, valueDate,
                Money.inr(i % 3 == 0 ? "1500.00" : "-249.50"), "UPI/merchant " + i, "Counterparty " + i,
                null, RecordSource.AA, "dedup-" + account.getId() + "-" + i));
        }
        transactions.saveAll(rows);

        Page<AccountTransaction> firstPage =
            transactions.findByUserIdOrderByValueDateDescIdDesc(user.getId(), PageRequest.of(0, 50));
        Page<AccountTransaction> lastPage =
            transactions.findByUserIdOrderByValueDateDescIdDesc(user.getId(), PageRequest.of(19, 50));

        assertThat(firstPage.getTotalElements()).isEqualTo(1000);
        assertThat(firstPage.getContent()).hasSize(50)
            .allSatisfy(t -> assertThat(t.getValueDate().getMonthValue()).isEqualTo(4));
        assertThat(firstPage.getContent().get(0).getValueDate()).isEqualTo(LocalDate.of(2025, 4, 28));
        assertThat(lastPage.getContent()).hasSize(50)
            .allSatisfy(t -> assertThat(t.getValueDate().getMonthValue()).isEqualTo(3));
        assertThat(jdbcTemplate.queryForList(
            "SELECT DISTINCT tableoid::regclass::text FROM transaction WHERE user_id = ? ORDER BY 1",
            String.class, user.getId()))
            .containsExactly("transaction_2025_03", "transaction_2025_04");
    }

    /** Narration and masked number are ciphertext in the database and plaintext through the entities. */
    @Test
    void encryptsSensitiveColumnsAtRestAndDecryptsOnRead() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        FinancialAccount account = accounts.save(anAccount(user.getId()));
        LocalDate today = LocalDate.now();
        AccountTransaction saved = transactions.save(new AccountTransaction(account.getId(), user.getId(), today,
            today, Money.inr("-120.00"), "UPI/SWIGGY/ref 991", "Swiggy", Money.inr("880.00"), RecordSource.AA,
            "dedup-enc-" + account.getId()));

        byte[] narrationAtRest = jdbcTemplate.queryForObject(
            "SELECT narration FROM transaction WHERE id = ?", byte[].class, saved.getId());
        byte[] maskedNumberAtRest = jdbcTemplate.queryForObject(
            "SELECT masked_number FROM financial_account WHERE id = ?", byte[].class, account.getId());

        assertThat(new String(narrationAtRest, StandardCharsets.ISO_8859_1)).doesNotContain("SWIGGY");
        assertThat(new String(maskedNumberAtRest, StandardCharsets.ISO_8859_1)).doesNotContain("1234");
        AccountTransaction reloaded = transactions.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getNarration()).isEqualTo("UPI/SWIGGY/ref 991");
        assertThat(reloaded.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(reloaded.getBalanceAfter()).isEqualTo(Money.inr("880.00"));
        assertThat(accounts.findById(account.getId()).orElseThrow().getMaskedNumber()).isEqualTo("XXXX1234");
    }

    /** A second row with the same dedup key and value date violates the unique constraint. */
    @Test
    void rejectsADuplicateDedupKeyOnTheSameValueDate() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        FinancialAccount account = accounts.save(anAccount(user.getId()));
        LocalDate today = LocalDate.now();
        String dedupKey = "dedup-dup-" + account.getId();
        transactions.save(aTransaction(account, today, dedupKey));

        assertThatThrownBy(() -> transactions.saveAndFlush(aTransaction(account, today, dedupKey)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Account lookups are user-scoped and exclude soft-deleted accounts. */
    @Test
    void scopesAccountLookupsToTheOwnerAndHidesSoftDeleted() {
        UserProfile owner = users.save(UserProfileMother.aUserProfile());
        UserProfile other = users.save(UserProfileMother.aUserProfile());
        FinancialAccount kept = accounts.save(anAccount(owner.getId()));
        FinancialAccount deleted = anAccount(owner.getId());
        deleted.softDelete(Instant.now());
        accounts.save(deleted);

        assertThat(accounts.findByIdAndUserIdAndDeletedAtIsNull(kept.getId(), other.getId())).isEmpty();
        assertThat(accounts.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(owner.getId()))
            .extracting(FinancialAccount::getId).containsExactly(kept.getId());
    }

    /** Card details keep last 4 and credit limit only; a non-digit last4 is rejected. */
    @Test
    void storesCardDisplayDetailsOnly() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        FinancialAccount account = accounts.save(new FinancialAccount(user.getId(), AccountType.CREDIT_CARD,
            "ICICI Bank", null, "Amazon Pay", Money.inr("-4200.00"), Instant.now(), RecordSource.MANUAL));
        cards.save(new CardDetail(account.getId(), "4242", CardNetwork.VISA, "Amazon Pay", "INR",
            Money.inr("200000.00")));

        assertThat(cards.findByFinancialAccountId(account.getId()))
            .singleElement()
            .satisfies(card -> {
                assertThat(card.getLast4()).isEqualTo("4242");
                assertThat(card.getCreditLimit()).isEqualTo(Money.inr("200000.00"));
            });
        assertThatThrownBy(() -> new CardDetail(account.getId(), "42a2", CardNetwork.VISA, null, "INR", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Builds a manual INR savings account with balance 1000.00.
     *
     * @param userId owner
     * @return an unsaved account
     */
    private static FinancialAccount anAccount(UUID userId) {
        return new FinancialAccount(userId, AccountType.SAVINGS, "HDFC Bank", "XXXX1234", "Salary account",
            Money.inr("1000.00"), Instant.now(), RecordSource.MANUAL);
    }

    /**
     * Builds a minimal aggregator debit of 1.00.
     *
     * @param account owning account
     * @param date value and booking date
     * @param dedupKey deduplication key
     * @return an unsaved transaction
     */
    private static AccountTransaction aTransaction(FinancialAccount account, LocalDate date, String dedupKey) {
        return new AccountTransaction(account.getId(), account.getUserId(), date, date, Money.inr("-1.00"),
            null, null, null, RecordSource.AA, dedupKey);
    }
}
