package com.payments.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.payments.account.Account;
import com.payments.account.AccountRepository;
import com.payments.account.AccountService;
import com.payments.processor.MockProcessorAdapter;
import com.payments.processor.ProcessorRecord;
import com.payments.reconciliation.ReconciliationReport;
import com.payments.reconciliation.ReconciliationReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ReconciliationJobIntegrationTest {

    private static final String TEST_SECRET = "test-secret-key-minimum-32-chars-ok";

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @LocalServerPort
    int port;
    @Autowired
    AccountService accountService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    ReconciliationReportRepository reconciliationReportRepository;
    @Autowired
    JobLauncher jobLauncher;
    @Autowired
    Job reconciliationJob;
    @Autowired
    Job settlementJob;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    MockProcessorAdapter processorAdapter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanData() {
        // Isolate each method: exact discrepancy counts require a clean dataset.
        jdbcTemplate.execute("TRUNCATE TABLE payments, accounts, ledger_entries, "
                + "payment_status_history, settlement_entries, settlement_batches, "
                + "reconciliation_reports, refunds, idempotency_keys RESTART IDENTITY CASCADE");
    }

    // ---------- helpers ----------

    private RestTemplate rest() {
        RestTemplate rt = new RestTemplate();
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return rt;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String mintJwt(UUID accountId) {
        SecretKeySpec key = new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .claim("accountIds", List.of(accountId.toString()))
                .claim("role", "USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private HttpHeaders headers(String jwt) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(jwt);
        return h;
    }

    private UUID createFundedAccount(String balance) {
        Account a = accountService.create(UUID.randomUUID(), "USD");
        a.credit(new BigDecimal(balance));
        accountRepository.save(a);
        return a.getId();
    }

    private UUID createAccount() {
        return accountService.create(UUID.randomUUID(), "USD").getId();
    }

    private String field(String json, String name) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode v = node.get(name);
            return v == null || v.isNull() ? null : v.asText();
        } catch (Exception e) {
            throw new IllegalStateException("read " + name + " from " + json, e);
        }
    }

    private ResponseEntity<String> get(UUID id, String jwt) {
        return rest().exchange(url("/api/v1/payments/" + id), HttpMethod.GET,
                new HttpEntity<>(headers(jwt)), String.class);
    }

    private void waitForStatus(UUID id, String jwt, String target) {
        for (int i = 0; i < 40; i++) {
            ResponseEntity<String> resp = get(id, jwt);
            if (resp.getStatusCode() == HttpStatus.OK && target.equals(field(resp.getBody(), "status"))) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        fail("Payment " + id + " did not reach " + target);
    }

    /** Drive a payment to CAPTURED; return [paymentId, sourceId]. */
    private UUID[] capturedPayment(String balance, String amount) {
        UUID source = createFundedAccount(balance);
        UUID dest = createAccount();
        String jwt = mintJwt(source);

        HttpHeaders h = headers(jwt);
        h.add("Idempotency-Key", UUID.randomUUID().toString());
        String body = "{\"sourceAccountId\":\"" + source + "\",\"destAccountId\":\"" + dest
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\"}";
        ResponseEntity<String> created = rest().exchange(url("/api/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
        assertEquals(HttpStatus.ACCEPTED, created.getStatusCode());
        UUID paymentId = UUID.fromString(field(created.getBody(), "id"));

        waitForStatus(paymentId, jwt, "AUTHORIZED");
        ResponseEntity<String> captured = rest().exchange(
                url("/api/v1/payments/" + paymentId + "/capture"), HttpMethod.POST,
                new HttpEntity<>(headers(jwt)), String.class);
        assertEquals(HttpStatus.OK, captured.getStatusCode());
        return new UUID[]{paymentId, source};
    }

    private void runReconciliation(LocalDate date) throws Exception {
        jobLauncher.run(reconciliationJob, new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .addString("date", date.toString())
                .toJobParameters());
    }

    // ---------- tests ----------

    @Test
    void reconciliation_detectsAmountMismatch() throws Exception {
        UUID[] ids = capturedPayment("500.00", "150.00");
        UUID paymentId = ids[0];
        String jwt = mintJwt(ids[1]);

        String processorRef = field(get(paymentId, jwt).getBody(), "processorRef");

        // Processor claims a different amount for the same processor_ref.
        doReturn(List.of(new ProcessorRecord(processorRef, new BigDecimal("155.00"), "CAPTURED")))
                .when(processorAdapter).getProcessedPayments(any());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        runReconciliation(today);

        ReconciliationReport report = reconciliationReportRepository.findByReportDate(today).orElseThrow();
        assertEquals("COMPLETED", report.getStatus().name());
        assertEquals(1, report.getTotalDiscrepant());
        assertTrue(report.getDiscrepancies().contains("AMOUNT_MISMATCH"),
                "discrepancies should contain AMOUNT_MISMATCH: " + report.getDiscrepancies());
    }

    @Test
    void reconciliation_matchesCleanPayments() throws Exception {
        UUID[] ids = capturedPayment("500.00", "150.00");
        UUID paymentId = ids[0];
        String jwt = mintJwt(ids[1]);

        // Drive to SETTLED so the processor's mirrored view matches a terminal status.
        jobLauncher.run(settlementJob, new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .addString("date", LocalDate.now(ZoneOffset.UTC).toString())
                .toJobParameters());
        waitForStatus(paymentId, jwt, "SETTLED");

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        runReconciliation(today);

        ReconciliationReport report = reconciliationReportRepository.findByReportDate(today).orElseThrow();
        assertEquals("COMPLETED", report.getStatus().name());
        assertTrue(report.getTotalMatched() >= 1, "expected at least one matched payment");
        assertEquals(0, report.getTotalDiscrepant());
    }
}
