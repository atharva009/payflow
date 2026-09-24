package com.payments.integration;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.payments.account.Account;
import com.payments.account.AccountRepository;
import com.payments.account.AccountService;
import com.payments.exception.PermanentProcessorException;
import com.payments.exception.TransientProcessorException;
import com.payments.payment.Payment;
import com.payments.payment.PaymentRepository;
import com.payments.payment.PaymentStatus;
import com.payments.processor.MockProcessorAdapter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ProcessorResilienceIntegrationTest {

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
    PaymentRepository paymentRepository;
    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    MockProcessorAdapter processorAdapter;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE TABLE payments, accounts, ledger_entries, "
                + "payment_status_history, settlement_entries, settlement_batches, "
                + "reconciliation_reports, refunds, idempotency_keys RESTART IDENTITY CASCADE");
        circuitBreakerRegistry.circuitBreaker("processor").reset();
    }

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
        h.add("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    private UUID createPayment() {
        UUID source = accountService.create(UUID.randomUUID(), "USD").getId();
        Account a = accountRepository.findById(source).orElseThrow();
        a.credit(new BigDecimal("500.00"));
        accountRepository.save(a);
        UUID dest = accountService.create(UUID.randomUUID(), "USD").getId();
        String jwt = mintJwt(source);
        String body = "{\"sourceAccountId\":\"" + source + "\",\"destAccountId\":\"" + dest
                + "\",\"amount\":\"100.00\",\"currency\":\"USD\"}";
        ResponseEntity<String> resp = rest().exchange(url("/api/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, headers(jwt)), String.class);
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        try {
            return UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resp.getBody()).get("id").asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void waitForStatus(UUID paymentId, PaymentStatus target, int maxSeconds) {
        for (int i = 0; i < maxSeconds * 4; i++) {
            Payment p = paymentRepository.findById(paymentId).orElse(null);
            if (p != null && p.getStatus() == target) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        fail("Payment " + paymentId + " did not reach " + target);
    }

    @Test
    void retry_exhausted_marksPaymentFailed() {
        doThrow(new TransientProcessorException("boom"))
                .when(processorAdapter).charge(any(), any(), any());

        UUID paymentId = createPayment();
        waitForStatus(paymentId, PaymentStatus.FAILED, 15);

        Payment p = paymentRepository.findById(paymentId).orElseThrow();
        assertTrue(p.getFailureReason().contains("PROCESSOR_UNAVAILABLE"),
                "failureReason=" + p.getFailureReason());
        // 3 attempts: initial + 2 retries.
        verify(processorAdapter, atLeast(3)).charge(any(), any(), any());
    }

    @Test
    void permanentFailure_marksPaymentFailed_withoutRetry() {
        doThrow(new PermanentProcessorException("DECLINED"))
                .when(processorAdapter).charge(any(), any(), any());

        UUID paymentId = createPayment();
        waitForStatus(paymentId, PaymentStatus.FAILED, 10);

        Payment p = paymentRepository.findById(paymentId).orElseThrow();
        assertTrue(p.getFailureReason().contains("DECLINED"), "failureReason=" + p.getFailureReason());
        // Permanent failures are not retried.
        verify(processorAdapter, times(1)).charge(any(), any(), any());
    }

    @Test
    void circuitBreaker_opensAfterThreshold() {
        doThrow(new TransientProcessorException("boom"))
                .when(processorAdapter).charge(any(), any(), any());

        for (int i = 0; i < 10; i++) {
            createPayment();
        }

        // Wait for the poller to process the batch (all fail).
        for (int i = 0; i < 120; i++) {
            if (paymentRepository.countByStatus(PaymentStatus.PENDING) == 0
                    && paymentRepository.countByStatus(PaymentStatus.FAILED) >= 1) {
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        assertTrue(paymentRepository.countByStatus(PaymentStatus.FAILED) >= 1,
                "at least one payment should be failed");
        CircuitBreaker.State state = circuitBreakerRegistry.circuitBreaker("processor").getState();
        assertNotEquals(CircuitBreaker.State.CLOSED, state,
                "circuit breaker should have left CLOSED after repeated failures, was " + state);
    }
}
