package com.payments.common;

import com.payments.exception.AccountClosedException;
import com.payments.exception.AccountFrozenException;
import com.payments.exception.AccountNotFoundException;
import com.payments.exception.CacheUnavailableException;
import com.payments.exception.ConcurrentRequestException;
import com.payments.exception.ForbiddenException;
import com.payments.exception.IdempotencyConflictException;
import com.payments.exception.InsufficientFundsException;
import com.payments.exception.InvalidStateTransitionException;
import com.payments.exception.PaymentNotFoundException;
import com.payments.exception.PaymentNotRefundableException;
import com.payments.exception.ProcessorUnavailableException;
import com.payments.exception.RefundAmountExceedsOriginalException;
import com.payments.exception.SelfPaymentException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * RFC 7807 error catalogue. Uses Spring's {@link ProblemDetail} (no custom error body class).
 *
 * Catalogue entries handled elsewhere, by design:
 *   400 MISSING_IDEMPOTENCY_KEY / INVALID_IDEMPOTENCY_KEY — written directly in IdempotencyFilter
 *   401 UNAUTHORIZED — handled by Spring Security (resource server / entry point)
 *   429 RATE_LIMITED — no exception class yet; handler intentionally omitted until rate limiting exists
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TYPE_BASE = "https://api.payments.dev/errors/";

    // ---- 400 ----

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed Request",
                "Request validation failed", "MALFORMED_REQUEST", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if (message.toLowerCase().contains("currency")) {
            return build(HttpStatus.BAD_REQUEST, "currency-mismatch", "Currency Mismatch",
                    message, "CURRENCY_MISMATCH", request);
        }
        return build(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed Request",
                message, "MALFORMED_REQUEST", request);
    }

    @ExceptionHandler(RefundAmountExceedsOriginalException.class)
    public ResponseEntity<ProblemDetail> handleRefundAmount(
            RefundAmountExceedsOriginalException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "refund-amount-exceeds-original",
                "Refund Amount Exceeds Original", ex.getMessage(),
                "REFUND_AMOUNT_EXCEEDS_ORIGINAL", request);
    }

    // ---- 403 ----

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(
            ForbiddenException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", ex.getMessage(),
                "FORBIDDEN", request);
    }

    // ---- 404 ----

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePaymentNotFound(
            PaymentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "payment-not-found", "Payment Not Found",
                ex.getMessage(), "PAYMENT_NOT_FOUND", request);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAccountNotFound(
            AccountNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "account-not-found", "Account Not Found",
                ex.getMessage(), "ACCOUNT_NOT_FOUND", request);
    }

    // ---- 409 ----

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(
            IdempotencyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "idempotency-conflict", "Idempotency Conflict",
                ex.getMessage(), "IDEMPOTENCY_CONFLICT", request);
    }

    @ExceptionHandler(ConcurrentRequestException.class)
    public ResponseEntity<ProblemDetail> handleConcurrentRequest(
            ConcurrentRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "concurrent-request", "Concurrent Request",
                ex.getMessage(), "CONCURRENT_REQUEST", request);
    }

    // ---- 422 ----

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientFunds(
            InsufficientFundsException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-funds", "Insufficient Funds",
                ex.getMessage(), "INSUFFICIENT_FUNDS", request);
    }

    @ExceptionHandler(AccountFrozenException.class)
    public ResponseEntity<ProblemDetail> handleAccountFrozen(
            AccountFrozenException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "account-frozen", "Account Frozen",
                ex.getMessage(), "ACCOUNT_FROZEN", request);
    }

    @ExceptionHandler(AccountClosedException.class)
    public ResponseEntity<ProblemDetail> handleAccountClosed(
            AccountClosedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "account-closed", "Account Closed",
                ex.getMessage(), "ACCOUNT_CLOSED", request);
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidStateTransition(
            InvalidStateTransitionException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-state-transition",
                "Invalid State Transition", ex.getMessage(), "INVALID_STATE_TRANSITION", request);
    }

    @ExceptionHandler(PaymentNotRefundableException.class)
    public ResponseEntity<ProblemDetail> handlePaymentNotRefundable(
            PaymentNotRefundableException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "payment-not-refundable",
                "Payment Not Refundable", ex.getMessage(), "PAYMENT_NOT_REFUNDABLE", request);
    }

    @ExceptionHandler(SelfPaymentException.class)
    public ResponseEntity<ProblemDetail> handleSelfPayment(
            SelfPaymentException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "self-payment", "Self Payment",
                ex.getMessage(), "SELF_PAYMENT", request);
    }

    // ---- 503 ----

    @ExceptionHandler(ProcessorUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleProcessorUnavailable(
            ProcessorUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "processor-unavailable",
                "Processor Unavailable", ex.getMessage(), "PROCESSOR_UNAVAILABLE", request);
    }

    @ExceptionHandler(CacheUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleCacheUnavailable(
            CacheUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "cache-unavailable", "Cache Unavailable",
                ex.getMessage(), "CACHE_UNAVAILABLE", request);
    }

    // ---- 500 (catch-all — never leak ex.getMessage() or stack traces) ----

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal Server Error",
                "An unexpected error occurred.", "INTERNAL_ERROR", request);
    }

    private ResponseEntity<ProblemDetail> build(HttpStatus status, String typeSlug, String title,
                                                String detail, String errorCode,
                                                HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(URI.create(TYPE_BASE + typeSlug));
        pd.setTitle(title);
        pd.setDetail(detail);
        pd.setProperty("errorCode", errorCode);
        pd.setProperty("instance", request.getRequestURI());
        pd.setProperty("timestamp", Instant.now().toString());
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            pd.setProperty("traceId", traceId);
        }
        return ResponseEntity.status(status).body(pd);
    }
}
