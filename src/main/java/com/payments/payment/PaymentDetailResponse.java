package com.payments.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentDetailResponse(
        UUID id,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String failureReason,
        String processorRef,
        Instant createdAt,
        Instant updatedAt,
        List<StatusHistoryEntry> statusHistory) {

    public record StatusHistoryEntry(PaymentStatus from, PaymentStatus to, Instant at, String reason) {
    }

    public static PaymentDetailResponse from(Payment payment, List<PaymentStatusHistory> history) {
        List<StatusHistoryEntry> entries = history.stream()
                .map(h -> new StatusHistoryEntry(
                        h.getFromStatus(), h.getToStatus(), h.getCreatedAt(), h.getReason()))
                .toList();
        return new PaymentDetailResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getFailureReason(),
                payment.getProcessorRef(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                entries);
    }
}
