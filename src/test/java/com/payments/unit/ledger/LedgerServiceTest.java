package com.payments.unit.ledger;

import com.payments.account.Account;
import com.payments.account.AccountRepository;
import com.payments.exception.InsufficientFundsException;
import com.payments.ledger.LedgerEntry;
import com.payments.ledger.LedgerEntryRepository;
import com.payments.ledger.LedgerEntryType;
import com.payments.ledger.LedgerService;
import com.payments.payment.Payment;
import com.payments.payment.PaymentStatus;
import com.payments.payment.PaymentStatusHistory;
import com.payments.payment.PaymentStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    LedgerEntryRepository ledgerEntryRepository;
    @Mock
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    LedgerService ledgerService;

    private Payment payment(UUID source, UUID dest, String amount) {
        return Payment.create("idem-" + UUID.randomUUID(), source, dest, new BigDecimal(amount), "USD");
    }

    // ---- authorizePayment ----

    @Test
    void authorizePayment_writesExactlyTwoLedgerEntries() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = Account.create(UUID.randomUUID(), "USD");
        source.credit(new BigDecimal("200.00"));
        Account dest = Account.create(UUID.randomUUID(), "USD");
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(dest));

        ledgerService.authorizePayment(payment(sourceId, destId, "150.00"));

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();
        // Debit entry saved first (source), credit entry second (destination).
        assertEquals(LedgerEntryType.DEBIT, entries.get(0).getEntryType());
        assertEquals(LedgerEntryType.CREDIT, entries.get(1).getEntryType());
    }

    @Test
    void authorizePayment_balanceAfterIsCorrect() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = Account.create(UUID.randomUUID(), "USD");
        source.credit(new BigDecimal("200.00"));
        Account dest = Account.create(UUID.randomUUID(), "USD"); // balance 0
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(dest));

        ledgerService.authorizePayment(payment(sourceId, destId, "150.00"));

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        LedgerEntry debit = captor.getAllValues().get(0);
        LedgerEntry credit = captor.getAllValues().get(1);
        assertEquals(0, debit.getBalanceAfter().compareTo(new BigDecimal("50.00")));   // 200 - 150
        assertEquals(0, credit.getBalanceAfter().compareTo(new BigDecimal("150.00"))); // 0 + 150
    }

    @Test
    void authorizePayment_throwsInsufficientFundsException_whenBalanceTooLow() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = Account.create(UUID.randomUUID(), "USD");
        source.credit(new BigDecimal("50.00"));
        Account dest = Account.create(UUID.randomUUID(), "USD");
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(dest));

        assertThrows(InsufficientFundsException.class,
                () -> ledgerService.authorizePayment(payment(sourceId, destId, "150.00")));

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void authorizePayment_writesStatusHistoryRow() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = Account.create(UUID.randomUUID(), "USD");
        source.credit(new BigDecimal("200.00"));
        Account dest = Account.create(UUID.randomUUID(), "USD");
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(dest));

        ledgerService.authorizePayment(payment(sourceId, destId, "150.00"));

        ArgumentCaptor<PaymentStatusHistory> captor =
                ArgumentCaptor.forClass(PaymentStatusHistory.class);
        verify(paymentStatusHistoryRepository, times(1)).save(captor.capture());
        assertEquals(PaymentStatus.PENDING, captor.getValue().getFromStatus());
        assertEquals(PaymentStatus.AUTHORIZED, captor.getValue().getToStatus());
    }

    @Test
    void authorizePayment_transitionsPaymentToAuthorized() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = Account.create(UUID.randomUUID(), "USD");
        source.credit(new BigDecimal("200.00"));
        Account dest = Account.create(UUID.randomUUID(), "USD");
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(dest));

        Payment payment = payment(sourceId, destId, "150.00");
        ledgerService.authorizePayment(payment);

        assertEquals(PaymentStatus.AUTHORIZED, payment.getStatus());
    }

    // ---- reverseAuthorization ----

    @Test
    void reverseAuthorization_creditsSourceAndDebitsDestination() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = Account.create(UUID.randomUUID(), "USD");
        source.credit(new BigDecimal("50.00"));   // balance after the prior debit of 150
        Account dest = Account.create(UUID.randomUUID(), "USD");
        dest.credit(new BigDecimal("150.00"));     // received the prior credit
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(dest));

        ledgerService.reverseAuthorization(payment(sourceId, destId, "150.00"));

        assertEquals(0, source.getBalance().compareTo(new BigDecimal("200.00"))); // restored
        assertEquals(0, dest.getBalance().compareTo(new BigDecimal("0.00")));     // reversed
        verify(ledgerEntryRepository, times(2)).save(any());
    }

    @Test
    void reverseAuthorization_writesCompensatingEntries() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = Account.create(UUID.randomUUID(), "USD");
        source.credit(new BigDecimal("50.00"));
        Account dest = Account.create(UUID.randomUUID(), "USD");
        dest.credit(new BigDecimal("150.00"));
        when(accountRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(destId)).thenReturn(Optional.of(dest));

        ledgerService.reverseAuthorization(payment(sourceId, destId, "150.00"));

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        // Credit to source saved first, debit to destination second.
        assertEquals(LedgerEntryType.CREDIT, captor.getAllValues().get(0).getEntryType());
        assertEquals(LedgerEntryType.DEBIT, captor.getAllValues().get(1).getEntryType());
    }

    // ---- entity-level guard ----

    @Test
    void negativeBalance_rejectedByEntityGuard() {
        Account account = Account.create(UUID.randomUUID(), "USD"); // balance ZERO

        assertThrows(InsufficientFundsException.class,
                () -> account.debit(BigDecimal.ONE));
    }
}
