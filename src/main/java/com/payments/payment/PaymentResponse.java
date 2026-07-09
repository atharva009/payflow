package com.payments.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        Instant createdAt) {
}
