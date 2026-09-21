package com.payments.processor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ProcessorAdapter {
    ProcessorResponse charge(String processorRef, BigDecimal amount, String currency);

    ProcessorResponse refund(String processorRef, BigDecimal amount);

    // Reconciliation (Step 9): the processor's record of what it processed on a date.
    List<ProcessorRecord> getProcessedPayments(LocalDate date);
}
