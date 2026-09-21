package com.payments.processor;

import com.payments.exception.PermanentProcessorException;
import com.payments.exception.TransientProcessorException;
import com.payments.payment.PaymentRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class MockProcessorAdapter implements ProcessorAdapter {

    private final double successRate;
    private final double transientFailureRate;
    private final double permanentFailureRate;
    private final double timeoutRate;
    private final int minLatencyMs;
    private final int maxLatencyMs;

    // Field-injected (not constructor) so the 6-arg constructor used by the
    // direct-construction unit test (MockProcessorAdapterTest) stays intact.
    // Only getProcessedPayments uses it; charge/refund do not.
    @Autowired(required = false)
    private PaymentRepository paymentRepository;

    public MockProcessorAdapter(
            @Value("${mock-processor.success-rate}") double successRate,
            @Value("${mock-processor.transient-failure-rate}") double transientFailureRate,
            @Value("${mock-processor.permanent-failure-rate}") double permanentFailureRate,
            @Value("${mock-processor.timeout-rate}") double timeoutRate,
            @Value("${mock-processor.min-latency-ms}") int minLatencyMs,
            @Value("${mock-processor.max-latency-ms}") int maxLatencyMs) {
        this.successRate = successRate;
        this.transientFailureRate = transientFailureRate;
        this.permanentFailureRate = permanentFailureRate;
        this.timeoutRate = timeoutRate;
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    @Override
    @CircuitBreaker(name = "processor")
    @Retry(name = "processor")
    // @TimeLimiter removed (authorised): the Resilience4j aspect hard-throws
    // IllegalReturnTypeException on a synchronous (non-CompletionStage) return type.
    // The resilience4j.timeout.* config in application.yml is retained for Phase 2 (async).
    public ProcessorResponse charge(String processorRef, BigDecimal amount, String currency) {
        simulateLatency();
        double roll = Math.random();
        if (roll < timeoutRate) {
            throw new TransientProcessorException("Simulated timeout");
        }
        if (roll < timeoutRate + transientFailureRate) {
            throw new TransientProcessorException("Simulated transient failure");
        }
        if (roll < timeoutRate + transientFailureRate + permanentFailureRate) {
            throw new PermanentProcessorException("Simulated permanent decline");
        }
        return new ProcessorResponse(true, processorRef, null);
    }

    @Override
    public ProcessorResponse refund(String processorRef, BigDecimal amount) {
        simulateLatency();
        return new ProcessorResponse(true, processorRef, null);
    }

    @Override
    public List<ProcessorRecord> getProcessedPayments(LocalDate date) {
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        // The processor's view: every payment it was handed a processor_ref for on this date.
        return paymentRepository.findByProcessorRefIsNotNullAndCreatedAtBetween(start, end).stream()
                .map(p -> new ProcessorRecord(p.getProcessorRef(), p.getAmount(), p.getStatus().name()))
                .toList();
    }

    private void simulateLatency() {
        try {
            int latency = minLatencyMs +
                (int) (Math.random() * (maxLatencyMs - minLatencyMs));
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
