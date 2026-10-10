package com.rohit.nyvra.income;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of the income API against a real PostgreSQL (Testcontainers) with a stub JWT decoder:
 * ownership scoping (404 for other users), validation (400), business-rule errors (422 with codes such as
 * {@code INCOME_PERIOD_OVERLAP}), read-only detected entries (409) and listing behaviour. Each test uses a
 * fresh subject so tests never see each other's data.
 */
@AutoConfigureMockMvc
class IncomeControllerIntegrationTest extends AbstractIntegrationTest {

    /** Drives the controller through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Used to insert an {@code AA_DETECTED} entry, which the API cannot create. */
    @Autowired
    private IncomeEntryRepository entries;

    /** Resolves the internal user id that JIT provisioning created for a subject. */
    @Autowired
    private UserProfileRepository users;

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
        return "inc-" + UUID.randomUUID();
    }

    /**
     * Creates a monthly SALARY source expecting 85000.00 INR through the API and checks the 201 response.
     *
     * @param subject the caller
     * @param name the source name
     * @return the new source id
     * @throws Exception on request failure
     */
    private String createSource(String subject, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/income/sources").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"%s","type":"SALARY","cadence":"MONTHLY",
                     "expectedAmount":{"amount":"85000.00","currency":"INR"}}""".formatted(name)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.active", equalTo(true)))
            .andExpect(jsonPath("$.currency", equalTo("INR")))
            .andExpect(jsonPath("$.expectedAmount.amount", equalTo("85000.00")))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Builds a create-entry request body in INR.
     *
     * @param sourceId the source id
     * @param start first covered day
     * @param end last covered day
     * @param gross gross amount
     * @param net net amount
     * @param receivedOn received-on date
     * @return the JSON text
     */
    private static String entryJson(String sourceId, String start, String end, String gross, String net,
                                    String receivedOn) {
        return """
            {"sourceId":"%s","periodStart":"%s","periodEnd":"%s",
             "grossAmount":{"amount":"%s","currency":"INR"},"netAmount":{"amount":"%s","currency":"INR"},
             "receivedOn":"%s"}""".formatted(sourceId, start, end, gross, net, receivedOn);
    }

    /**
     * Creates an entry with gross 100000.00 INR, received on the period's last day, and checks the 201 response
     * shows origin {@code MANUAL} and no payslip.
     *
     * @param subject the caller
     * @param sourceId the source id
     * @param start first covered day
     * @param end last covered day
     * @param net net amount
     * @return the new entry id
     * @throws Exception on request failure
     */
    private String createEntry(String subject, String sourceId, String start, String end, String net)
            throws Exception {
        String body = mockMvc.perform(post("/api/v1/income/entries").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(entryJson(sourceId, start, end, "100000.00", net, end)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.origin", equalTo("MANUAL")))
            .andExpect(jsonPath("$.hasPayslip", equalTo(false)))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
      * Creating, fetching and patching a source round-trips: the patch changes name and expected amount and leaves
      * the cadence alone.
     */
    @Test
    void createsFetchesAndUpdatesASource() throws Exception {
        String subject = newSubject();
        String id = createSource(subject, "Acme salary");

        mockMvc.perform(get("/api/v1/income/sources/" + id).with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name", equalTo("Acme salary")))
            .andExpect(jsonPath("$.type", equalTo("SALARY")));

        mockMvc.perform(patch("/api/v1/income/sources/" + id).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Corp\",\"expectedAmount\":{\"amount\":\"90000.00\",\"currency\":\"INR\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name", equalTo("Acme Corp")))
            .andExpect(jsonPath("$.expectedAmount.amount", equalTo("90000.00")))
            .andExpect(jsonPath("$.cadence", equalTo("MONTHLY")));
    }

    /**
      * An IRREGULAR source needs no amount; clearing the amount of a MONTHLY source is 422 EXPECTED_AMOUNT_REQUIRED,
      * omitting it
      * is a no-op, switching to IRREGULAR while clearing works, and switching IRREGULAR to MONTHLY without an amount
      * is 422.
     */
    @Test
    void irregularSourcesNeedNoAmountAndNullClearsItOnlyForThem() throws Exception {
        String subject = newSubject();
        String body = mockMvc.perform(post("/api/v1/income/sources").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Freelance\",\"type\":\"BUSINESS\",\"cadence\":\"IRREGULAR\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.expectedAmount").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String irregular = JsonPath.read(body, "$.id");
        String monthly = createSource(subject, "Job");

        // clearing the amount on a MONTHLY source is rejected; omitting it is a no-op
        mockMvc.perform(patch("/api/v1/income/sources/" + monthly).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedAmount\":null}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("EXPECTED_AMOUNT_REQUIRED")));
        mockMvc.perform(patch("/api/v1/income/sources/" + monthly).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Job 2\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expectedAmount.amount", equalTo("85000.00")));
        // switching to IRREGULAR and clearing together works
        mockMvc.perform(patch("/api/v1/income/sources/" + monthly).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cadence\":\"IRREGULAR\",\"expectedAmount\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expectedAmount").doesNotExist());
        // switching an IRREGULAR source to MONTHLY without an amount is rejected
        mockMvc.perform(patch("/api/v1/income/sources/" + irregular).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cadence\":\"MONTHLY\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("EXPECTED_AMOUNT_REQUIRED")));
    }

    /** Creating a MONTHLY source without an expected amount is rejected with 422 EXPECTED_AMOUNT_REQUIRED. */
    @Test
    void createSourceRequiresExpectedAmountUnlessIrregular() throws Exception {
        mockMvc.perform(post("/api/v1/income/sources").with(as(newSubject()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rent\",\"type\":\"RENTAL\",\"cadence\":\"MONTHLY\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("EXPECTED_AMOUNT_REQUIRED")));
    }

    /**
      * Malformed source requests (missing or blank name, unknown enum, zero or over-precise amount, bad JSON) are
      * 400, and a non-INR amount is 422 UNSUPPORTED_CURRENCY.
     */
    @Test
    void rejectsInvalidSourceRequests() throws Exception {
        String subject = newSubject();
        // missing name / type, blank name, unknown enum, zero amount, malformed json
        mockMvc.perform(post("/api/v1/income/sources").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cadence\":\"IRREGULAR\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/income/sources").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \",\"type\":\"SALARY\",\"cadence\":\"IRREGULAR\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/income/sources").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"type\":\"LOTTERY\",\"cadence\":\"IRREGULAR\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/income/sources").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"x","type":"SALARY","cadence":"MONTHLY","expectedAmount":{"amount":"0.00","currency":"INR"}}"""))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/income/sources").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"x","type":"SALARY","cadence":"MONTHLY","expectedAmount":{"amount":"1.234","currency":"INR"}}"""))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/income/sources").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/income/sources").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"x","type":"SALARY","cadence":"MONTHLY","expectedAmount":{"amount":"5.00","currency":"USD"}}"""))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("UNSUPPORTED_CURRENCY")));
    }

    /**
      * Another user's source and entry are reported as 404 for get, patch and delete, recording an entry against
      * their source is 404,
     * they are absent from the stranger's lists, and the owner still sees them with source name and type.
     */
    @Test
    void anotherUsersSourceAndEntryAre404() throws Exception {
        String owner = newSubject();
        String stranger = newSubject();
        String sourceId = createSource(owner, "Private");
        String entryId = createEntry(owner, sourceId, "2026-01-01", "2026-01-31", "80000.00");

        mockMvc.perform(get("/api/v1/income/sources/" + sourceId).with(as(stranger))).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/income/sources/" + sourceId).with(as(stranger))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/income/sources/" + sourceId).with(as(stranger)))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/income/entries/" + entryId).with(as(stranger))).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/income/entries/" + entryId).with(as(stranger))
                .contentType(MediaType.APPLICATION_JSON).content("{\"receivedOn\":\"2026-02-01\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/income/entries/" + entryId).with(as(stranger)))
            .andExpect(status().isNotFound());
        // recording an entry against someone else's source is also a 404
        mockMvc.perform(post("/api/v1/income/entries").with(as(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content(entryJson(sourceId, "2026-02-01", "2026-02-28", "10.00", "9.00", "2026-02-28")))
            .andExpect(status().isNotFound());

        // and neither shows up in the stranger's lists
        mockMvc.perform(get("/api/v1/income/sources").with(as(stranger)))
            .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/v1/income/entries").with(as(stranger)))
            .andExpect(jsonPath("$.content", hasSize(0)));
        // the owner still sees everything
        mockMvc.perform(get("/api/v1/income/entries/" + entryId).with(as(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sourceName", equalTo("Private")))
            .andExpect(jsonPath("$.sourceType", equalTo("SALARY")));
    }

    /**
      * The source list only contains the caller's sources, defaults to name ascending, and supports paging, type and
      * active filters and an explicit descending sort.
     */
    @Test
    void sourceListIsPagedFilteredAndSorted() throws Exception {
        String subject = newSubject();
        createSource(subject, "B salary");
        createSource(subject, "A salary");
        String rent = mockMvc.perform(post("/api/v1/income/sources").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Flat\",\"type\":\"RENTAL\",\"cadence\":\"IRREGULAR\"}"))
            .andReturn().getResponse().getContentAsString();
        createSource(newSubject(), "Someone else's");
        mockMvc.perform(patch("/api/v1/income/sources/" + JsonPath.read(rent, "$.id")).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active", equalTo(false)));

        mockMvc.perform(get("/api/v1/income/sources").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(3)))
            .andExpect(jsonPath("$.content[0].name", equalTo("A salary")))
            .andExpect(jsonPath("$.totalElements", equalTo(3)));
        mockMvc.perform(get("/api/v1/income/sources").param("size", "2").param("page", "1").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.totalPages", equalTo(2)))
            .andExpect(jsonPath("$.page", equalTo(1)));
        mockMvc.perform(get("/api/v1/income/sources").param("type", "RENTAL").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get("/api/v1/income/sources").param("active", "true").param("sort", "name,desc")
                .with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].name", equalTo("B salary")));
    }

    /**
      * Both lists reject a bad page size, negative page, unknown sort field and unknown type with 400, and the entry
      * list also
     * rejects an unparsable date, a range where {@code to} is before {@code from}, and a non-UUID path id.
     */
    @Test
    void listRejectsBadPagingAndSortInputWith400() throws Exception {
        String subject = newSubject();
        for (String path : new String[] {"/api/v1/income/sources", "/api/v1/income/entries"}) {
            mockMvc.perform(get(path).param("size", "0").with(as(subject))).andExpect(status().isBadRequest());
            mockMvc.perform(get(path).param("size", "101").with(as(subject))).andExpect(status().isBadRequest());
            mockMvc.perform(get(path).param("page", "-1").with(as(subject))).andExpect(status().isBadRequest());
            mockMvc.perform(get(path).param("sort", "password,asc").with(as(subject)))
                .andExpect(status().isBadRequest());
            mockMvc.perform(get(path).param("type", "NOPE").with(as(subject))).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/income/entries").param("from", "yesterday").with(as(subject)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/income/entries").param("from", "2026-02-01").param("to", "2026-01-01")
                .with(as(subject)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/income/entries/not-a-uuid").with(as(subject)))
            .andExpect(status().isBadRequest());
    }

    /**
      * Deleting an unused source removes it (later 404), while deleting one with entries returns 204 but only
      * deactivates it and keeps its entries listed.
     */
    @Test
    void deletingASourceRemovesItOrDeactivatesItWhenItHasEntries() throws Exception {
        String subject = newSubject();
        String unused = createSource(subject, "Unused");
        String used = createSource(subject, "Used");
        createEntry(subject, used, "2026-01-01", "2026-01-31", "80000.00");

        mockMvc.perform(delete("/api/v1/income/sources/" + unused).with(as(subject)))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/income/sources/" + unused).with(as(subject))).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/income/sources/" + used).with(as(subject))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/income/sources/" + used).with(as(subject)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active", equalTo(false)));
        mockMvc.perform(get("/api/v1/income/entries").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].sourceName", equalTo("Used")));
    }

    /**
      * An entry can be created, fetched with its amounts and dates, patched (net and received-on change, gross is
      * kept), deleted, and is then 404.
     */
    @Test
    void entryCrudRoundTrip() throws Exception {
        String subject = newSubject();
        String sourceId = createSource(subject, "Job");
        String entryId = createEntry(subject, sourceId, "2026-01-01", "2026-01-31", "80000.00");

        mockMvc.perform(get("/api/v1/income/entries/" + entryId).with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.grossAmount.amount", equalTo("100000.00")))
            .andExpect(jsonPath("$.netAmount.amount", equalTo("80000.00")))
            .andExpect(jsonPath("$.periodStart", equalTo("2026-01-01")))
            .andExpect(jsonPath("$.linkedTransactionId").doesNotExist());

        mockMvc.perform(patch("/api/v1/income/entries/" + entryId).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"netAmount\":{\"amount\":\"75000.50\",\"currency\":\"INR\"},\"receivedOn\":\"2026-02-02\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.netAmount.amount", equalTo("75000.50")))
            .andExpect(jsonPath("$.grossAmount.amount", equalTo("100000.00")))
            .andExpect(jsonPath("$.receivedOn", equalTo("2026-02-02")));

        mockMvc.perform(delete("/api/v1/income/entries/" + entryId).with(as(subject)))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/income/entries/" + entryId).with(as(subject))).andExpect(status().isNotFound());
    }

    /**
      * The entry list defaults to received-on descending and supports paging, filtering by source, by source type and
      * by received-on range, and sorting by net amount.
     */
    @Test
    void entryListFiltersSortsAndPages() throws Exception {
        String subject = newSubject();
        String job = createSource(subject, "Job");
        String rentBody = mockMvc.perform(post("/api/v1/income/sources").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Flat","type":"RENTAL","cadence":"MONTHLY",
                     "expectedAmount":{"amount":"20000.00","currency":"INR"}}"""))
            .andReturn().getResponse().getContentAsString();
        String rent = JsonPath.read(rentBody, "$.id");
        createEntry(subject, job, "2026-01-01", "2026-01-31", "80000.00");
        createEntry(subject, job, "2026-02-01", "2026-02-28", "81000.00");
        createEntry(subject, job, "2026-03-01", "2026-03-31", "82000.00");
        createEntry(subject, rent, "2026-01-01", "2026-01-31", "20000.00");

        mockMvc.perform(get("/api/v1/income/entries").with(as(subject)))
            .andExpect(jsonPath("$.totalElements", equalTo(4)))
            .andExpect(jsonPath("$.content[0].receivedOn", equalTo("2026-03-31")));
        mockMvc.perform(get("/api/v1/income/entries").param("size", "3").param("page", "1").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.totalPages", equalTo(2)));
        mockMvc.perform(get("/api/v1/income/entries").param("sourceId", rent).with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].sourceType", equalTo("RENTAL")));
        mockMvc.perform(get("/api/v1/income/entries").param("type", "SALARY").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(3)));
        mockMvc.perform(get("/api/v1/income/entries").param("from", "2026-02-01").param("to", "2026-02-28")
                .with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].netAmount.amount", equalTo("81000.00")));
        mockMvc.perform(get("/api/v1/income/entries").param("sort", "netAmount,asc").param("type", "SALARY")
                .with(as(subject)))
            .andExpect(jsonPath("$.content[0].netAmount.amount", equalTo("80000.00")));
    }

    /**
      * Net above gross is 422 NET_EXCEEDS_GROSS both when creating an entry and when a patch raises net above the
      * stored gross.
     */
    @Test
    void rejectsNetAboveGrossWith422() throws Exception {
        String subject = newSubject();
        String sourceId = createSource(subject, "Job");
        mockMvc.perform(post("/api/v1/income/entries").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content(entryJson(sourceId, "2026-01-01", "2026-01-31", "100.00", "100.01", "2026-01-31")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("NET_EXCEEDS_GROSS")));

        String entryId = createEntry(subject, sourceId, "2026-01-01", "2026-01-31", "80000.00");
        mockMvc.perform(patch("/api/v1/income/entries/" + entryId).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"netAmount\":{\"amount\":\"100000.01\",\"currency\":\"INR\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("NET_EXCEEDS_GROSS")));
    }

    /**
      * A period sharing even one day with another entry of the same source is 422 INCOME_PERIOD_OVERLAP, on create
      * and when moving an entry
     * onto its neighbour; a different source may cover the same days and re-saving an entry's own period succeeds.
     */
    @Test
    void rejectsOverlappingPeriodsPerSourceWith422ButAllowsOtherSources() throws Exception {
        String subject = newSubject();
        String job = createSource(subject, "Job");
        String other = createSource(subject, "Side gig");
        String first = createEntry(subject, job, "2026-01-01", "2026-01-31", "80000.00");
        String second = createEntry(subject, job, "2026-02-01", "2026-02-28", "80000.00");

        // shares the single day 2026-01-31
        mockMvc.perform(post("/api/v1/income/entries").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content(entryJson(job, "2026-01-31", "2026-01-31", "10.00", "9.00", "2026-01-31")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("INCOME_PERIOD_OVERLAP")));
        // a different source may cover the same days
        createEntry(subject, other, "2026-01-01", "2026-01-31", "5000.00");

        // moving an entry onto its neighbour overlaps; re-saving its own period doesn't
        mockMvc.perform(patch("/api/v1/income/entries/" + second).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"periodStart\":\"2026-01-15\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("INCOME_PERIOD_OVERLAP")));
        mockMvc.perform(patch("/api/v1/income/entries/" + first).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"periodStart\":\"2026-01-01\",\"periodEnd\":\"2026-01-31\"}"))
            .andExpect(status().isOk());
    }

    /**
      * Entry requests with end before start, zero gross, negative net, missing fields, a malformed date or a patch
      * moving the end
     * before the stored start are 400, and an unknown source id is 404.
     */
    @Test
    void rejectsInvalidEntryRequestsWith400() throws Exception {
        String subject = newSubject();
        String sourceId = createSource(subject, "Job");
        // end before start
        mockMvc.perform(post("/api/v1/income/entries").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content(entryJson(sourceId, "2026-02-01", "2026-01-01", "10.00", "9.00", "2026-02-01")))
            .andExpect(status().isBadRequest());
        // zero gross
        mockMvc.perform(post("/api/v1/income/entries").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content(entryJson(sourceId, "2026-01-01", "2026-01-31", "0.00", "0.00", "2026-01-31")))
            .andExpect(status().isBadRequest());
        // negative net
        mockMvc.perform(post("/api/v1/income/entries").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content(entryJson(sourceId, "2026-01-01", "2026-01-31", "10.00", "-1.00", "2026-01-31")))
            .andExpect(status().isBadRequest());
        // missing fields and a bad date
        mockMvc.perform(post("/api/v1/income/entries").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceId\":\"" + sourceId + "\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/income/entries").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content(entryJson(sourceId, "01/01/2026", "2026-01-31", "10.00", "9.00", "2026-01-31")))
            .andExpect(status().isBadRequest());
        // unknown source
        mockMvc.perform(post("/api/v1/income/entries").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content(entryJson(UUID.randomUUID().toString(), "2026-01-01", "2026-01-31", "10.00", "9.00",
                    "2026-01-31")))
            .andExpect(status().isNotFound());
        // patch that moves the end before the stored start
        String entryId = createEntry(subject, sourceId, "2026-03-01", "2026-03-31", "80000.00");
        mockMvc.perform(patch("/api/v1/income/entries/" + entryId).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"periodEnd\":\"2026-02-01\"}"))
            .andExpect(status().isBadRequest());
    }

    /** An AA_DETECTED entry can be read but patching or deleting it is 409 SOURCE_READ_ONLY. */
    @Test
    void aDetectedEntryIsReadOnly() throws Exception {
        String subject = newSubject();
        String sourceId = createSource(subject, "Job");
        UserProfile user = users.findByKeycloakSubject(subject).orElseThrow();
        IncomeEntry detected = entries.save(new IncomeEntry(UUID.fromString(sourceId), user.getId(),
            LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"), Money.inr("100.00"), Money.inr("90.00"),
            LocalDate.parse("2026-04-30"), null, IncomeOrigin.AA_DETECTED));
        String id = detected.getId().toString();

        mockMvc.perform(patch("/api/v1/income/entries/" + id).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"receivedOn\":\"2026-05-01\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("SOURCE_READ_ONLY")));
        mockMvc.perform(delete("/api/v1/income/entries/" + id).with(as(subject)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("SOURCE_READ_ONLY")));
        mockMvc.perform(get("/api/v1/income/entries/" + id).with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.origin", equalTo("AA_DETECTED")))
            .andExpect(jsonPath("$.linkedTransactionId").doesNotExist());
    }

    /** Both list endpoints return 401 without a bearer token. */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/income/sources")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/income/entries")).andExpect(status().isUnauthorized());
    }
}
