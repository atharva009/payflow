package com.payments.unit.account;

import com.payments.account.Account;
import com.payments.account.AccountRepository;
import com.payments.account.AccountService;
import com.payments.account.AccountStatus;
import com.payments.exception.AccountClosedException;
import com.payments.exception.AccountFrozenException;
import com.payments.exception.AccountNotFoundException;
import com.payments.exception.InsufficientFundsException;
import com.payments.exception.SelfPaymentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link AccountService}. No Spring context, no Testcontainers,
 * no database — Mockito only.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    AccountService accountService;

    // ---- create ----

    @Test
    void create_savesAndReturnsAccount() {
        UUID ownerId = UUID.randomUUID();
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account result = accountService.create(ownerId, "USD");

        verify(accountRepository, times(1)).save(any(Account.class));
        assertEquals(0, result.getBalance().compareTo(BigDecimal.ZERO));
        assertEquals(AccountStatus.ACTIVE, result.getStatus());
    }

    // ---- findById ----

    @Test
    void findById_returnsAccount_whenExists() {
        UUID id = UUID.randomUUID();
        Account account = Account.create(UUID.randomUUID(), "USD");
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        Account result = accountService.findById(id);

        assertSame(account, result);
    }

    @Test
    void findById_throwsAccountNotFoundException_whenMissing() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> accountService.findById(id));
    }

    // ---- validateForPayment ----

    @Test
    void validateForPayment_throwsSelfPaymentException_whenSameId() {
        UUID id = UUID.randomUUID();

        assertThrows(SelfPaymentException.class, () -> accountService.validateForPayment(id, id));

        // Self-check happens before any DB lookup.
        verify(accountRepository, never()).findById(any());
    }

    @Test
    void validateForPayment_throwsAccountNotFoundException_whenSourceMissing() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        when(accountRepository.findById(sourceId)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class,
                () -> accountService.validateForPayment(sourceId, destId));
    }

    @Test
    void validateForPayment_throwsAccountFrozenException_whenSourceFrozen() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = mock(Account.class);
        when(source.getStatus()).thenReturn(AccountStatus.FROZEN);
        Account dest = Account.create(UUID.randomUUID(), "USD"); // ACTIVE, status never inspected
        when(accountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findById(destId)).thenReturn(Optional.of(dest));

        assertThrows(AccountFrozenException.class,
                () -> accountService.validateForPayment(sourceId, destId));
    }

    @Test
    void validateForPayment_throwsAccountClosedException_whenSourceClosed() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = mock(Account.class);
        when(source.getStatus()).thenReturn(AccountStatus.CLOSED);
        Account dest = Account.create(UUID.randomUUID(), "USD"); // ACTIVE, status never inspected
        when(accountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findById(destId)).thenReturn(Optional.of(dest));

        assertThrows(AccountClosedException.class,
                () -> accountService.validateForPayment(sourceId, destId));
    }

    @Test
    void validateForPayment_throwsAccountClosedException_whenDestClosed() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = Account.create(UUID.randomUUID(), "USD"); // ACTIVE
        Account dest = mock(Account.class);
        when(dest.getStatus()).thenReturn(AccountStatus.CLOSED);
        when(accountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findById(destId)).thenReturn(Optional.of(dest));

        assertThrows(AccountClosedException.class,
                () -> accountService.validateForPayment(sourceId, destId));
    }

    @Test
    void validateForPayment_succeeds_whenDestFrozen() {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        Account source = Account.create(UUID.randomUUID(), "USD"); // ACTIVE
        Account dest = mock(Account.class);
        when(dest.getStatus()).thenReturn(AccountStatus.FROZEN); // FROZEN dest is allowed
        when(accountRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findById(destId)).thenReturn(Optional.of(dest));

        assertDoesNotThrow(() -> accountService.validateForPayment(sourceId, destId));
    }

    // ---- entity domain methods: debit / credit ----

    @Test
    void debit_reducesBalance() {
        Account account = Account.create(UUID.randomUUID(), "USD");
        account.credit(new BigDecimal("100.00"));

        account.debit(new BigDecimal("30.00"));

        assertEquals(0, account.getBalance().compareTo(new BigDecimal("70.00")));
    }

    @Test
    void debit_throwsInsufficientFundsException_whenAmountExceedsBalance() {
        Account account = Account.create(UUID.randomUUID(), "USD"); // zero balance

        assertThrows(InsufficientFundsException.class, () -> account.debit(BigDecimal.ONE));
    }

    @Test
    void credit_increasesBalance() {
        Account account = Account.create(UUID.randomUUID(), "USD");

        account.credit(new BigDecimal("100.00"));

        assertEquals(0, account.getBalance().compareTo(new BigDecimal("100.00")));
    }
}
