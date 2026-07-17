package com.payments.exception;

public class PermanentProcessorException extends RuntimeException {
    public PermanentProcessorException(String message) {
        super(message);
    }
}
