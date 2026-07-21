package com.payments.payment;

import com.payments.exception.PermanentProcessorException;
import com.payments.ledger.LedgerService;
import com.payments.processor.ProcessorAdapter;
import com.payments.processor.ProcessorResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class PaymentPoller {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    private final ProcessorAdapter processorAdapter;
    private final MeterRegistry meterRegistry;
    private final PaymentPoller self;   // proxied self-reference so per-payment @Transactional applies

    public PaymentPoller(PaymentRepository paymentRepository,
                         LedgerService ledgerService,
                         PaymentStatusHistoryRepository paymentStatusHistoryRepository,
                         ProcessorAdapter processorAdapter,
                         MeterRegistry meterRegistry,
                         @Lazy PaymentPoller self) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.paymentStatusHistoryRepository = paymentStatusHistoryRepository;
        this.processorAdapter = processorAdapter;
        this.meterRegistry = meterRegistry;
        this.self = self;
    }

    // The poll itself is NOT transactional: each payment is processed in its own
    // transaction(s) so one payment's failure cannot roll back the whole batch, and the
    // processor call is never made while holding the batch's row locks.
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "paymentPoller", lockAtMostFor = "30m", lockAtLeastFor = "5s")
    public void pollPendingPayments() {
        List<Payment> batch = self.fetchBatch();
        batch.forEach(this::processPayment);
    }

    // SELECT ... FOR UPDATE SKIP LOCKED requires an active transaction — kept short so the
    // payment-row locks release immediately after the fetch (ShedLock serialises pollers).
    @Transactional
    public List<Payment> fetchBatch() {
        return paymentRepository.findNextBatchForProcessing(
                PaymentStatus.PENDING, PageRequest.of(0, 20));
    }

    private void processPayment(Payment payment) {
        // Transaction A — write processor_ref and commit (returns the version-bumped entity)
        Payment refreshed = self.assignProcessorRef(payment);
        String processorRef = refreshed.getProcessorRef();

        try {
            // Outside any transaction — call processor (timed)
            chargeWithTimer(processorRef, refreshed.getAmount(), refreshed.getCurrency());

            // Transaction B — authorize: ledger + balance + status
            self.completeAuthorization(refreshed);

        } catch (PermanentProcessorException e) {
            self.failPayment(refreshed, e.getMessage());
        } catch (Exception e) {
            // Transient failure after all retries exhausted, circuit open, or authorization rejected
            self.failPayment(refreshed, "PROCESSOR_UNAVAILABLE");
        }
    }

    private ProcessorResponse chargeWithTimer(String processorRef, BigDecimal amount, String currency) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            ProcessorResponse response = processorAdapter.charge(processorRef, amount, currency);
            sample.stop(meterRegistry.timer("processor.charge.duration", "outcome", "success"));
            return response;
        } catch (RuntimeException e) {
            sample.stop(meterRegistry.timer("processor.charge.duration", "outcome", "failure"));
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment assignProcessorRef(Payment payment) {
        payment.assignProcessorRef(UUID.randomUUID().toString());
        return paymentRepository.save(payment);
    }

    // Transaction B boundary. LedgerService.authorizePayment joins via REQUIRED propagation.
    @Transactional
    public void completeAuthorization(Payment payment) {
        ledgerService.authorizePayment(payment);
        paymentRepository.save(payment);
    }

    @Transactional
    public void failPayment(Payment payment, String reason) {
        payment.fail(reason);
        paymentRepository.save(payment);
        paymentStatusHistoryRepository.save(PaymentStatusHistory.record(
                payment.getId(), PaymentStatus.PENDING, PaymentStatus.FAILED, reason));
    }
}
