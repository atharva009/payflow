package com.payments.processor;

import java.math.BigDecimal;

public interface ProcessorAdapter {
    ProcessorResponse charge(String processorRef, BigDecimal amount, String currency);

    ProcessorResponse refund(String processorRef, BigDecimal amount);
}
