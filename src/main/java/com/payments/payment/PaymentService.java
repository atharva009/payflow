package com.payments.payment;

import com.payments.account.AccountService;
import com.payments.exception.ForbiddenException;
import com.payments.idempotency.CachedResponse;
import com.payments.idempotency.IdempotencyService;
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

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentStatusHistoryRepository paymentStatusHistoryRepository,
                          AccountService accountService,
                          IdempotencyService idempotencyService) {
        this.paymentRepository = paymentRepository;
        this.paymentStatusHistoryRepository = paymentStatusHistoryRepository;
        this.accountService = accountService;
        this.idempotencyService = idempotencyService;
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
}
