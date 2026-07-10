package com.payments.unit.payment;

import com.payments.account.AccountService;
import com.payments.exception.ForbiddenException;
import com.payments.exception.SelfPaymentException;
import com.payments.idempotency.CachedResponse;
import com.payments.idempotency.IdempotencyService;
import com.payments.payment.Payment;
import com.payments.payment.PaymentRepository;
import com.payments.payment.PaymentRequest;
import com.payments.payment.PaymentService;
import com.payments.payment.PaymentStatus;
import com.payments.payment.PaymentStatusHistory;
import com.payments.payment.PaymentStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link PaymentService}. No Spring context — Mockito only.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    @Mock
    AccountService accountService;
    @Mock
    IdempotencyService idempotencyService;

    @InjectMocks
    PaymentService paymentService;

    private PaymentRequest request(UUID source, UUID dest, String amount) {
        return new PaymentRequest(source, dest, new BigDecimal(amount), "USD");
    }

    @Test
    void createPayment_savesPendingPayment() {
        UUID source = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        PaymentRequest req = request(source, dest, "150.00");

        when(idempotencyService.findCachedResponse(key)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = paymentService.createPayment(req, key, List.of(source));

        assertEquals(PaymentStatus.PENDING, result.getStatus());
        verify(paymentRepository, times(1)).save(any(Payment.class));
        verify(paymentStatusHistoryRepository, times(1)).save(any(PaymentStatusHistory.class));
    }

    @Test
    void createPayment_returnsFromCache_onDuplicateKey() {
        UUID source = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        PaymentRequest req = request(source, dest, "150.00");

        // Filter owns the HTTP replay; if the service is reached on a duplicate it does nothing.
        when(idempotencyService.findCachedResponse(key))
                .thenReturn(Optional.of(new CachedResponse(202, "{\"id\":\"x\"}", "hash")));

        paymentService.createPayment(req, key, List.of(source));

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(paymentStatusHistoryRepository, never()).save(any(PaymentStatusHistory.class));
    }

    @Test
    void createPayment_throwsForbiddenException_whenAccountNotOwnedByPrincipal() {
        UUID source = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        PaymentRequest req = request(source, dest, "150.00");

        when(idempotencyService.findCachedResponse(key)).thenReturn(Optional.empty());

        // authorizedAccountIds does NOT contain the source account.
        assertThrows(ForbiddenException.class,
                () -> paymentService.createPayment(req, key, List.of(UUID.randomUUID())));

        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void createPayment_throwsSelfPaymentException_whenSameAccounts() {
        UUID id = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        PaymentRequest req = request(id, id, "150.00");

        when(idempotencyService.findCachedResponse(key)).thenReturn(Optional.empty());
        doThrow(new SelfPaymentException())
                .when(accountService).validateForPayment(id, id);

        assertThrows(SelfPaymentException.class,
                () -> paymentService.createPayment(req, key, List.of(id)));

        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
