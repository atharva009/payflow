package com.payments.account;

import com.payments.exception.AccountClosedException;
import com.payments.exception.AccountFrozenException;
import com.payments.exception.AccountNotFoundException;
import com.payments.exception.SelfPaymentException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account create(UUID ownerId, String currency) {
        Account account = Account.create(ownerId, currency);
        return accountRepository.save(account);
    }

    public Account findById(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    public void validateForPayment(UUID sourceId, UUID destId) {
        if (sourceId.equals(destId)) {
            throw new SelfPaymentException();
        }
        Account source = findById(sourceId);   // throws AccountNotFoundException if missing
        Account dest = findById(destId);        // throws AccountNotFoundException if missing

        if (source.getStatus() == AccountStatus.FROZEN) {
            throw new AccountFrozenException(sourceId);
        }
        if (source.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException(sourceId);
        }
        if (dest.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException(destId);
        }
        // FROZEN destination is permitted — funds may be received into a frozen account.
    }
}
