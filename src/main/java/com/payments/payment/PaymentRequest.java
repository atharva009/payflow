package com.payments.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destAccountId,
        @NotNull @Positive BigDecimal amount,
        @NotNull String currency) {
}
