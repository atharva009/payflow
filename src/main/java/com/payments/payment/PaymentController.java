package com.payments.payment;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestBody @Valid PaymentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            JwtAuthenticationToken jwtToken) {

        Payment payment = paymentService.createPayment(request, idempotencyKey, accountIds(jwtToken));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(payment));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentDetailResponse> getPayment(@PathVariable UUID id) {
        Payment payment = paymentService.getPayment(id);
        return ResponseEntity.ok(detail(payment));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<PaymentDetailResponse> capture(
            @PathVariable UUID id, JwtAuthenticationToken jwtToken) {
        Payment payment = paymentService.capturePayment(id, accountIds(jwtToken));
        return ResponseEntity.ok(detail(payment));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentDetailResponse> cancel(
            @PathVariable UUID id, JwtAuthenticationToken jwtToken) {
        Payment payment = paymentService.cancelPayment(id, accountIds(jwtToken));
        return ResponseEntity.ok(detail(payment));
    }

    private List<UUID> accountIds(JwtAuthenticationToken jwtToken) {
        return ((List<?>) jwtToken.getToken().getClaim("accountIds"))
                .stream().map(s -> UUID.fromString(s.toString())).toList();
    }

    private PaymentDetailResponse detail(Payment payment) {
        return PaymentDetailResponse.from(payment, paymentService.getStatusHistory(payment.getId()));
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCreatedAt());
    }
}
