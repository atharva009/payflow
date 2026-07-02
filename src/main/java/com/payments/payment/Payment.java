package com.payments.payment;

import com.payments.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "dest_account_id")
    private UUID destAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "processor_ref", length = 255)
    private String processorRef;

    @Column(name = "settlement_batch_id")
    private UUID settlementBatchId;

    public static Payment create(String idempotencyKey, UUID sourceAccountId,
                                  UUID destAccountId, BigDecimal amount, String currency) {
        Payment p = new Payment();
        p.idempotencyKey = idempotencyKey;
        p.sourceAccountId = sourceAccountId;
        p.destAccountId = destAccountId;
        p.amount = amount;
        p.currency = currency;
        p.status = PaymentStatus.PENDING;
        return p;
    }

    public void transitionTo(PaymentStatus target) {
        this.status = this.status.transitionTo(target);
    }

    public void assignProcessorRef(String processorRef) {
        this.processorRef = processorRef;
    }

    public void fail(String reason) {
        this.transitionTo(PaymentStatus.FAILED);
        this.failureReason = reason;
    }

    public void assignToSettlementBatch(UUID batchId) {
        this.settlementBatchId = batchId;
        this.transitionTo(PaymentStatus.SETTLEMENT_QUEUED);
    }
}
