package com.payments.exception;

public class ProcessorUnavailableException extends RuntimeException {
    public ProcessorUnavailableException(String message) {
        super(message);
    }
}
