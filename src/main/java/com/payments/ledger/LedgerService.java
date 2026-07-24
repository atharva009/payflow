package com.payments.ledger;

import com.payments.account.Account;
import com.payments.account.AccountRepository;
import com.payments.exception.AccountNotFoundException;
import com.payments.payment.Payment;
import com.payments.payment.PaymentStatus;
import com.payments.payment.PaymentStatusHistory;
import com.payments.payment.PaymentStatusHistoryRepository;
import com.payments.refund.RefundType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    private final AccountRepository accountRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository,
                         PaymentStatusHistoryRepository paymentStatusHistoryRepository,
                         AccountRepository accountRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.paymentStatusHistoryRepository = paymentStatusHistoryRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Transaction B. Joins the caller's transaction via REQUIRED propagation so that the
     * status update, double-entry ledger writes, balance updates, and history row are one
     * atomic unit — the "never partially apply a payment" invariant.
     */
    @Transactional
    public void authorizePayment(Payment payment) {
        // 1. Pessimistic lock on both accounts
        Account source = accountRepository.findByIdForUpdate(payment.getSourceAccountId())
            .orElseThrow(() -> new AccountNotFoundException(payment.getSourceAccountId()));
        Account dest = accountRepository.findByIdForUpdate(payment.getDestAccountId())
            .orElseThrow(() -> new AccountNotFoundException(payment.getDestAccountId()));

        // 2. Debit source — throws InsufficientFundsException if balance < amount
        source.debit(payment.getAmount());

        // 3. Credit destination
        dest.credit(payment.getAmount());

        // 4. Write ledger entries
        LedgerEntry debitEntry = LedgerEntry.debit(
            payment.getId(),
            source.getId(),
            payment.getAmount(),
            source.getBalance(),        // balance AFTER debit
            "Payment authorization"
        );
        LedgerEntry creditEntry = LedgerEntry.credit(
            payment.getId(),
            dest.getId(),
            payment.getAmount(),
            dest.getBalance(),          // balance AFTER credit
            "Payment authorization received"
        );
        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        // 5. Save updated account balances
        accountRepository.save(source);
        accountRepository.save(dest);

        // 6. Transition payment status
        payment.transitionTo(PaymentStatus.AUTHORIZED);
        // Payment save happens in the calling poller after this method returns,
        // within the same @Transactional boundary propagated from the poller.

        // 7. Write status history
        PaymentStatusHistory history = PaymentStatusHistory.record(
            payment.getId(),
            PaymentStatus.PENDING,
            PaymentStatus.AUTHORIZED,
            null
        );
        paymentStatusHistoryRepository.save(history);
    }

    /**
     * Used by the cancel endpoint (Step 8) for AUTHORIZED -> CANCELLED. Restores the
     * debited funds to the source and reverses the credit on the destination.
     */
    @Transactional
    public void reverseAuthorization(Payment payment) {
        // 1. Pessimistic lock on both accounts
        Account source = accountRepository.findByIdForUpdate(payment.getSourceAccountId())
            .orElseThrow(() -> new AccountNotFoundException(payment.getSourceAccountId()));
        Account dest = accountRepository.findByIdForUpdate(payment.getDestAccountId())
            .orElseThrow(() -> new AccountNotFoundException(payment.getDestAccountId()));

        // 2. Reverse: credit source (restore debited amount), debit destination
        source.credit(payment.getAmount());
        dest.debit(payment.getAmount());

        // 3. Write compensating ledger entries
        LedgerEntry creditEntry = LedgerEntry.credit(
            payment.getId(),
            source.getId(),
            payment.getAmount(),
            source.getBalance(),
            "Authorization reversal — payment cancelled"
        );
        LedgerEntry debitEntry = LedgerEntry.debit(
            payment.getId(),
            dest.getId(),
            payment.getAmount(),
            dest.getBalance(),
            "Authorization reversal — payment cancelled"
        );
        ledgerEntryRepository.save(creditEntry);
        ledgerEntryRepository.save(debitEntry);

        // 4. Save updated balances
        accountRepository.save(source);
        accountRepository.save(dest);
    }

    /**
     * Used by Step 8 (refund). Signature present now so the class compiles; full logic
     * is implemented in Step 8.
     */
    @Transactional
    public void writeRefundEntries(Payment payment, RefundType refundType) {
        Account source = accountRepository.findByIdForUpdate(payment.getSourceAccountId())
            .orElseThrow(() -> new AccountNotFoundException(payment.getSourceAccountId()));
        Account dest = accountRepository.findByIdForUpdate(payment.getDestAccountId())
            .orElseThrow(() -> new AccountNotFoundException(payment.getDestAccountId()));

        if (refundType == RefundType.VOID) {
            // VOID: payment never settled — reverse the authorization
            // Same ledger logic as reverseAuthorization
            source.credit(payment.getAmount());
            dest.debit(payment.getAmount());
            ledgerEntryRepository.save(LedgerEntry.credit(
                payment.getId(), source.getId(), payment.getAmount(),
                source.getBalance(), "Refund VOID — authorization reversed"));
            ledgerEntryRepository.save(LedgerEntry.debit(
                payment.getId(), dest.getId(), payment.getAmount(),
                dest.getBalance(), "Refund VOID — authorization reversed"));
        } else {
            // REVERSAL: payment settled — new entries flow funds back
            source.credit(payment.getAmount());
            dest.debit(payment.getAmount());
            ledgerEntryRepository.save(LedgerEntry.credit(
                payment.getId(), source.getId(), payment.getAmount(),
                source.getBalance(), "Refund REVERSAL — settled payment returned"));
            ledgerEntryRepository.save(LedgerEntry.debit(
                payment.getId(), dest.getId(), payment.getAmount(),
                dest.getBalance(), "Refund REVERSAL — settled payment returned"));
        }
        accountRepository.save(source);
        accountRepository.save(dest);
    }
}
