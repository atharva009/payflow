package com.payments.payment;

import com.payments.ledger.LedgerService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentPoller {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    public PaymentPoller(PaymentRepository paymentRepository,
                         LedgerService ledgerService,
                         PaymentStatusHistoryRepository paymentStatusHistoryRepository) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.paymentStatusHistoryRepository = paymentStatusHistoryRepository;
    }

    // Transaction B boundary. LedgerService.authorizePayment joins via REQUIRED propagation.
    @Transactional
    public void completeAuthorization(Payment payment) {
        ledgerService.authorizePayment(payment);
        paymentRepository.save(payment);
    }
}
