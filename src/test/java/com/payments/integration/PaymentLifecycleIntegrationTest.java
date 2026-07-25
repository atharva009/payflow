package com.payments.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.payments.account.Account;
import com.payments.account.AccountRepository;
import com.payments.account.AccountService;
import com.payments.ledger.LedgerEntryRepository;
import com.payments.payment.PaymentRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PaymentLifecycleIntegrationTest {

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
    LedgerEntryRepository ledgerEntryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private String paymentBody(UUID source, UUID dest, String amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destAccountId\":\"" + dest
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\"}";
    }

    private ResponseEntity<String> postPayment(UUID source, UUID dest, String amount, String jwt, String key) {
        HttpHeaders h = headers(jwt);
        h.add("Idempotency-Key", key);
        return rest().exchange(url("/api/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(paymentBody(source, dest, amount), h), String.class);
    }

    private ResponseEntity<String> get(UUID id, String jwt) {
        return rest().exchange(url("/api/v1/payments/" + id), HttpMethod.GET,
                new HttpEntity<>(headers(jwt)), String.class);
    }

    private ResponseEntity<String> action(UUID id, String verb, String jwt) {
        return rest().exchange(url("/api/v1/payments/" + id + "/" + verb), HttpMethod.POST,
                new HttpEntity<>(headers(jwt)), String.class);
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

    private void waitForStatus(UUID id, String jwt, String target) {
        ResponseEntity<String> last = null;
        for (int i = 0; i < 40; i++) {     // up to ~10s
            last = get(id, jwt);
            if (last.getStatusCode() == HttpStatus.OK && target.equals(field(last.getBody(), "status"))) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        fail("Payment " + id + " did not reach " + target + "; last=" + last.getStatusCode()
                + " body=" + (last == null ? "null" : last.getBody()));
    }

    private int ledgerCount(UUID paymentId) {
        return ledgerEntryRepository.findByPaymentId(paymentId).size();
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    // ---------- tests ----------

    @Test
    void fullLifecycle_pendingToAuthorizedToCaptured() {
        UUID source = createFundedAccount("500.00");
        UUID dest = createAccount();
        String jwt = mintJwt(source);

        ResponseEntity<String> created = postPayment(source, dest, "150.00", jwt, UUID.randomUUID().toString());
        assertEquals(HttpStatus.ACCEPTED, created.getStatusCode());
        assertEquals("PENDING", field(created.getBody(), "status"));
        UUID paymentId = UUID.fromString(field(created.getBody(), "id"));

        waitForStatus(paymentId, jwt, "AUTHORIZED");

        assertEquals(0, balanceOf(source).compareTo(new BigDecimal("350.00")));
        assertEquals(2, ledgerCount(paymentId));

        ResponseEntity<String> captured = action(paymentId, "capture", jwt);
        assertEquals(HttpStatus.OK, captured.getStatusCode());
        assertEquals("CAPTURED", field(captured.getBody(), "status"));

        try {
            JsonNode history = objectMapper.readTree(captured.getBody()).get("statusHistory");
            assertEquals(3, history.size());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void capture_returns422_whenNotAuthorized() {
        UUID source = createFundedAccount("500.00");
        UUID dest = createAccount();
        String jwt = mintJwt(source);

        ResponseEntity<String> created = postPayment(source, dest, "150.00", jwt, UUID.randomUUID().toString());
        UUID paymentId = UUID.fromString(field(created.getBody(), "id"));

        // Capture immediately while still PENDING (before the poller authorizes it).
        // Spring 7 renamed the 422 enum constant, so assert on the numeric status value.
        ResponseEntity<String> resp = action(paymentId, "capture", jwt);
        assertEquals(422, resp.getStatusCode().value());
    }

    @Test
    void cancel_pendingPayment_returns200_noLedgerEntries() {
        UUID source = createFundedAccount("500.00");
        UUID dest = createAccount();
        String jwt = mintJwt(source);

        ResponseEntity<String> created = postPayment(source, dest, "150.00", jwt, UUID.randomUUID().toString());
        UUID paymentId = UUID.fromString(field(created.getBody(), "id"));

        ResponseEntity<String> resp = action(paymentId, "cancel", jwt);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("CANCELLED", field(resp.getBody(), "status"));
        assertEquals(0, ledgerCount(paymentId));
    }

    @Test
    void cancel_authorizedPayment_restoresBalance() {
        UUID source = createFundedAccount("500.00");
        UUID dest = createAccount();
        String jwt = mintJwt(source);

        ResponseEntity<String> created = postPayment(source, dest, "150.00", jwt, UUID.randomUUID().toString());
        UUID paymentId = UUID.fromString(field(created.getBody(), "id"));

        waitForStatus(paymentId, jwt, "AUTHORIZED");
        assertEquals(0, balanceOf(source).compareTo(new BigDecimal("350.00")));

        ResponseEntity<String> resp = action(paymentId, "cancel", jwt);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("CANCELLED", field(resp.getBody(), "status"));

        assertEquals(0, balanceOf(source).compareTo(new BigDecimal("500.00")));   // fully restored
        assertEquals(4, ledgerCount(paymentId));   // 2 authorization + 2 reversal
        assertTrue(true);
    }
}
