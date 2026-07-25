package com.payments.refund;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable UUID id,
            @RequestBody @Valid RefundRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken jwtToken) {

        // Idempotency is enforced per-payment (one refund per payment) inside the service;
        // the Idempotency-Key header is required by contract.
        Refund refund = refundService.refund(id, request.amount(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RefundResponse.from(refund));
    }
}
