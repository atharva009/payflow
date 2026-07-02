package com.payments.settlement;

import com.payments.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "settlement_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementEntry extends ImmutableEntity {

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "dest_account_id", nullable = false)
    private UUID destAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    public static SettlementEntry create(UUID batchId, UUID paymentId,
                                          UUID sourceAccountId, UUID destAccountId,
                                          BigDecimal amount) {
        SettlementEntry e = new SettlementEntry();
        e.batchId = batchId;
        e.paymentId = paymentId;
        e.sourceAccountId = sourceAccountId;
        e.destAccountId = destAccountId;
        e.amount = amount;
        return e;
    }
}
