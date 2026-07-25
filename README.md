# PayFlow

PayFlow is an idempotent payment processing API written with Spring Boot 4 and Java 21. It's a
portfolio project built to show how the hard parts of a real payments system fit together: making
retries safe, keeping an accurate ledger, and dealing with a payment processor that can be slow or
fail.

This is a short overview for now. A fuller architecture write-up and API reference are on the way.

## What it does

- **Idempotent payments.** Send the same request twice and you still get one payment. There's a Redis
  cache in front of Postgres, a distributed lock to handle concurrent retries, and a hash of the
  request body so a reused key with a different payload is caught instead of silently replayed.
- **A clear payment lifecycle.** Payments move through a small state machine (PENDING, AUTHORIZED,
  CAPTURED, SETTLED, plus the terminal CANCELLED, FAILED, and REFUNDED). Illegal transitions are
  rejected rather than quietly allowed.
- **A double-entry ledger.** Every movement of money is a matching debit and credit that never gets
  edited or deleted, so balances always add up and can be reconciled later.
- **Resilient processor calls.** A circuit breaker and retries (Resilience4j) wrap the external
  processor, and the charge is split across two transactions so a crash mid-flight never
  double-charges the customer.
- **Safe under load.** Account balances can't go negative when many payments hit the same account at
  once, thanks to row locking and `SELECT ... FOR UPDATE SKIP LOCKED`.
- **Background workers.** A poller authorizes payments, a sweeper times out stuck ones, and nightly
  jobs handle settlement (with netting done in SQL) and reconciliation. All of them are safe to run on
  more than one instance, using ShedLock.
- **Refunds.** VOID before settlement, REVERSAL after, each writing the compensating ledger entries.
- **Secured and observable.** Stateless JWT auth, RFC 7807 error responses, Micrometer metrics,
  distributed tracing, and structured JSON logs.

## Built with

Java 21, Spring Boot 4, PostgreSQL 17, Redis 8, Flyway, Spring Batch, Resilience4j, ShedLock, and
Testcontainers.

## Running it locally

```bash
docker compose up -d          # starts Postgres and Redis
export JWT_SECRET=test-secret-key-minimum-32-chars-ok
mvn spring-boot:run
```

To run the tests (you'll need Docker for the integration suites):

```bash
mvn verify
```

## Status

Still building this one in public. More detailed docs are coming.
