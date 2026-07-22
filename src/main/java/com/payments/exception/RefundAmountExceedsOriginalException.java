package com.payments.exception;

public class RefundAmountExceedsOriginalException extends RuntimeException {
    public RefundAmountExceedsOriginalException(String message) {
        super(message);
    }
}
