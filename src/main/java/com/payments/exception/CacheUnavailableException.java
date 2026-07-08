package com.payments.exception;

public class CacheUnavailableException extends RuntimeException {
    public CacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
