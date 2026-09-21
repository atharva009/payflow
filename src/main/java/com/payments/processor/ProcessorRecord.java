package com.payments.processor;

import java.math.BigDecimal;

public record ProcessorRecord(String processorRef, BigDecimal amount, String status) {
}
