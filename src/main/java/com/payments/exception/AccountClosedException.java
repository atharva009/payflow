package com.payments.exception;

import java.util.UUID;

public class AccountClosedException extends RuntimeException {
    public AccountClosedException(UUID accountId) {
        super("Account " + accountId + " is closed");
    }
}
