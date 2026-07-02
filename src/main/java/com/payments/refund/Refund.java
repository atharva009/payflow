package com.payments.refund;

import com.payments.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseEntity {

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_type", nullable = false, length = 10)
    private RefundType refundType;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "processor_ref", length = 255)
    private String processorRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "completed_at")
    private Instant completedAt;

    public static Refund create(UUID paymentId, BigDecimal amount,
                                 RefundType type, String reason) {
        Refund r = new Refund();
        r.paymentId = paymentId;
        r.amount = amount;
        r.refundType = type;
        r.reason = reason;
        r.status = RefundStatus.PENDING;
        return r;
    }
}
