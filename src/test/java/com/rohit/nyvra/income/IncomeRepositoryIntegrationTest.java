package com.rohit.nyvra.income;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;

/**
 * Repository-level tests against a real PostgreSQL: persistence and paging of sources and entries, the
 * overlap and irregular-source constraints, and encryption of parsed payslip fields at rest.
 */
class IncomeRepositoryIntegrationTest extends AbstractIntegrationTest {

    /** Creates the owning users. */
    @Autowired
    private UserProfileRepository users;

    /** Source repository under test. */
    @Autowired
    private IncomeSourceRepository sources;

    /** Entry repository under test. */
    @Autowired
    private IncomeEntryRepository entries;

    /** Payslip repository under test. */
    @Autowired
    private PayslipDocumentRepository payslips;

    /** Reads raw column bytes to prove encryption at rest. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Entries saved for a source come back newest period first with amounts intact. */
    @Test
    void savesASourceWithEntriesAndPagesThemNewestFirst() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        IncomeSource salary = sources.save(aMonthlySalary(user));
        entries.save(anEntry(salary, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31)));
        entries.save(anEntry(salary, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28)));

        var page = entries.findByUserIdOrderByPeriodStartDesc(user.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(IncomeEntry::getPeriodStart)
            .containsExactly(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 1, 1));
        assertThat(page.getContent().get(0).getNetAmount()).isEqualTo(Money.inr("98000.00"));
        assertThat(sources.findByUserIdAndActiveTrueOrderByNameAsc(user.getId()))
            .extracting(IncomeSource::getExpectedAmount).containsExactly(Money.inr("98000.00"));
    }

    /** A second entry overlapping an existing period of the same source is rejected by the database. */
    @Test
    void rejectsOverlappingPeriodsForTheSameSource() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        IncomeSource salary = sources.save(aMonthlySalary(user));
        entries.save(anEntry(salary, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31)));

        assertThatThrownBy(() -> entries.saveAndFlush(
            anEntry(salary, LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 29))))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Only an IRREGULAR source may omit its expected amount. */
    @Test
    void allowsAnIrregularSourceWithoutAnExpectedAmountOnly() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());

        IncomeSource freelance = sources.save(
            new IncomeSource(user.getId(), "Freelance", IncomeType.BUSINESS, "INR", IncomeCadence.IRREGULAR, null));

        assertThat(sources.findById(freelance.getId()).orElseThrow().getExpectedAmount()).isNull();
        assertThatThrownBy(() ->
            new IncomeSource(user.getId(), "Rent", IncomeType.RENTAL, "INR", IncomeCadence.MONTHLY, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Parsed payslip fields are unreadable in the raw column but decrypt back to the original JSON on load. */
    @Test
    void encryptsParsedPayslipFieldsAtRest() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        IncomeSource salary = sources.save(aMonthlySalary(user));
        IncomeEntry entry = entries.save(anEntry(salary, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31)));
        PayslipDocument payslip = new PayslipDocument(entry.getId(), "payslips/2025-05.pdf", "may.pdf", "application/pdf", 1234L, Instant.now());
        payslip.recordParsedFields("{\"basic\":\"52000.00\",\"hra\":\"26000.00\"}");
        payslips.save(payslip);

        byte[] atRest = jdbcTemplate.queryForObject(
            "SELECT parsed_fields FROM payslip_document WHERE id = ?", byte[].class, payslip.getId());

        assertThat(new String(atRest, StandardCharsets.ISO_8859_1)).doesNotContain("basic");
        assertThat(payslips.findByIncomeEntryId(entry.getId()))
            .singleElement()
            .extracting(PayslipDocument::getParsedFields)
            .isEqualTo("{\"basic\":\"52000.00\",\"hra\":\"26000.00\"}");
    }

    /**
     * Builds a monthly SALARY source expecting 98000.00 INR.
     *
     * @param user the owner
     * @return an unsaved source
     */
    private static IncomeSource aMonthlySalary(UserProfile user) {
        return new IncomeSource(user.getId(), "Acme Corp salary", IncomeType.SALARY, "INR",
            IncomeCadence.MONTHLY, Money.inr("98000.00"));
    }

    /**
     * Builds a manual entry for the source covering the given period, 120000.00 gross and 98000.00 net INR.
     *
     * @param source the source
     * @param start first covered day
     * @param end last covered day, also the received-on date
     * @return an unsaved entry
     */
    private static IncomeEntry anEntry(IncomeSource source, LocalDate start, LocalDate end) {
        return new IncomeEntry(source.getId(), source.getUserId(), start, end, Money.inr("120000.00"),
            Money.inr("98000.00"), end, null, IncomeOrigin.MANUAL);
    }
}
