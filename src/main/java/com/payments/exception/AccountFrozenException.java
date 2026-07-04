package com.payments.exception;

import java.util.UUID;

public class AccountFrozenException extends RuntimeException {
    public AccountFrozenException(UUID accountId) {
        super("Account " + accountId + " is frozen");
    }
}
