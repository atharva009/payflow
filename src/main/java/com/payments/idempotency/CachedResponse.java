package com.payments.idempotency;

public record CachedResponse(int statusCode, String body, String requestHash) {
}
