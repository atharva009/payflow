package com.payments.payment;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class PaymentExpiryPoller {

    private final PaymentRepository paymentRepository;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    public PaymentExpiryPoller(PaymentRepository paymentRepository,
                                PaymentStatusHistoryRepository paymentStatusHistoryRepository) {
        this.paymentRepository = paymentRepository;
        this.paymentStatusHistoryRepository = paymentStatusHistoryRepository;
    }

    @Scheduled(fixedDelay = 60000)
    @SchedulerLock(name = "paymentExpiry", lockAtMostFor = "5m", lockAtLeastFor = "30s")
    @Transactional
    public void expireStuckPayments() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(15));
        List<Payment> stuck = paymentRepository.findStuckPayments(
            PaymentStatus.PENDING, cutoff);
        stuck.forEach(p -> {
            p.fail("PROCESSING_TIMEOUT");
            paymentRepository.save(p);
            PaymentStatusHistory history = PaymentStatusHistory.record(
                p.getId(), PaymentStatus.PENDING, PaymentStatus.FAILED, "PROCESSING_TIMEOUT");
            paymentStatusHistoryRepository.save(history);
        });
    }
}
