package com.payments.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.payments.idempotency.IdempotencyKey;
import com.payments.idempotency.IdempotencyKeyRepository;
import com.payments.payment.PaymentRepository;
import com.payments.payment.PaymentStatusHistoryRepository;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class IdempotencyIntegrationTest {

    private static final String TEST_SECRET = "test-secret-key-minimum-32-chars-ok";

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:8-alpine")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379).toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------- helpers ----------

    /** RestTemplate that never throws on 4xx/5xx, so we can assert on status codes directly. */
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
        SecretKeySpec secretKey = new SecretKeySpec(
                TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder =
                new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .claim("accountIds", List.of(accountId.toString()))
                .claim("role", "USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private HttpHeaders authHeaders(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt);
        return headers;
    }

    private UUID createAccount(String jwt) {
        String body = "{\"ownerId\":\"" + UUID.randomUUID() + "\",\"currency\":\"USD\"}";
        ResponseEntity<String> resp = rest().exchange(
                url("/api/v1/accounts"), HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(jwt)), String.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return UUID.fromString(readField(resp.getBody(), "id"));
    }

    private String paymentBody(UUID source, UUID dest, String amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destAccountId\":\"" + dest
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\"}";
    }

    private ResponseEntity<String> postPayment(String body, String jwt, String idempotencyKey) {
        HttpHeaders headers = authHeaders(jwt);
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        return rest().exchange(
                url("/api/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private String readField(String json, String field) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.get(field).asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read field " + field + " from " + json, e);
        }
    }

    // ---------- test cases ----------

    @Test
    void duplicateRequest_returnsSameResponse_singlePaymentInDb() {
        String setupJwt = mintJwt(UUID.randomUUID());
        UUID sourceId = createAccount(setupJwt);
        UUID destId = createAccount(setupJwt);

        String payJwt = mintJwt(sourceId);
        String body = paymentBody(sourceId, destId, "150.00");
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = postPayment(body, payJwt, key);
        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());

        ResponseEntity<String> second = postPayment(body, payJwt, key);
        assertEquals(HttpStatus.ACCEPTED, second.getStatusCode());

        // Identical bodies on replay.
        assertEquals(first.getBody(), second.getBody());
        assertEquals(readField(first.getBody(), "id"), readField(second.getBody(), "id"));
        assertEquals(readField(first.getBody(), "status"), readField(second.getBody(), "status"));
        assertEquals(readField(first.getBody(), "amount"), readField(second.getBody(), "amount"));

        // Exactly one payment record and exactly one status-history row.
        assertEquals(1, paymentRepository.countBySourceAccountId(sourceId));
        UUID paymentId = UUID.fromString(readField(first.getBody(), "id"));
        assertEquals(1, paymentStatusHistoryRepository
                .findByPaymentIdOrderByCreatedAtAsc(paymentId).size());
    }

    @Test
    void differentPayload_sameKey_returns409() {
        String setupJwt = mintJwt(UUID.randomUUID());
        UUID sourceId = createAccount(setupJwt);
        UUID destId = createAccount(setupJwt);

        String payJwt = mintJwt(sourceId);
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first =
                postPayment(paymentBody(sourceId, destId, "150.00"), payJwt, key);
        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());

        ResponseEntity<String> second =
                postPayment(paymentBody(sourceId, destId, "999.00"), payJwt, key);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
    }

    @Test
    void missingIdempotencyKey_returns400() {
        String setupJwt = mintJwt(UUID.randomUUID());
        UUID sourceId = createAccount(setupJwt);
        UUID destId = createAccount(setupJwt);

        String payJwt = mintJwt(sourceId);
        ResponseEntity<String> resp =
                postPayment(paymentBody(sourceId, destId, "150.00"), payJwt, null);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void invalidIdempotencyKey_returns400() {
        String setupJwt = mintJwt(UUID.randomUUID());
        UUID sourceId = createAccount(setupJwt);
        UUID destId = createAccount(setupJwt);

        String payJwt = mintJwt(sourceId);
        ResponseEntity<String> resp =
                postPayment(paymentBody(sourceId, destId, "150.00"), payJwt, "not-a-uuid");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void expiredKey_treatedAsNewRequest() {
        String setupJwt = mintJwt(UUID.randomUUID());
        UUID sourceId = createAccount(setupJwt);
        UUID destId = createAccount(setupJwt);
        String payJwt = mintJwt(sourceId);

        // Create a real payment first so we have a valid payment_id for the FK.
        ResponseEntity<String> seedResp =
                postPayment(paymentBody(sourceId, destId, "150.00"), payJwt, UUID.randomUUID().toString());
        assertEquals(HttpStatus.ACCEPTED, seedResp.getStatusCode());
        UUID seedPaymentId = UUID.fromString(readField(seedResp.getBody(), "id"));

        // Seed an idempotency row whose TTL is already in the past, with no Redis entry.
        String expiredKey = UUID.randomUUID().toString();
        idempotencyKeyRepository.save(IdempotencyKey.create(
                expiredKey, seedPaymentId, 202, "{}",
                Instant.now().minus(Duration.ofHours(1))));

        // A request using the expired key must be treated as new → 202.
        ResponseEntity<String> resp =
                postPayment(paymentBody(sourceId, destId, "150.00"), payJwt, expiredKey);
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
    }
}
