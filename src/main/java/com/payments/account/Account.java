package com.payments.account;

import com.payments.common.BaseEntity;
import com.payments.exception.InsufficientFundsException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseEntity {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    public static Account create(UUID ownerId, String currency) {
        Account a = new Account();
        a.ownerId = ownerId;
        a.currency = currency;
        a.balance = BigDecimal.ZERO;
        a.status = AccountStatus.ACTIVE;
        return a;
    }

    public void debit(BigDecimal amount) {
        if (amount.compareTo(this.balance) > 0) {
            throw new InsufficientFundsException(this.getId(), amount, this.balance);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }
}
