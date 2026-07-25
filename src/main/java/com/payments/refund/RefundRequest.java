package com.payments.refund;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record RefundRequest(
        @NotNull BigDecimal amount,
        String reason) {
}
