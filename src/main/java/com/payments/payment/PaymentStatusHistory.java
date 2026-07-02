package com.payments.payment;

import com.payments.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import java.util.UUID;

@Entity
@Table(name = "payment_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentStatusHistory extends ImmutableEntity {

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private PaymentStatus toStatus;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    public static PaymentStatusHistory record(UUID paymentId,
                                               PaymentStatus from,
                                               PaymentStatus to,
                                               String reason) {
        PaymentStatusHistory h = new PaymentStatusHistory();
        h.paymentId = paymentId;
        h.fromStatus = from;
        h.toStatus = to;
        h.reason = reason;
        return h;
    }
}
