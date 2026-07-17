package com.payments.exception;

public class TransientProcessorException extends RuntimeException {
    public TransientProcessorException(String message) {
        super(message);
    }

    public TransientProcessorException(String message, Throwable cause) {
        super(message, cause);
    }
}
