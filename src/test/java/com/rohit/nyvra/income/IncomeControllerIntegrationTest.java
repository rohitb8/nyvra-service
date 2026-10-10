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

@AutoConfigureMockMvc
class IncomeControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IncomeEntryRepository entries;

    @Autowired
    private UserProfileRepository users;

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    private static String newSubject() {
        return "inc-" + UUID.randomUUID();
    }

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

    private static String entryJson(String sourceId, String start, String end, String gross, String net,
                                    String receivedOn) {
        return """
            {"sourceId":"%s","periodStart":"%s","periodEnd":"%s",
             "grossAmount":{"amount":"%s","currency":"INR"},"netAmount":{"amount":"%s","currency":"INR"},
             "receivedOn":"%s"}""".formatted(sourceId, start, end, gross, net, receivedOn);
    }

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

    @Test
    void createSourceRequiresExpectedAmountUnlessIrregular() throws Exception {
        mockMvc.perform(post("/api/v1/income/sources").with(as(newSubject()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rent\",\"type\":\"RENTAL\",\"cadence\":\"MONTHLY\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("EXPECTED_AMOUNT_REQUIRED")));
    }

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

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/income/sources")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/income/entries")).andExpect(status().isUnauthorized());
    }
}
