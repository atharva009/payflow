package com.payments.processor;

import com.payments.exception.PermanentProcessorException;
import com.payments.exception.TransientProcessorException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class MockProcessorAdapter implements ProcessorAdapter {

    private final double successRate;
    private final double transientFailureRate;
    private final double permanentFailureRate;
    private final double timeoutRate;
    private final int minLatencyMs;
    private final int maxLatencyMs;

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
