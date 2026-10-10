package com.rohit.nyvra.income;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of the payslip upload/lookup endpoints and the rolling income summary against a real
 * PostgreSQL: file validation (400, 413, 415), replacement on re-upload, ownership scoping (404), and the
 * summary's window, averages, per-type shares and per-user isolation. Each test uses a fresh subject.
 */
@AutoConfigureMockMvc
class IncomePayslipAndSummaryIntegrationTest extends AbstractIntegrationTest {

    /** A minimal byte sequence that starts like a PDF. */
    private static final byte[] PDF = "%PDF-1.4 test".getBytes(StandardCharsets.ISO_8859_1);

    /** Drives the controller through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Authenticates a request as the given Keycloak subject, with a derived email claim.
     *
     * @param subject the token {@code sub}
     * @return a request post-processor attaching that JWT
     */
    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /** @return a unique subject, so each test works with its own user */
    private static String newSubject() {
        return "pay-" + UUID.randomUUID();
    }

    /** @return today in the display time zone, which the summary uses as the end of its window */
    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    /**
     * Creates a source through the API.
     *
     * @param subject the caller
     * @param name the source name
     * @param type the income type
     * @return the new source id
     * @throws Exception on request failure
     */
    private String createSource(String subject, String name, String type) throws Exception {
        String body = mockMvc.perform(post("/api/v1/income/sources").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"%s","type":"%s","cadence":"IRREGULAR"}""".formatted(name, type)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Records a one-day entry received on the given date through the API.
     *
     * @param subject the caller
     * @param sourceId the source
     * @param daysAgo how many days before today the money arrived
     * @param gross the gross amount
     * @param net the net amount
     * @return the new entry id
     * @throws Exception on request failure
     */
    private String createEntry(String subject, String sourceId, int daysAgo, String gross, String net)
            throws Exception {
        LocalDate day = today().minusDays(daysAgo);
        String body = mockMvc.perform(post("/api/v1/income/entries").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sourceId":"%s","periodStart":"%s","periodEnd":"%s",
                     "grossAmount":{"amount":"%s","currency":"INR"},"netAmount":{"amount":"%s","currency":"INR"},
                     "receivedOn":"%s"}""".formatted(sourceId, day, day, gross, net, day)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Creates a source and one entry for a new payslip test.
     *
     * @param subject the caller
     * @return the entry id
     * @throws Exception on request failure
     */
    private String anEntry(String subject) throws Exception {
        return createEntry(subject, createSource(subject, "Acme", "SALARY"), 3, "100000.00", "90000.00");
    }

    /**
     * Builds a payslip upload request.
     *
     * @param entryId the entry
     * @param subject the caller
     * @param name the file name
     * @param contentType the declared media type
     * @param bytes the file content
     * @return the request builder result, ready to perform
     * @throws Exception on request failure
     */
    private org.springframework.test.web.servlet.ResultActions upload(
            String entryId, String subject, String name, String contentType, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/v1/income/entries/" + entryId + "/payslip")
            .file(new MockMultipartFile("file", name, contentType, bytes)).with(as(subject)));
    }

    /**
     * A PDF upload answers 201 with a PENDING payslip, the entry then reports {@code hasPayslip}, and GET
     * returns the same payslip.
     */
    @Test
    void uploadsAPayslipAndReadsItBack() throws Exception {
        String subject = newSubject();
        String entryId = anEntry(subject);

        upload(entryId, subject, "may.pdf", "application/pdf", PDF)
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.incomeEntryId", equalTo(entryId)))
            .andExpect(jsonPath("$.fileName", equalTo("may.pdf")))
            .andExpect(jsonPath("$.contentType", equalTo("application/pdf")))
            .andExpect(jsonPath("$.sizeBytes", equalTo(PDF.length)))
            .andExpect(jsonPath("$.parseStatus", equalTo("PENDING")))
            .andExpect(jsonPath("$.parsedFields").doesNotExist());

        mockMvc.perform(get("/api/v1/income/entries/" + entryId + "/payslip").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileName", equalTo("may.pdf")));
        mockMvc.perform(get("/api/v1/income/entries/" + entryId).with(as(subject)))
            .andExpect(jsonPath("$.hasPayslip", equalTo(true)));
    }

    /** A second upload replaces the first, so GET reports only the newest file. */
    @Test
    void reuploadReplacesThePreviousPayslip() throws Exception {
        String subject = newSubject();
        String entryId = anEntry(subject);

        upload(entryId, subject, "old.pdf", "application/pdf", PDF).andExpect(status().isCreated());
        upload(entryId, subject, "dir/new.pdf", "application/pdf", PDF).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/income/entries/" + entryId + "/payslip").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileName", equalTo("new.pdf")));
    }

    /** An entry with no payslip answers 404 on GET. */
    @Test
    void getWithoutAPayslipIsNotFound() throws Exception {
        String subject = newSubject();
        String entryId = anEntry(subject);

        mockMvc.perform(get("/api/v1/income/entries/" + entryId + "/payslip").with(as(subject)))
            .andExpect(status().isNotFound());
    }

    /** Another user's entry is invisible: both upload and GET answer 404. */
    @Test
    void payslipsOfOtherUsersAreNotFound() throws Exception {
        String owner = newSubject();
        String entryId = anEntry(owner);
        upload(entryId, owner, "a.pdf", "application/pdf", PDF).andExpect(status().isCreated());
        String intruder = newSubject();

        upload(entryId, intruder, "b.pdf", "application/pdf", PDF).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/income/entries/" + entryId + "/payslip").with(as(intruder)))
            .andExpect(status().isNotFound());
    }

    /** Wrong declared type or content that does not match its type is 415; an empty file is 400. */
    @Test
    void rejectsUnsupportedOrEmptyFiles() throws Exception {
        String subject = newSubject();
        String entryId = anEntry(subject);

        upload(entryId, subject, "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code", equalTo("PAYSLIP_UNSUPPORTED_TYPE")));
        upload(entryId, subject, "fake.png", "image/png", PDF)
            .andExpect(status().isUnsupportedMediaType());
        upload(entryId, subject, "empty.pdf", "application/pdf", new byte[0])
            .andExpect(status().isBadRequest());
    }

    /** A file above 5 MB is rejected with 413 and nothing is stored. */
    @Test
    void rejectsFilesOverFiveMegabytes() throws Exception {
        String subject = newSubject();
        String entryId = anEntry(subject);
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);

        upload(entryId, subject, "big.pdf", "application/pdf", big)
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code", equalTo("PAYSLIP_TOO_LARGE")));
        mockMvc.perform(get("/api/v1/income/entries/" + entryId + "/payslip").with(as(subject)))
            .andExpect(status().isNotFound());
    }

    /**
     * The 3-month summary counts only entries received in the window, averages over 3 months, orders types by
     * net total with one-decimal shares, and the 12-month window also picks up the older entry.
     */
    @Test
    void summarisesTheTrailingWindow() throws Exception {
        String subject = newSubject();
        String salary = createSource(subject, "Job", "SALARY");
        String rent = createSource(subject, "Flat", "RENTAL");
        createEntry(subject, salary, 10, "100000.00", "90000.00");
        createEntry(subject, salary, 40, "100000.00", "80000.00");
        createEntry(subject, rent, 5, "30000.00", "30000.00");
        createEntry(subject, salary, 200, "60000.00", "50000.00");

        mockMvc.perform(get("/api/v1/income/summary").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.windowMonths", equalTo(3)))
            .andExpect(jsonPath("$.to", equalTo(today().toString())))
            .andExpect(jsonPath("$.from", equalTo(today().minusMonths(3).plusDays(1).toString())))
            .andExpect(jsonPath("$.totalNet.amount", equalTo("200000.00")))
            .andExpect(jsonPath("$.monthlyAverageNet.amount", equalTo("66666.67")))
            .andExpect(jsonPath("$.monthlyAverageGross.amount", equalTo("76666.67")))
            .andExpect(jsonPath("$.byType", hasSize(2)))
            .andExpect(jsonPath("$.byType[0].type", equalTo("SALARY")))
            .andExpect(jsonPath("$.byType[0].totalNet.amount", equalTo("170000.00")))
            .andExpect(jsonPath("$.byType[0].sharePct", equalTo(85.0)))
            .andExpect(jsonPath("$.byType[1].type", equalTo("RENTAL")))
            .andExpect(jsonPath("$.byType[1].sharePct", equalTo(15.0)))
            .andExpect(jsonPath("$.computedAt").exists());

        mockMvc.perform(get("/api/v1/income/summary?window=12").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalNet.amount", equalTo("250000.00")))
            .andExpect(jsonPath("$.monthlyAverageNet.amount", equalTo("20833.33")));
        mockMvc.perform(get("/api/v1/income/summary?window=6").with(as(subject)))
            .andExpect(jsonPath("$.totalNet.amount", equalTo("200000.00")));
    }

    /** A user with no income gets zero totals and an empty type list, and never sees other users' income. */
    @Test
    void summaryIsEmptyWithoutIncomeAndScopedToTheCaller() throws Exception {
        String other = newSubject();
        createEntry(other, createSource(other, "Job", "SALARY"), 2, "100000.00", "90000.00");

        mockMvc.perform(get("/api/v1/income/summary").with(as(newSubject())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalNet.amount", equalTo("0.00")))
            .andExpect(jsonPath("$.monthlyAverageNet.amount", equalTo("0.00")))
            .andExpect(jsonPath("$.byType", hasSize(0)));
    }

    /** Windows other than 3, 6 or 12 are rejected with 400. */
    @Test
    void summaryRejectsAnUnsupportedWindow() throws Exception {
        mockMvc.perform(get("/api/v1/income/summary?window=5").with(as(newSubject())))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/income/summary?window=abc").with(as(newSubject())))
            .andExpect(status().isBadRequest());
    }
}
