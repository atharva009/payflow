package com.payments.exception;

public class SelfPaymentException extends RuntimeException {
    public SelfPaymentException() {
        super("Source and destination account must differ");
    }
}
