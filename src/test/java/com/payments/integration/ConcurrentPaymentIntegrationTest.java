package com.payments.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.payments.account.Account;
import com.payments.account.AccountRepository;
import com.payments.account.AccountService;
import com.payments.payment.Payment;
import com.payments.payment.PaymentRepository;
import com.payments.payment.PaymentStatus;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ConcurrentPaymentIntegrationTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private HttpHeaders headers(String jwt, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(jwt);
        h.add("Idempotency-Key", idempotencyKey);
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

    private String body(UUID source, UUID dest, String amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destAccountId\":\"" + dest
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\"}";
    }

    private void waitUntilNoPending(int expectedTotal, UUID source) {
        for (int i = 0; i < 120; i++) {     // up to ~30s
            if (paymentRepository.countByStatus(PaymentStatus.PENDING) == 0
                    && paymentRepository.countBySourceAccountId(source) == expectedTotal) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        fail("Payments did not finish processing within timeout");
    }

    @Test
    void concurrentPayments_doNotExceedBalance() throws Exception {
        UUID source = createFundedAccount("500.00");
        UUID dest = createAccount();
        String jwt = mintJwt(source);

        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    rest().exchange(url("/api/v1/payments"), HttpMethod.POST,
                            new HttpEntity<>(body(source, dest, "100.00"),
                                    headers(jwt, UUID.randomUUID().toString())), String.class);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();   // fire all simultaneously
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        waitUntilNoPending(n, source);

        BigDecimal balance = accountRepository.findById(source).orElseThrow().getBalance();
        assertTrue(balance.compareTo(BigDecimal.ZERO) >= 0, "balance must never go negative: " + balance);

        List<Payment> mine = paymentRepository.findAll().stream()
                .filter(p -> p.getSourceAccountId().equals(source))
                .toList();
        long authorizedOrLater = mine.stream().filter(p -> isAuthorizedOrLater(p.getStatus())).count();
        assertEquals(5, authorizedOrLater, "exactly 5 payments of $100 fit in a $500 balance");

        BigDecimal authorizedSum = mine.stream()
                .filter(p -> isAuthorizedOrLater(p.getStatus()))
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(authorizedSum.compareTo(new BigDecimal("500.00")) <= 0,
                "authorized total must not exceed the starting balance: " + authorizedSum);
    }

    private boolean isAuthorizedOrLater(PaymentStatus s) {
        return s == PaymentStatus.AUTHORIZED || s == PaymentStatus.CAPTURED
                || s == PaymentStatus.SETTLEMENT_QUEUED || s == PaymentStatus.SETTLED
                || s == PaymentStatus.REFUNDED;
    }

    @Test
    void idempotent_concurrentRequests_produceOnePayment() throws Exception {
        UUID source = createFundedAccount("500.00");
        UUID dest = createAccount();
        String jwt = mintJwt(source);
        String idempotencyKey = UUID.randomUUID().toString();
        String payload = body(source, dest, "100.00");

        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<String> resp = rest().exchange(url("/api/v1/payments"), HttpMethod.POST,
                            new HttpEntity<>(payload, headers(jwt, idempotencyKey)), String.class);
                    if (resp.getStatusCode() == HttpStatus.ACCEPTED) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, paymentRepository.countBySourceAccountId(source),
                "exactly one payment despite concurrent same-key requests");
        assertEquals(n, accepted.get(), "all concurrent same-key requests must return 202");
    }
}
