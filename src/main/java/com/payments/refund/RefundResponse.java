package com.payments.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID refundId,
        UUID paymentId,
        RefundStatus status,
        RefundType refundType,
        BigDecimal amount,
        Instant createdAt) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getStatus(),
                refund.getRefundType(),
                refund.getAmount(),
                refund.getCreatedAt());
    }
}
