package com.payments.ledger;

import com.payments.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry extends ImmutableEntity {

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private LedgerEntryType entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    public static LedgerEntry debit(UUID paymentId, UUID accountId,
                                     BigDecimal amount, BigDecimal balanceAfter,
                                     String description) {
        LedgerEntry e = new LedgerEntry();
        e.paymentId = paymentId;
        e.accountId = accountId;
        e.entryType = LedgerEntryType.DEBIT;
        e.amount = amount;
        e.balanceAfter = balanceAfter;
        e.description = description;
        return e;
    }

    public static LedgerEntry credit(UUID paymentId, UUID accountId,
                                      BigDecimal amount, BigDecimal balanceAfter,
                                      String description) {
        LedgerEntry e = new LedgerEntry();
        e.paymentId = paymentId;
        e.accountId = accountId;
        e.entryType = LedgerEntryType.CREDIT;
        e.amount = amount;
        e.balanceAfter = balanceAfter;
        e.description = description;
        return e;
    }
}
