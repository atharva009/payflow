package com.payments.payment;

import com.payments.account.AccountService;
import com.payments.exception.ForbiddenException;
import com.payments.exception.PaymentNotFoundException;
import com.payments.idempotency.CachedResponse;
import com.payments.idempotency.IdempotencyService;
import com.payments.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    private final AccountService accountService;
    private final IdempotencyService idempotencyService;
    private final LedgerService ledgerService;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentStatusHistoryRepository paymentStatusHistoryRepository,
                          AccountService accountService,
                          IdempotencyService idempotencyService,
                          LedgerService ledgerService) {
        this.paymentRepository = paymentRepository;
        this.paymentStatusHistoryRepository = paymentStatusHistoryRepository;
        this.accountService = accountService;
        this.idempotencyService = idempotencyService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public Payment createPayment(PaymentRequest request,
                                 String idempotencyKey,
                                 List<UUID> authorizedAccountIds) {

        // Guard only (double-check inside the lock the filter holds). The filter owns the
        // HTTP replay, so on a genuine duplicate the service is never reached; if it is
        // (race), do nothing — there is no new payment to create.
        Optional<CachedResponse> cached = idempotencyService.findCachedResponse(idempotencyKey);
        if (cached.isPresent()) {
            return null;
        }

        // Validate both accounts exist and are in a usable state (throws on invalid).
        accountService.validateForPayment(request.sourceAccountId(), request.destAccountId());

        // Ownership: the source account must belong to the authenticated principal.
        if (!authorizedAccountIds.contains(request.sourceAccountId())) {
            throw new ForbiddenException(
                    "Source account not owned by principal: " + request.sourceAccountId());
        }

        Payment payment = Payment.create(
                idempotencyKey,
                request.sourceAccountId(),
                request.destAccountId(),
                request.amount(),
                request.currency());
        Payment saved = paymentRepository.save(payment);

        // Initial history row: from=null, to=PENDING, reason=null.
        paymentStatusHistoryRepository.save(
                PaymentStatusHistory.record(saved.getId(), null, PaymentStatus.PENDING, null));

        return saved;
    }

    @Transactional
    public Payment capturePayment(UUID paymentId, List<UUID> authorizedAccountIds) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (!authorizedAccountIds.contains(payment.getSourceAccountId())) {
            throw new ForbiddenException("Account does not belong to this principal");
        }

        PaymentStatus previousStatus = payment.getStatus();
        payment.transitionTo(PaymentStatus.CAPTURED);  // throws if not AUTHORIZED
        paymentRepository.save(payment);

        paymentStatusHistoryRepository.save(PaymentStatusHistory.record(
                payment.getId(), previousStatus, PaymentStatus.CAPTURED, null));
        return payment;
    }

    @Transactional
    public Payment cancelPayment(UUID paymentId, List<UUID> authorizedAccountIds) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (!authorizedAccountIds.contains(payment.getSourceAccountId())) {
            throw new ForbiddenException("Account does not belong to this principal");
        }

        PaymentStatus previousStatus = payment.getStatus();
        payment.transitionTo(PaymentStatus.CANCELLED);

        if (previousStatus == PaymentStatus.AUTHORIZED) {
            ledgerService.reverseAuthorization(payment);
        }
        // PENDING -> CANCELLED: no ledger action needed

        paymentRepository.save(payment);
        String reason = previousStatus == PaymentStatus.AUTHORIZED
                ? "Customer cancelled after authorization"
                : "Customer cancelled before processing";
        paymentStatusHistoryRepository.save(PaymentStatusHistory.record(
                payment.getId(), previousStatus, PaymentStatus.CANCELLED, reason));
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    @Transactional(readOnly = true)
    public List<PaymentStatusHistory> getStatusHistory(UUID paymentId) {
        return paymentStatusHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }
}
