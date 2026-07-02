package com.payments.payment;

import com.payments.exception.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {

    PENDING,
    AUTHORIZED,
    CAPTURED,
    SETTLEMENT_QUEUED,
    SETTLED,
    CANCELLED,
    FAILED,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(PaymentStatus.class);

    private static final Set<PaymentStatus> TERMINAL_STATES =
            EnumSet.of(SETTLED, CANCELLED, FAILED, REFUNDED);

    static {
        ALLOWED_TRANSITIONS.put(PENDING, EnumSet.of(AUTHORIZED, FAILED, CANCELLED));
        ALLOWED_TRANSITIONS.put(AUTHORIZED, EnumSet.of(CAPTURED, FAILED, CANCELLED));
        ALLOWED_TRANSITIONS.put(CAPTURED, EnumSet.of(SETTLEMENT_QUEUED, REFUNDED));
        ALLOWED_TRANSITIONS.put(SETTLEMENT_QUEUED, EnumSet.of(SETTLED, FAILED));
        ALLOWED_TRANSITIONS.put(SETTLED, EnumSet.of(REFUNDED));
        ALLOWED_TRANSITIONS.put(CANCELLED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED_TRANSITIONS.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED_TRANSITIONS.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
    }

    public boolean canTransitionTo(PaymentStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, EnumSet.noneOf(PaymentStatus.class))
                .contains(target);
    }

    public PaymentStatus transitionTo(PaymentStatus target) {
        if (target == null) {
            throw new IllegalArgumentException("Target status must not be null");
        }
        if (!canTransitionTo(target)) {
            throw new InvalidStateTransitionException(this.name(), target.name());
        }
        return target;
    }

    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }
}
