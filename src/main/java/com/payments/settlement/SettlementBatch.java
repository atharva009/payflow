package com.payments.settlement;

import com.payments.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "settlement_batches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementBatch extends BaseEntity {

    @Column(name = "settlement_date", nullable = false, unique = true)
    private LocalDate settlementDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SettlementBatchStatus status;

    @Column(name = "payment_count", nullable = false)
    private Integer paymentCount;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "processor_ref", length = 255)
    private String processorRef;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    public static SettlementBatch create(LocalDate settlementDate) {
        SettlementBatch b = new SettlementBatch();
        b.settlementDate = settlementDate;
        b.status = SettlementBatchStatus.PENDING;
        b.paymentCount = 0;
        b.grossAmount = BigDecimal.ZERO;
        b.netAmount = BigDecimal.ZERO;
        return b;
    }

    // Domain mutators (Step 9) — the immutable-by-default entity pattern requires
    // mutation through explicit domain methods only.

    public void markCalculating() {
        this.status = SettlementBatchStatus.CALCULATING;
    }

    public void recordTotals(int paymentCount, BigDecimal grossAmount, BigDecimal netAmount) {
        this.paymentCount = paymentCount;
        this.grossAmount = grossAmount;
        this.netAmount = netAmount;
    }

    public void markSettled(Instant settledAt) {
        this.status = SettlementBatchStatus.SETTLED;
        this.settledAt = settledAt;
    }

    public void markFailed() {
        this.status = SettlementBatchStatus.FAILED;
    }

    public void resetForRetry() {
        this.status = SettlementBatchStatus.PENDING;
    }
}
