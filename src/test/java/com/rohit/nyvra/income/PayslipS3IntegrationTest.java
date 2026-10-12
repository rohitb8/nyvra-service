package com.rohit.nyvra.income;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests of payslip handling with a real S3 API (the LocalStack emulator in Testcontainers): the file lands in the
 * bucket, the background parser turns a real PDF into {@code PARSED} fields, an unreadable file ends
 * {@code FAILED}, and {@code Idempotency-Key} replays a repeated upload but rejects the same key with a different
 * file.
 */
@AutoConfigureMockMvc
class PayslipS3IntegrationTest extends AbstractIntegrationTest {

    /** Bucket created for the tests. */
    private static final String BUCKET = "payslip-test";

    /** Access key and secret the emulator accepts; it checks nothing, so any value works. */
    private static final String CREDENTIAL = "test";

    /**
     * LocalStack's S3 emulator, started once for this class. MinIO's own image is no longer published on Docker
     * Hub; the client speaks plain S3, so any S3-compatible server exercises the same code.
     */
    private static final GenericContainer<?> S3 = new GenericContainer<>(DockerImageName.parse("localstack/localstack:3.8"))
        .withEnv("SERVICES", "s3").withExposedPorts(4566)
        .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200));

    static {
        S3.start();
    }

    /** @return the emulator's S3 endpoint URL */
    private static String endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(4566);
    }

    /** Drives the API through the full filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** The store under test, wired to MinIO. */
    @Autowired
    private PayslipStorage storage;

    /**
     * Points the application at the MinIO container and creates the bucket.
     *
     * @param registry the property registry to add to
     * @throws Exception if the bucket cannot be created
     */
    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) throws Exception {
        MinioClient client = MinioClient.builder().endpoint(endpoint())
            .credentials(CREDENTIAL, CREDENTIAL).build();
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        registry.add("nyvra.income.payslip-storage.type", () -> "s3");
        registry.add("nyvra.income.payslip-storage.endpoint", PayslipS3IntegrationTest::endpoint);
        registry.add("nyvra.income.payslip-storage.bucket", () -> BUCKET);
        registry.add("nyvra.income.payslip-storage.access-key", () -> CREDENTIAL);
        registry.add("nyvra.income.payslip-storage.secret-key", () -> CREDENTIAL);
        registry.add("nyvra.income.payslip-storage.region", () -> "us-east-1");
    }

    /**
     * Authenticates a request as the given token subject.
     *
     * @param subject the JWT subject
     * @return the request post-processor
     */
    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /**
     * Creates a source and one entry for the caller through the API.
     *
     * @param subject the caller
     * @return the entry id
     * @throws Exception on request failure
     */
    private String anEntry(String subject) throws Exception {
        String source = mockMvc.perform(post("/api/v1/income/sources").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\",\"type\":\"SALARY\",\"cadence\":\"IRREGULAR\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String sourceId = JsonPath.read(source, "$.id");
        LocalDate day = LocalDate.now().minusDays(3);
        String entry = mockMvc.perform(post("/api/v1/income/entries").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sourceId":"%s","periodStart":"%s","periodEnd":"%s",
                     "grossAmount":{"amount":"80000.00","currency":"INR"},
                     "netAmount":{"amount":"70000.00","currency":"INR"},"receivedOn":"%s"}"""
                    .formatted(sourceId, day, day, day)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(entry, "$.id");
    }

    /**
     * Uploads a file to an entry, optionally with an idempotency key.
     *
     * @param entryId the entry
     * @param subject the caller
     * @param bytes   the file content
     * @param key     the {@code Idempotency-Key}, or {@code null} for none
     * @return the result, ready for assertions
     * @throws Exception on request failure
     */
    private ResultActions upload(String entryId, String subject, byte[] bytes, String key) throws Exception {
        // A part (not .file) so the request exposes getParts(), as a real servlet container does.
        MockPart part = new MockPart("file", "slip.pdf", bytes);
        part.getHeaders().setContentType(MediaType.APPLICATION_PDF);
        var request = multipart("/api/v1/income/entries/" + entryId + "/payslip").part(part).with(as(subject));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    /**
     * Waits for the payslip to leave {@code PENDING} and returns the GET body.
     *
     * @param entryId the entry
     * @param subject the caller
     * @return the payslip JSON
     */
    private String awaitParsed(String entryId, String subject) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).until(() -> {
            String body = mockMvc.perform(get("/api/v1/income/entries/" + entryId + "/payslip").with(as(subject)))
                .andReturn().getResponse().getContentAsString();
            return "PENDING".equals(JsonPath.read(body, "$.parseStatus")) ? null : body;
        }, body -> body != null);
    }

    /**
     * A real PDF is stored in the bucket and parsed in the background.
     *
     * @throws Exception on request or storage failure
     */
    @Test
    void storesInTheBucketAndParsesAPdf() throws Exception {
        String subject = "s3-" + UUID.randomUUID();
        String entryId = anEntry(subject);
        byte[] pdf = PdfPayslipParserTest.pdf(List.of("Acme Technologies Pvt Ltd",
            "Pay Period: 01-Sep-2026 to 30-Sep-2026", "Gross Earnings 80,000.00", "Provident Fund 1,800.00",
            "Net Pay 70,000.00"));

        upload(entryId, subject, pdf, null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.parseStatus", equalTo("PENDING")));

        String body = awaitParsed(entryId, subject);
        assertThat((String) JsonPath.read(body, "$.parseStatus")).isEqualTo("PARSED");
        assertThat((String) JsonPath.read(body, "$.parsedFields.grossAmount.amount")).isEqualTo("80000.00");
        assertThat((String) JsonPath.read(body, "$.parsedFields.netAmount.amount")).isEqualTo("70000.00");
        assertThat((String) JsonPath.read(body, "$.parsedFields.employer")).isEqualTo("Acme Technologies Pvt Ltd");
        assertThat((String) JsonPath.read(body, "$.parsedFields.deductions[0].label")).isEqualTo("Provident fund");
    }

    /**
     * The store round-trips bytes and ignores deleting a missing key.
     *
     * @throws Exception on storage failure
     */
    @Test
    void storageRoundTripsAndDeletes() throws Exception {
        String key = "payslips/test/" + UUID.randomUUID();
        storage.put(key, new ByteArrayInputStream("hello".getBytes()), "application/pdf");
        try (InputStream in = storage.open(key)) {
            assertThat(new String(in.readAllBytes())).isEqualTo("hello");
        }
        storage.delete(key);
        storage.delete(key);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> storage.open(key));
    }

    /**
     * A PDF with no pay figures is recorded as {@code FAILED}.
     *
     * @throws Exception on request failure
     */
    @Test
    void anUnreadableFileEndsFailed() throws Exception {
        String subject = "s3-" + UUID.randomUUID();
        String entryId = anEntry(subject);

        upload(entryId, subject, PdfPayslipParserTest.pdf(List.of("Nothing useful here")), null)
            .andExpect(status().isCreated());

        String body = awaitParsed(entryId, subject);
        assertThat((String) JsonPath.read(body, "$.parseStatus")).isEqualTo("FAILED");
    }

    /**
     * Repeating an upload with the same key replays the first response; the same key with a different file is a
     * conflict, even if the files are the same size.
     *
     * @throws Exception on request failure
     */
    @Test
    void honoursTheIdempotencyKeyOnUpload() throws Exception {
        String subject = "s3-" + UUID.randomUUID();
        String entryId = anEntry(subject);
        String key = UUID.randomUUID().toString();
        byte[] first = PdfPayslipParserTest.pdf(List.of("Gross Earnings 80,000.00"));
        byte[] sameSizeDifferent = first.clone();
        sameSizeDifferent[sameSizeDifferent.length - 2] ^= 1;

        String created = upload(entryId, subject, first, key).andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andReturn().getResponse().getContentAsString();
        String replayed = upload(entryId, subject, first, key).andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(replayed, "$.id")).isEqualTo(JsonPath.read(created, "$.id"));

        upload(entryId, subject, sameSizeDifferent, key).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("IDEMPOTENCY_KEY_REUSED")));
    }
}
