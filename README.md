# PayFlow

A production-grade, idempotent payment processing API built to demonstrate state-machine-driven payment lifecycle, double-entry ledger, two-phase external processor calls, distributed scheduling, and resilience patterns, all verified by 84 tests across unit and Testcontainers-backed integration suites.

---

## Architecture

```
                         HTTP Client
                              |
                              v
                 [MdcTraceFilter]  (trace id into MDC, before security)
                              |
                              v
                 [IdempotencyFilter] <───────> Redis  (Redlock + response cache, fail-closed)
                              |                        (only POST /api/v1/payments)
                              v
                 [Spring Security / OAuth2 Resource Server, HS256 JWT]
                              |
                              v
   [PaymentController | AccountController | RefundController | AdminController]
                              |
                              v
   [PaymentService | AccountService | LedgerService | RefundService]
                              |
       +----------------------+-------------------------------+
       |                                                      |
       v                                                      v
 [PaymentRepository | AccountRepository | LedgerEntryRepository | ...]   [MockProcessorAdapter]
       |                                                      @CircuitBreaker + @Retry
       v                                                      (Resilience4j 2.4.0)
 PostgreSQL  (Flyway V1..V12)

 ── Background workers (each guarded by ShedLock, DB-time leases) ─────────────

 [PaymentPoller]            @Scheduled(fixedDelay 5s)  + @SchedulerLock
     fetchBatch()           SELECT ... FOR UPDATE SKIP LOCKED   (short tx)
     Transaction A          assignProcessorRef()  REQUIRES_NEW  (commit processor_ref)
     processor call         MockProcessorAdapter.charge()       (OUTSIDE any transaction)
     Transaction B          completeAuthorization()  REQUIRED   (ledger + balance + status)
     on failure             failPayment()  (own transaction)

 [PaymentExpiryPoller]      @Scheduled(fixedDelay 60s)          + @SchedulerLock
 [IdempotencyCleanupJob]    @Scheduled(cron 0 0 3 * * *, UTC)   + @SchedulerLock
 [SettlementJob]            Spring Batch + @Scheduled(0 0 23 * * *, UTC) + @SchedulerLock
 [ReconciliationJob]        Spring Batch + @Scheduled(0 0 1 * * *, UTC)  + @SchedulerLock
```

---

## Payment State Machine

11 valid transitions. Terminal states are `SETTLED`, `CANCELLED`, `FAILED`, `REFUNDED` (marked `(terminal)`).

```
                        PENDING
                       /   |    \
          AUTHORIZED <-+   |     +-> CANCELLED (terminal)
          /  |   \         |
         /   |    \        +-------> FAILED (terminal)
        /    |     \
 CAPTURED  FAILED  CANCELLED (terminal)
   |  \    (terminal)
   |   \
   |    +----------------------------> REFUNDED (terminal)
   v
 SETTLEMENT_QUEUED
   |   \
   |    +-> FAILED (terminal)
   v
 SETTLED ---------------------------> REFUNDED (terminal)
```

The 11 transitions, enumerated (the authoritative source is the matrix and the 79-assertion `PaymentStatusTest`):

```
PENDING            -> AUTHORIZED          (processor authorizes)
PENDING            -> FAILED              (authorization rejected / expiry)
PENDING            -> CANCELLED           (cancelled before processing)
AUTHORIZED         -> CAPTURED            (funds captured)
AUTHORIZED         -> FAILED              (capture failed)
AUTHORIZED         -> CANCELLED           (authorization voided)
CAPTURED           -> SETTLEMENT_QUEUED   (assigned to a settlement batch)
CAPTURED           -> REFUNDED            (VOID refund before settlement)
SETTLEMENT_QUEUED  -> SETTLED             (settlement batch succeeds)
SETTLEMENT_QUEUED  -> FAILED              (settlement batch fails)
SETTLED            -> REFUNDED            (REVERSAL refund after settlement)
```

A state being terminal is independent of whether it has an outgoing transition: `SETTLED` is terminal yet `SETTLED -> REFUNDED` is valid.

---

## Quick Start

### 1. Infrastructure (`docker-compose.yml`)

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: payments
      POSTGRES_USER: payments
      POSTGRES_PASSWORD: payments
    ports:
      - "5432:5432"

  redis:
    image: redis:8-alpine
    ports:
      - "6379:6379"
```

### 2. Run

```bash
docker compose up -d
export JWT_SECRET=test-secret-key-minimum-32-chars-ok
mvn spring-boot:run
# tests (84, requires Docker for the integration suites):
mvn verify
```

### 3. Authentication

PayFlow validates an HS256 JWT via the OAuth2 resource server. The JWT carries a custom `role` claim (mapped to `ROLE_USER` / `ROLE_ADMIN`) and an `accountIds` claim listing the accounts the principal may act on. The dev/test secret is `test-secret-key-minimum-32-chars-ok`.

The test suite mints tokens with `NimbusJwtEncoder`:

```java
var key = new SecretKeySpec("test-secret-key-minimum-32-chars-ok".getBytes(UTF_8), "HmacSHA256");
var encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
var claims = JwtClaimsSet.builder()
        .subject(UUID.randomUUID().toString())
        .claim("accountIds", List.of(sourceAccountId.toString()))
        .claim("role", "USER")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .build();
String jwt = encoder.encode(JwtEncoderParameters.from(
        JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
```

A ready-to-use token for the examples below (subject random, `role=USER`, `accountIds=["a1b2c3d4-1234-5678-abcd-000000000001"]`, expiry year 2100):

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI5ZjFjN2EyZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDBhYmMiLCJhY2NvdW50SWRzIjpbImExYjJjM2Q0LTEyMzQtNTY3OC1hYmNkLTAwMDAwMDAwMDAwMSJdLCJyb2xlIjoiVVNFUiIsImlhdCI6MTczNTY4OTYwMCwiZXhwIjo0MTAyNDQ0ODAwfQ.vS6Ph9ZZ6fWY1_p-gsJPoCVU04GvuOVblb5fdOykH4I
```

### 4. Endpoints

```bash
# 1. Create a payment (202 Accepted, status PENDING)
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI5ZjFjN2EyZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDBhYmMiLCJhY2NvdW50SWRzIjpbImExYjJjM2Q0LTEyMzQtNTY3OC1hYmNkLTAwMDAwMDAwMDAwMSJdLCJyb2xlIjoiVVNFUiIsImlhdCI6MTczNTY4OTYwMCwiZXhwIjo0MTAyNDQ0ODAwfQ.vS6Ph9ZZ6fWY1_p-gsJPoCVU04GvuOVblb5fdOykH4I" \
  -H "Idempotency-Key: f47ac10b-58cc-4372-a567-0e02b2c3d479" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "a1b2c3d4-1234-5678-abcd-000000000001",
    "destAccountId": "a1b2c3d4-1234-5678-abcd-000000000002",
    "amount": "150.00",
    "currency": "USD"
  }'

# 2. Get payment by ID (200 OK, includes statusHistory)
curl http://localhost:8080/api/v1/payments/f47ac10b-58cc-4372-a567-0e02b2c3d479 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI5ZjFjN2EyZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDBhYmMiLCJhY2NvdW50SWRzIjpbImExYjJjM2Q0LTEyMzQtNTY3OC1hYmNkLTAwMDAwMDAwMDAwMSJdLCJyb2xlIjoiVVNFUiIsImlhdCI6MTczNTY4OTYwMCwiZXhwIjo0MTAyNDQ0ODAwfQ.vS6Ph9ZZ6fWY1_p-gsJPoCVU04GvuOVblb5fdOykH4I"

# 3. Capture an AUTHORIZED payment (200 OK, status CAPTURED)
curl -X POST http://localhost:8080/api/v1/payments/f47ac10b-58cc-4372-a567-0e02b2c3d479/capture \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI5ZjFjN2EyZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDBhYmMiLCJhY2NvdW50SWRzIjpbImExYjJjM2Q0LTEyMzQtNTY3OC1hYmNkLTAwMDAwMDAwMDAwMSJdLCJyb2xlIjoiVVNFUiIsImlhdCI6MTczNTY4OTYwMCwiZXhwIjo0MTAyNDQ0ODAwfQ.vS6Ph9ZZ6fWY1_p-gsJPoCVU04GvuOVblb5fdOykH4I"

# 4. Cancel a PENDING or AUTHORIZED payment (200 OK, status CANCELLED)
curl -X POST http://localhost:8080/api/v1/payments/f47ac10b-58cc-4372-a567-0e02b2c3d479/cancel \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI5ZjFjN2EyZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDBhYmMiLCJhY2NvdW50SWRzIjpbImExYjJjM2Q0LTEyMzQtNTY3OC1hYmNkLTAwMDAwMDAwMDAwMSJdLCJyb2xlIjoiVVNFUiIsImlhdCI6MTczNTY4OTYwMCwiZXhwIjo0MTAyNDQ0ODAwfQ.vS6Ph9ZZ6fWY1_p-gsJPoCVU04GvuOVblb5fdOykH4I"

# 5. Refund a CAPTURED (VOID) or SETTLED (REVERSAL) payment (202 Accepted)
curl -X POST http://localhost:8080/api/v1/payments/f47ac10b-58cc-4372-a567-0e02b2c3d479/refund \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI5ZjFjN2EyZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDBhYmMiLCJhY2NvdW50SWRzIjpbImExYjJjM2Q0LTEyMzQtNTY3OC1hYmNkLTAwMDAwMDAwMDAwMSJdLCJyb2xlIjoiVVNFUiIsImlhdCI6MTczNTY4OTYwMCwiZXhwIjo0MTAyNDQ0ODAwfQ.vS6Ph9ZZ6fWY1_p-gsJPoCVU04GvuOVblb5fdOykH4I" \
  -H "Idempotency-Key: c9d3e4f5-6789-0abc-def1-234567890abc" \
  -H "Content-Type: application/json" \
  -d '{"amount": "150.00"}'
```

A newly created payment is `PENDING`. The `PaymentPoller` picks it up within about 5 seconds, calls the processor, and moves it to `AUTHORIZED`, after which capture, cancel, and refund apply.

---

## Key Engineering Decisions

**1. Two-layer idempotency with Redis, fail-closed.** A naive design checks for a duplicate request by reading the database, but a hot retry loop on the payment-creation path would hammer Postgres with redundant point lookups and add the database round-trip to every request's latency. PayFlow puts Redis in front as the fast path (key `idempotency:{key}`, value a serialized response plus request hash) and keeps the database row as the authoritative fallback that repopulates Redis on a cold hit. The system is fail-closed: if Redis is unreachable the request is rejected with `503 CACHE_UNAVAILABLE` rather than falling through to a database-only path. Fail-open was rejected because losing Redis under load is exactly when duplicate suppression matters most; falling through would let a retry storm create duplicate payments at the moment the cache that prevents them is gone. Concurrency across replicas is handled with a Redlock (`lock:idempotency:{key}`): a single plain Redis `SETNX` lock is insufficient because in a multi-replica deployment two instances can each believe they hold the lock during a failover, so the Redlock pattern is used as the coordination primitive. The `requestHash` (SHA-256 of the raw request body) is stored alongside the cached response so that a second request reusing the same key with a different `sourceAccountId`, `amount`, or `currency` is detected and rejected with `409 IDEMPOTENCY_CONFLICT` rather than silently replaying the first response.

**2. Two-transaction processor call pattern.** The external processor call is a network operation that can take up to several seconds, so it cannot run inside a database transaction: holding a connection and any row locks across that call would serialize all concurrent payment processing and exhaust the connection pool. PayFlow splits the work around the call. Transaction A assigns a `processor_ref` and commits it before the call begins. The processor call happens with no transaction open. Transaction B writes the ledger, updates balances, and transitions status. If the JVM crashes after Transaction A commits but before Transaction B, recovery reads the already-committed `processor_ref` and retries the processor with the same reference, so no duplicate charge occurs. If instead the processor were called without Transaction A committing a reference first, every retry would generate a fresh reference and the processor would treat each attempt as a new charge, double-charging the customer. `assignProcessorRef` uses `REQUIRES_NEW` so its commit is independent of any surrounding transaction and is durable before the external call, which is the whole point of separating it.

**3. Pessimistic locking on accounts, optimistic on payments.** Accounts and payments have opposite contention profiles, so they use opposite locking strategies. An account balance is a shared mutable counter that many payments touch concurrently, so `findByIdForUpdate` issues `SELECT ... FOR UPDATE` to serialize debits against a single account. A payment row, by contrast, is owned by exactly one processing thread at a time, so it relies on the `@Version` optimistic lock on `BaseEntity`; conflicts are rare and the loser simply moves on. If accounts used optimistic locking instead, two concurrent debits could both read the same starting balance, both pass their checks, and both commit, driving the balance negative, which is precisely the double-spend the `ConcurrentPaymentIntegrationTest` guards against. On top of the optimistic lock, the poller query uses `SELECT ... FOR UPDATE SKIP LOCKED` (JPA lock timeout `-2`) so that a replica encountering a payment row already locked by another poller skips it instead of blocking, which keeps batch throughput high without ever processing the same payment twice.

**4. Double-entry ledger over balance-column updates.** Updating `accounts.balance` in place is insufficient for a payment system because it destroys history: a single mutable number cannot answer "what was this balance last Tuesday" or "which payment moved these funds," and it offers no surface for reconciliation against an external processor. PayFlow writes an immutable double-entry ledger instead. Every authorization writes exactly two entries, a DEBIT against the source and a CREDIT against the destination, so the books always balance, and a cancellation or refund writes two equal and opposite compensating entries rather than mutating the originals. Each entry also stores `balance_after`, the account balance immediately after that entry was applied, which lets the current or any historical balance be reconstructed by reading the latest relevant entry rather than replaying the entire history from zero.

**5. ShedLock over a PROCESSING intermediate state.** One way to stop two pollers from grabbing the same payment is to add a `PROCESSING` status between `PENDING` and `AUTHORIZED`, set as a lease in Transaction A. That was rejected because it pollutes the domain state machine with an operational concern, adds another transition to reason about, and leaves orphaned `PROCESSING` rows when a poller dies mid-lease. PayFlow uses ShedLock instead: `@SchedulerLock` ensures only one replica runs a given scheduled method at a time, recorded in a `shedlock` table. The two mechanisms are complementary rather than redundant: ShedLock prevents two replicas from running the poller simultaneously, while `SKIP LOCKED` handles the row-level race within a single poller's batch and the brief windows around lease boundaries. The lock provider is configured with `.usingDbTime()` so lease expiry is measured by the database clock rather than each node's clock; without it, node clock skew could let a node consider a lease expired while another still holds it, defeating the lock.

**6. processor_ref committed in Transaction A before the external call.** Committing the reference before the call gives an at-least-once delivery guarantee with safe retries. The `processor_ref` is the processor's idempotency key, which is distinct from the user-supplied `Idempotency-Key` that deduplicates inbound HTTP requests: the former deduplicates the outbound charge at the processor, the latter deduplicates the inbound API call. Because the same `processor_ref` is reused on every retry, a processor that has already seen it responds with a duplicate indicator, and PayFlow treats that `409`-style duplicate as a SUCCESS and extracts the existing reference rather than marking the payment FAILED, since the processor did in fact charge. Generating a fresh reference per retry was rejected for the obvious reason: it converts a safe retry into a second real charge.

**7. Netting computed in SQL, not Java memory.** Netting reduces a batch of gross obligations between counterparties into a single net amount per pair, which collapses many small settlements into one and is the standard way to cut settlement volume and cost. PayFlow computes it in the database with a self-join: `LEAST`/`GREATEST` on the two account ids canonicalize each counterparty pair regardless of direction, and a `LEFT JOIN` of the entries against their reverse-direction counterparts lets `SUM(a) - SUM(b)` produce the bilateral net in one query. Doing this in Java would require loading every settlement entry for the batch into memory and grouping them there, which does not scale and risks out-of-memory failures as batches grow. Pushing the aggregation into the database is the correct architecture even though the resulting SQL is harder to read, because the database is built to aggregate large sets without materializing them in the application heap.

**8. AUTHORIZED to CANCELLED requires full ledger reversal; PENDING to CANCELLED does not.** The correct cancellation behavior depends on whether money has moved. A `PENDING` payment has no ledger entries because authorization has not run, so cancelling it is a pure status change with no balance impact. An `AUTHORIZED` payment already has a DEBIT and a CREDIT and has reduced the source balance, so cancelling it must release those held funds with two equal and opposite compensating entries (a CREDIT back to the source and a DEBIT against the destination). If the reversal were skipped for `AUTHORIZED -> CANCELLED`, the source account would stay debited forever and the destination would keep funds it never legitimately received, so the books would be permanently wrong by the payment amount. The `cancelPayment` method captures `previousStatus` before the transition and uses it to decide: only when the previous status was `AUTHORIZED` does it invoke `ledgerService.reverseAuthorization`.

---

## Known Deviations From Initial Spec

**1. `spring-boot-starter-aop` renamed to `spring-boot-starter-aspectj`.** The spec's `pom.xml` declared `spring-boot-starter-aop`, and a fixed rule mandated its presence alongside every Resilience4j usage. In Spring Boot 4.0.7 this artifact was renamed to `spring-boot-starter-aspectj` and is no longer managed by the BOM under the old name, so `mvn dependency:resolve` fails with "version is missing." The resolution was to replace the artifact id with `spring-boot-starter-aspectj`, which is BOM-managed and functionally identical: it provides the same Spring AOP plus AspectJ weaving that the Resilience4j annotations require.

**2. The state machine has 11 transitions, not 12.** The spec prose stated "12 valid transitions" in several places, but counting the embedded transition matrix yields exactly 11 rows, and the 79-assertion `PaymentStatusTest` also closes on 11 (its 28 parameterized non-terminal cases are consistent with 11 transitions across the 4 non-terminal source states, not 12). The root cause was a documentation error in the prose; the matrix and the test are authoritative. The implementation encodes exactly 11 transitions.

**3. `@TimeLimiter` removed from `charge()`.** The spec locked `@TimeLimiter(name = "processor")` onto the synchronous `charge()` method and asserted it "has no effect at the annotation level" on synchronous methods. In Resilience4j 2.4.0 that claim is factually wrong: `TimeLimiterAspect` throws `IllegalReturnTypeException` ("CompletionStage expected") on every invocation when the return type is not a `CompletionStage`. The effect was that every payment failed through the poller's catch block with `PROCESSOR_UNAVAILABLE`. The resolution was to remove `@TimeLimiter` from `charge()` while keeping `@CircuitBreaker` and `@Retry`, both of which work correctly on synchronous methods. The `resilience4j.timeout.instances.processor.timeout-duration: 5s` configuration was retained in `application.yml` so it applies correctly when the processor call becomes asynchronous in a later phase.

---

## Load Test Results

> Run with JMeter. Fill in after executing the load test suite. The values below are an illustrative example row, not measured data.

| Scenario | Threads | Duration | Throughput (req/s) | P50 (ms) | P95 (ms) | P99 (ms) | Error % |
|---|---|---|---|---|---|---|---|
| POST /payments baseline | 1 | 60s | _example: 220_ | _example: 4_ | _example: 11_ | _example: 19_ | _example: 0.0_ |
| POST /payments 50 concurrent | 50 | 300s | | | | | |
| POST /payments idempotent replay | 50 | 300s | | | | | |
| GET /payments/{id} read heavy | 100 | 300s | | | | | |

---

## Tech Stack

| Component | Choice | Version |
|---|---|---|
| Language | Java | 21 |
| Framework | Spring Boot | 4.0.7 |
| Database | PostgreSQL | 17 |
| Cache / Lock | Redis + Redisson | 8 / 4.6.0 |
| Migrations | Flyway | 11 (via spring-boot-starter-flyway) |
| Batch | Spring Batch | 6 (via spring-boot-starter-batch) |
| Resilience | Resilience4j | 2.4.0 |
| Distributed lock | ShedLock | 7.7.0 |
| Security | Spring OAuth2 Resource Server | HS256 JWT |
| API docs | SpringDoc OpenAPI | 3.0.3 |
| Observability | Micrometer + OTel bridge | Boot-managed |
| Test containers | Testcontainers | 2.0 |
| Build | Maven | Boot parent 4.0.7 |

---

## Production Considerations

**Processor integration.** `MockProcessorAdapter` simulates configurable success, transient, permanent, and timeout outcomes. A real integration replaces it with one of two shapes. The synchronous shape is a real HTTP client (a pooled `RestClient` or WebClient with bounded connection pool, sensible connect/read timeouts, and mTLS to the processor) behind the existing `@CircuitBreaker` and `@Retry`. The asynchronous shape, which the retained timeout configuration anticipates, makes the charge a Kafka request/response so the processor call no longer blocks a poller thread, at which point `@TimeLimiter` becomes meaningful again because the method returns a `CompletionStage`.

**JWT trust model.** HS256 with a single shared secret is fine for a single service in development but does not scale: every service that needs to validate a token would need the signing secret, which means every service could also mint tokens. Production uses asymmetric signing (RS256 or ES256) where an identity provider holds the private key and PayFlow fetches public keys from a JWKS endpoint, with key rotation handled by the `kid` header so keys can be rolled without redeploying validators.

**Redis topology.** The Redlock guarantee only holds against independent failure domains, which means at least three independent Redis nodes (or a Redis cluster with the appropriate topology). The current `docker-compose` runs a single Redis node, so the lock is a single point of failure and does not provide the multi-node Redlock safety property; that is acceptable for local development but must be a multi-node deployment in production.

**Connection pool sizing.** `spring.datasource.hikari.maximum-pool-size: 10` is a development default. Production sizing should follow a measured formula in the neighborhood of `connections = (core_count * 2) + effective_spindle_count` for the database host, then be validated under load, because an oversized pool starves the database with context switching while an undersized pool serializes request handlers.

**Poller transaction structure.** The poller fetches its batch in a short transaction and then processes each payment through a `@Lazy` self-reference so that `assignProcessorRef`, `completeAuthorization`, and `failPayment` each get their own transaction boundary. This works and is covered by the concurrency test, but self-injection is a code smell; the production-grade replacement extracts a separate `PaymentPollerHelper` Spring bean to hold the transactional methods and injects it normally, eliminating self-injection entirely.

**Settlement netting scope.** The netting implementation is bilateral only: it nets obligations between two counterparties at a time. Multilateral netting across three or more counterparties is a different algorithm (a clearing/optimization problem rather than a self-join) and was explicitly out of scope; a production clearing system would need that to minimize settlement movements across a network of parties.

---

## What I Would Do Differently

**1. Remove self-invocation from the poller.** `PaymentPoller` uses a `@Lazy` self-reference to obtain independent `@Transactional` boundaries on `assignProcessorRef`, `completeAuthorization`, and `failPayment`. It is correct and tested, but self-injection to cross the proxy boundary is a recognized Spring anti-pattern. A cleaner design extracts those three methods into a `PaymentPollerTransactionHelper` bean injected normally, which removes the `@Lazy` cycle and makes each transaction boundary unit-testable in isolation.

**2. Stronger integration-test isolation.** The integration suites share one Testcontainers instance per class via static `@Container` fields, which is fast but allows leftover database state to leak between methods, so several tests compensate with `@BeforeEach TRUNCATE ... CASCADE`. A test-data-builder pattern with explicit per-test fixtures and teardown would make the tests more readable and remove the dependency on truncation as a reset mechanism.

**3. Stop the mock processor from reading the application database.** `MockProcessorAdapter.getProcessedPayments` queries `PaymentRepository` to simulate the processor's view during reconciliation, which creates a dependency from the processor layer into the application's own repository, a layering violation. Since the processor is an external system in production, the mock should maintain its own in-memory record of what it "processed" and answer reconciliation from that, rather than querying the database the application owns.
