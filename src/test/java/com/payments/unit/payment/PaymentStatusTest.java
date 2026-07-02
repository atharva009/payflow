package com.payments.unit.payment;

import com.payments.exception.InvalidStateTransitionException;
import com.payments.payment.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit 5 contract test for {@link PaymentStatus}. No Spring, no Mockito.
 *
 * Assertion budget (79 total):
 *   - 28  parameterized non-terminal transition matrix (4 non-terminal sources x 7 non-self targets)
 *   - 36  terminal-state test (4 terminal states x 8 targets = 32, plus 4 isTerminal() == true)
 *   - 4   non-terminal isTerminal() == false
 *   - 8   self-transition (8 states x 1)
 *   - 1   null canTransitionTo
 *   - 1   transitionTo returns target
 *   - 1   transitionTo throws on invalid
 */
class PaymentStatusTest {

    /** Independent oracle: the 11 valid FROM -> TO transitions, written by hand. */
    private static final Set<String> VALID = Set.of(
            "PENDING->AUTHORIZED",
            "PENDING->FAILED",
            "PENDING->CANCELLED",
            "AUTHORIZED->CAPTURED",
            "AUTHORIZED->FAILED",
            "AUTHORIZED->CANCELLED",
            "CAPTURED->SETTLEMENT_QUEUED",
            "CAPTURED->REFUNDED",
            "SETTLEMENT_QUEUED->SETTLED",
            "SETTLEMENT_QUEUED->FAILED",
            "SETTLED->REFUNDED"
    );

    private static final Set<PaymentStatus> TERMINAL = Set.of(
            PaymentStatus.SETTLED,
            PaymentStatus.CANCELLED,
            PaymentStatus.FAILED,
            PaymentStatus.REFUNDED
    );

    private static final Set<PaymentStatus> NON_TERMINAL = Set.of(
            PaymentStatus.PENDING,
            PaymentStatus.AUTHORIZED,
            PaymentStatus.CAPTURED,
            PaymentStatus.SETTLEMENT_QUEUED
    );

    private static String key(PaymentStatus from, PaymentStatus to) {
        return from.name() + "->" + to.name();
    }

    // ---- 28 assertions: non-terminal source x non-self target matrix ----

    static Stream<Arguments> nonTerminalMatrix() {
        List<Arguments> args = new ArrayList<>();
        for (PaymentStatus from : NON_TERMINAL) {
            for (PaymentStatus to : PaymentStatus.values()) {
                if (from == to) {
                    continue; // self-transitions covered separately
                }
                args.add(Arguments.of(from, to, VALID.contains(key(from, to))));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0} -> {1} canTransition={2}")
    @MethodSource("nonTerminalMatrix")
    void nonTerminalTransitionMatrix(PaymentStatus from, PaymentStatus to, boolean expected) {
        assertEquals(expected, from.canTransitionTo(to));
    }

    // ---- 36 assertions: terminal states' full target matrix (32) + isTerminal() true (4) ----
    // Note: a state being terminal (isTerminal() == true) is independent of whether it has an
    // allowed transition. SETTLED is terminal yet SETTLED -> REFUNDED is a valid transition,
    // so the per-target expectation is driven by the same VALID oracle, not a blanket false.

    @Test
    void terminalStatesTransitionMatrixAndAreTerminal() {
        for (PaymentStatus from : TERMINAL) {
            for (PaymentStatus to : PaymentStatus.values()) {
                boolean expected = VALID.contains(key(from, to));
                assertEquals(expected, from.canTransitionTo(to),
                        from + " (terminal) -> " + to);
            }
            assertTrue(from.isTerminal(), from + " must be terminal");
        }
    }

    // ---- 4 assertions: non-terminal states are not terminal ----

    @Test
    void nonTerminalStatesAreNotTerminal() {
        for (PaymentStatus s : NON_TERMINAL) {
            assertFalse(s.isTerminal(), s + " must not be terminal");
        }
    }

    // ---- 8 assertions: no state may transition to itself ----

    @Test
    void noSelfTransitions() {
        for (PaymentStatus s : PaymentStatus.values()) {
            assertFalse(s.canTransitionTo(s), s + " must not transition to itself");
        }
    }

    // ---- 1 assertion: null target is never transitionable ----

    @Test
    void canTransitionToNullIsFalse() {
        assertFalse(PaymentStatus.PENDING.canTransitionTo(null));
    }

    // ---- 1 assertion: transitionTo returns the target on a valid transition ----

    @Test
    void transitionToReturnsTarget() {
        assertEquals(PaymentStatus.AUTHORIZED,
                PaymentStatus.PENDING.transitionTo(PaymentStatus.AUTHORIZED));
    }

    // ---- 1 assertion: transitionTo throws on an invalid transition ----

    @Test
    void transitionToThrowsOnInvalid() {
        assertThrows(InvalidStateTransitionException.class,
                () -> PaymentStatus.PENDING.transitionTo(PaymentStatus.SETTLED));
    }
}
