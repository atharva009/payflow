package com.payments.refund;

import com.payments.exception.IdempotencyConflictException;
import com.payments.exception.PaymentNotFoundException;
import com.payments.exception.PaymentNotRefundableException;
import com.payments.exception.RefundAmountExceedsOriginalException;
import com.payments.ledger.LedgerService;
import com.payments.payment.Payment;
import com.payments.payment.PaymentRepository;
import com.payments.payment.PaymentStatus;
import com.payments.payment.PaymentStatusHistory;
import com.payments.payment.PaymentStatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class RefundService {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    private final LedgerService ledgerService;

    public RefundService(RefundRepository refundRepository,
                         PaymentRepository paymentRepository,
                         PaymentStatusHistoryRepository paymentStatusHistoryRepository,
                         LedgerService ledgerService) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paymentStatusHistoryRepository = paymentStatusHistoryRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public Refund refund(UUID paymentId, BigDecimal amount, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // 1. Only one refund per payment — checked first so a repeat refund attempt
        //    reports IDEMPOTENCY_CONFLICT (409) rather than the post-refund state being
        //    misreported as not-refundable.
        if (refundRepository.findByPaymentId(paymentId).isPresent()) {
            throw new IdempotencyConflictException(
                    "Payment " + paymentId + " has already been refunded");
        }

        // 2. Determine refund type from payment state
        PaymentStatus fromStatus = payment.getStatus();
        RefundType refundType = switch (fromStatus) {
            case CAPTURED -> RefundType.VOID;
            case SETTLED -> RefundType.REVERSAL;
            default -> throw new PaymentNotRefundableException(
                    "Payment " + paymentId + " is not refundable in state " + fromStatus);
        };

        // 3. Validate amount: positive and not exceeding the original
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0
                || amount.compareTo(payment.getAmount()) > 0) {
            throw new RefundAmountExceedsOriginalException(
                    "Refund amount " + amount + " is invalid for payment amount " + payment.getAmount());
        }

        // 2-3. Create and persist the refund
        Refund refund = Refund.create(paymentId, amount, refundType, reason);
        refundRepository.save(refund);

        // 4-5. Transition payment to REFUNDED
        payment.transitionTo(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        // 6. Write the compensating ledger entries
        ledgerService.writeRefundEntries(payment, refundType);

        // 7. Status history
        paymentStatusHistoryRepository.save(PaymentStatusHistory.record(
                payment.getId(), fromStatus, PaymentStatus.REFUNDED, reason));

        return refund;
    }
}
