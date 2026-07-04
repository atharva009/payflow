package com.payments.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory, UUID> {
    List<PaymentStatusHistory> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
