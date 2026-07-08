package com.payments.exception;

public class ConcurrentRequestException extends RuntimeException {
    public ConcurrentRequestException(String key) {
        super("Concurrent request with same idempotency key: " + key);
    }
}
