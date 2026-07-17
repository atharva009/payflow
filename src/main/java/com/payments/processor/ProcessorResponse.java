package com.payments.processor;

public record ProcessorResponse(boolean success, String processorRef, String failureCode) {
}
