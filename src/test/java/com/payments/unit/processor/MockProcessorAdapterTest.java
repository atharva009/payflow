package com.payments.unit.processor;

import com.payments.exception.PermanentProcessorException;
import com.payments.exception.TransientProcessorException;
import com.payments.processor.MockProcessorAdapter;
import com.payments.processor.ProcessorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test — constructs {@link MockProcessorAdapter} directly with controlled rates
 * (the constructor takes primitive config values). No Spring context, so the Resilience4j
 * annotations are not active here. Latency forced to 0 for speed.
 */
@ExtendWith(MockitoExtension.class)
class MockProcessorAdapterTest {

    @Test
    void charge_returnsSuccess_whenSuccessRate100() {
        MockProcessorAdapter adapter =
                new MockProcessorAdapter(1.0, 0.0, 0.0, 0.0, 0, 0);

        for (int i = 0; i < 10; i++) {
            ProcessorResponse response = adapter.charge("ref-1", new BigDecimal("100.00"), "USD");
            assertTrue(response.success());
            assertEquals("ref-1", response.processorRef());
        }
    }

    @Test
    void charge_throwsTransientProcessorException_whenTransientRate100() {
        MockProcessorAdapter adapter =
                new MockProcessorAdapter(0.0, 1.0, 0.0, 0.0, 0, 0);

        assertThrows(TransientProcessorException.class,
                () -> adapter.charge("ref-1", new BigDecimal("100.00"), "USD"));
    }

    @Test
    void charge_throwsPermanentProcessorException_whenPermanentRate100() {
        MockProcessorAdapter adapter =
                new MockProcessorAdapter(0.0, 0.0, 1.0, 0.0, 0, 0);

        assertThrows(PermanentProcessorException.class,
                () -> adapter.charge("ref-1", new BigDecimal("100.00"), "USD"));
    }

    @Test
    void charge_throwsTransientProcessorException_whenTimeoutRate100() {
        MockProcessorAdapter adapter =
                new MockProcessorAdapter(0.0, 0.0, 0.0, 1.0, 0, 0);

        assertThrows(TransientProcessorException.class,
                () -> adapter.charge("ref-1", new BigDecimal("100.00"), "USD"));
    }

    @Test
    void refund_alwaysReturnsSuccess() {
        MockProcessorAdapter adapter =
                new MockProcessorAdapter(0.0, 1.0, 0.0, 0.0, 0, 0);

        ProcessorResponse response = adapter.refund("ref-1", new BigDecimal("50.00"));

        assertTrue(response.success());
    }

    @Test
    void charge_processorRef_matchesInput() {
        MockProcessorAdapter adapter =
                new MockProcessorAdapter(1.0, 0.0, 0.0, 0.0, 0, 0);

        ProcessorResponse response = adapter.charge("ref-xyz", new BigDecimal("100.00"), "USD");

        assertEquals("ref-xyz", response.processorRef());
    }
}
