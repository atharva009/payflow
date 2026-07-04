package com.payments.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // Processor poller — SKIP LOCKED (-2) prevents two replicas processing the same payment
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT p FROM Payment p WHERE p.status = :status ORDER BY p.createdAt ASC")
    List<Payment> findNextBatchForProcessing(
        @Param("status") PaymentStatus status, Pageable pageable);

    // Expiry poller — SKIP LOCKED prevents race with processor poller
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT p FROM Payment p WHERE p.status = :status AND p.updatedAt < :cutoff")
    List<Payment> findStuckPayments(
        @Param("status") PaymentStatus status, @Param("cutoff") Instant cutoff);

    // Settlement job
    @Query("SELECT p FROM Payment p WHERE p.status = :status AND p.createdAt BETWEEN :start AND :end")
    List<Payment> findByStatusAndCreatedAtBetween(
        @Param("status") PaymentStatus status,
        @Param("start") Instant start,
        @Param("end") Instant end);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    // Additive (Step 5): used by IdempotencyIntegrationTest to assert exactly one
    // payment record exists per source account after a duplicate request.
    long countBySourceAccountId(UUID sourceAccountId);

    // Settlement job (Step 9): bulk-transition all payments in a batch to SETTLED.
    List<Payment> findBySettlementBatchId(UUID settlementBatchId);

    // Reconciliation job (Step 9): the processor's view is payments that have a
    // processor_ref, created within the reconciliation date window.
    List<Payment> findByProcessorRefIsNotNullAndCreatedAtBetween(Instant start, Instant end);

    // Admin (Step 9): paginated listing filtered by status.
    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    // Metrics (Step 10): live count per status for the payments.active gauge.
    long countByStatus(PaymentStatus status);
}
