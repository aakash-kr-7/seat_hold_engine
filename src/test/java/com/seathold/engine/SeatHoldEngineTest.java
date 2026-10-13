package com.seathold.engine;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** Foundation checks only; no seat operation behavior exists yet. */
class SeatHoldEngineTest {
    private static final Instant DEADLINE = Instant.parse("2026-01-01T00:01:00Z");

    @Test
    void constructorAcceptsAnInjectedClockAndRejectsNull() {
        assertDoesNotThrow(() -> new SeatHoldEngine(Clock.fixed(DEADLINE, ZoneOffset.UTC)));
        assertThrows(NullPointerException.class, () -> new SeatHoldEngine(null));
    }

    @Test
    void seatViewsHaveValueSemanticsAndStateSpecificFields() {
        assertEquals(new SeatStatus(SeatStatus.State.FREE, null, null), SeatStatus.free());
        assertEquals(new SeatStatus(SeatStatus.State.HELD, "alice", DEADLINE),
                SeatStatus.held("alice", DEADLINE));
        assertEquals(new SeatStatus(SeatStatus.State.SOLD, "alice", null), SeatStatus.sold("alice"));
        assertEquals(SeatStatus.held("alice", DEADLINE).hashCode(),
                SeatStatus.held("alice", DEADLINE).hashCode());
        assertNotEquals(SeatStatus.held("alice", DEADLINE), SeatStatus.sold("alice"));
    }

    @Test
    void seatViewsRejectContradictoryFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new SeatStatus(SeatStatus.State.FREE, "alice", null));
        assertThrows(NullPointerException.class, () -> SeatStatus.held("alice", null));
        assertThrows(NullPointerException.class, () -> SeatStatus.sold(null));
        assertThrows(IllegalArgumentException.class,
                () -> new SeatStatus(SeatStatus.State.SOLD, "alice", DEADLINE));
    }

    @Test
    void holdValuesRetainGenerationAndLifecycle() {
        Hold hold = new Hold("opaque-id", "A1", "alice", DEADLINE, 1, Hold.Lifecycle.ACTIVE);
        assertEquals(hold, new Hold("opaque-id", "A1", "alice", DEADLINE, 1, Hold.Lifecycle.ACTIVE));
        assertNotEquals(hold, new Hold("opaque-id", "A1", "alice", DEADLINE, 2, Hold.Lifecycle.ACTIVE));
        assertNotEquals(hold, new Hold("opaque-id", "A1", "alice", DEADLINE, 1, Hold.Lifecycle.RELEASED));
        assertEquals("opaque-id", hold.holdId());
        assertEquals(DEADLINE, hold.expiresAt());
        assertThrows(IllegalArgumentException.class,
                () -> new Hold("opaque-id", "A1", "alice", DEADLINE, 0, Hold.Lifecycle.ACTIVE));
    }

    @Test
    void semanticErrorsExposeCodesIndependentlyOfWording() {
        var failure = new SeatHoldException(SeatHoldException.Code.HOLD_STALE, "Superseded hold");
        assertEquals(SeatHoldException.Code.HOLD_STALE, failure.code());
    }
}
