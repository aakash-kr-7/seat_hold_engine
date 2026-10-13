package com.seathold.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeatHoldEngineTest {
    private static final Instant DEADLINE = Instant.parse("2026-01-01T00:01:00Z");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(1);
    private final MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
    private final SeatHoldEngine engine = new SeatHoldEngine(clock);

    @Test
    void constructorAcceptsAnInjectedClockAndRejectsNull() {
        assertDoesNotThrow(() -> new SeatHoldEngine(clock));
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

    @Test
    void newSeatIsFree() {
        assertEquals(SeatStatus.free(), engine.seatStatus("A1"));
    }

    @Test
    void basicHoldReportsOwnerAndExpiry() {
        String id = engine.hold("A1", "alice", TTL);
        assertNotNull(id);
        assertFalse(id.isBlank());
        assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus("A1"));
    }

    @Test
    void competingUserCannotTakeAnActiveHold() {
        String id = engine.hold("A1", "alice", TTL);
        assertCode(SeatHoldException.Code.SEAT_UNAVAILABLE, () -> engine.hold("A1", "bob", TTL));
        engine.confirm(id);
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
    }

    @Test
    void sameUserRetryReturnsOriginalIdAndDeadline() {
        String id = engine.hold("A1", "alice", TTL);
        clock.advance(Duration.ofSeconds(30));
        assertEquals(id, engine.hold("A1", "alice", Duration.ofHours(1)));
        assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus("A1"));
        clock.advance(Duration.ofSeconds(30));
        assertEquals(SeatStatus.free(), engine.seatStatus("A1"));
    }

    @Test
    void releaseFreesSeatAndPermanentlyInvalidatesHold() {
        String id = engine.hold("A1", "alice", TTL);
        engine.release(id);
        assertEquals(SeatStatus.free(), engine.seatStatus("A1"));
        assertCode(SeatHoldException.Code.HOLD_RELEASED, () -> engine.confirm(id));
        clock.advance(TTL);
        assertCode(SeatHoldException.Code.HOLD_RELEASED, () -> engine.confirm(id));
    }

    @Test
    void repeatedReleaseIsHarmless() {
        String id = engine.hold("A1", "alice", TTL);
        engine.release(id);
        engine.release(id);
        assertEquals(SeatStatus.free(), engine.seatStatus("A1"));
    }

    @Test
    void staleReleaseCannotFreeNewerHold() {
        String oldId = engine.hold("A1", "alice", TTL);
        clock.advance(TTL);
        String newId = engine.hold("A1", "bob", TTL);
        engine.release(oldId);
        assertEquals(SeatStatus.held("bob", clock.instant().plus(TTL)), engine.seatStatus("A1"));
        assertCode(SeatHoldException.Code.HOLD_RELEASED, () -> engine.confirm(oldId));
        engine.confirm(newId);
        assertEquals(SeatStatus.sold("bob"), engine.seatStatus("A1"));
    }

    @Test
    void confirmationSellsSeat() {
        String id = engine.hold("A1", "alice", TTL);
        engine.confirm(id);
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
        clock.advance(Duration.ofDays(1));
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
    }

    @Test
    void soldSeatCannotBeHeldOrSoldTwice() {
        String id = engine.hold("A1", "alice", TTL);
        engine.confirm(id);
        assertCode(SeatHoldException.Code.ALREADY_SOLD, () -> engine.hold("A1", "bob", TTL));
        assertCode(SeatHoldException.Code.ALREADY_SOLD, () -> engine.hold("A1", "alice", TTL));
        assertCode(SeatHoldException.Code.ALREADY_SOLD, () -> engine.confirm(id));
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
    }

    @Test
    void releaseCannotUndoSale() {
        String id = engine.hold("A1", "alice", TTL);
        engine.confirm(id);
        assertCode(SeatHoldException.Code.ALREADY_SOLD, () -> engine.release(id));
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
    }

    @Test
    void fourActiveHoldsAreAllowedAndFifthFails() {
        for (int seat = 1; seat <= 4; seat++) {
            engine.hold("A" + seat, "alice", TTL);
        }
        assertCode(SeatHoldException.Code.USER_HOLD_LIMIT, () -> engine.hold("A5", "alice", TTL));
        assertEquals(SeatStatus.free(), engine.seatStatus("A5"));
        assertDoesNotThrow(() -> engine.hold("A5", "bob", TTL));
    }

    @Test
    void retryAtLimitDoesNotConsumeAnExtraSlot() {
        String id = engine.hold("A1", "alice", TTL);
        for (int seat = 2; seat <= 4; seat++) {
            engine.hold("A" + seat, "alice", TTL);
        }
        assertEquals(id, engine.hold("A1", "alice", Duration.ofHours(1)));
        assertCode(SeatHoldException.Code.USER_HOLD_LIMIT, () -> engine.hold("A5", "alice", TTL));
        engine.confirm(id);
    }

    @Test
    void expiredHoldsDoNotCountWithoutPriorStatusCalls() {
        for (int seat = 1; seat <= 4; seat++) {
            engine.hold("A" + seat, "alice", TTL);
        }
        clock.advance(TTL);
        for (int seat = 5; seat <= 8; seat++) {
            engine.hold("A" + seat, "alice", TTL);
        }
        assertCode(SeatHoldException.Code.USER_HOLD_LIMIT, () -> engine.hold("A9", "alice", TTL));
    }

    @Test
    void releasedAndConfirmedHoldsDoNotConsumeCapacity() {
        String first = engine.hold("A1", "alice", TTL);
        String second = engine.hold("A2", "alice", TTL);
        engine.hold("A3", "alice", TTL);
        engine.hold("A4", "alice", TTL);
        engine.release(first);
        engine.confirm(second);
        engine.hold("A5", "alice", TTL);
        engine.hold("A6", "alice", TTL);
        assertCode(SeatHoldException.Code.USER_HOLD_LIMIT, () -> engine.hold("A7", "alice", TTL));
    }

    @Test
    void exactDeadlineIsExpiredButOneMillisecondBeforeIsHeld() {
        engine.hold("A1", "alice", TTL);
        clock.advance(TTL.minusMillis(1));
        assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus("A1"));
        clock.advance(Duration.ofMillis(1));
        assertEquals(SeatStatus.free(), engine.seatStatus("A1"));
        assertDoesNotThrow(() -> engine.hold("A1", "bob", TTL));
    }

    @Test
    void holdingAgainAfterExpiryCreatesNewIdAndDeadline() {
        String oldId = engine.hold("A1", "alice", TTL);
        clock.advance(TTL);
        String newId = engine.hold("A1", "alice", Duration.ofMinutes(2));
        assertNotEquals(oldId, newId);
        assertEquals(SeatStatus.held("alice", START.plusSeconds(180)), engine.seatStatus("A1"));
        assertCode(SeatHoldException.Code.HOLD_STALE, () -> engine.confirm(oldId));
        engine.confirm(newId);
    }

    @Test
    void lateConfirmationSucceedsAfterExpiryAndStatusObservation() {
        String id = engine.hold("A1", "alice", TTL);
        clock.advance(Duration.ofDays(1));
        assertEquals(SeatStatus.free(), engine.seatStatus("A1"));
        engine.confirm(id);
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
    }

    @Test
    void lateConfirmationSucceedsAtExactDeadline() {
        String id = engine.hold("A1", "alice", TTL);
        clock.advance(TTL);
        engine.confirm(id);
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
    }

    @Test
    void oldExpiredHoldCannotConfirmAfterNewerAcquisition() {
        String oldId = engine.hold("A1", "alice", TTL);
        clock.advance(TTL);
        String newId = engine.hold("A1", "bob", TTL);
        assertCode(SeatHoldException.Code.HOLD_STALE, () -> engine.confirm(oldId));
        assertEquals(SeatStatus.held("bob", START.plusSeconds(120)), engine.seatStatus("A1"));
        engine.confirm(newId);
        assertCode(SeatHoldException.Code.HOLD_STALE, () -> engine.confirm(oldId));
        assertEquals(SeatStatus.sold("bob"), engine.seatStatus("A1"));
    }

    @Test
    void newerReleaseNeverRestoresOldConfirmationEligibility() {
        String oldId = engine.hold("A1", "alice", TTL);
        clock.advance(TTL);
        String newId = engine.hold("A1", "bob", TTL);
        engine.release(newId);
        assertEquals(SeatStatus.free(), engine.seatStatus("A1"));
        assertCode(SeatHoldException.Code.HOLD_STALE, () -> engine.confirm(oldId));
        assertCode(SeatHoldException.Code.HOLD_RELEASED, () -> engine.confirm(newId));
    }

    @Test
    void newerExpiryNeverRestoresOldConfirmationEligibility() {
        String oldId = engine.hold("A1", "alice", TTL);
        clock.advance(TTL);
        String newId = engine.hold("A1", "bob", TTL);
        clock.advance(TTL);
        assertCode(SeatHoldException.Code.HOLD_STALE, () -> engine.confirm(oldId));
        engine.confirm(newId);
        assertEquals(SeatStatus.sold("bob"), engine.seatStatus("A1"));
    }

    @Test
    void rejectedAcquisitionDoesNotInvalidateLateConfirmation() {
        String oldId = engine.hold("A1", "alice", TTL);
        for (int seat = 2; seat <= 5; seat++) {
            engine.hold("A" + seat, "bob", Duration.ofHours(1));
        }
        clock.advance(TTL);
        assertCode(SeatHoldException.Code.USER_HOLD_LIMIT, () -> engine.hold("A1", "bob", TTL));
        engine.confirm(oldId);
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
    }

    @Test
    void nullAndBlankIdsAreRejectedWithoutAcquiringSeats() {
        for (String invalid : new String[] { null, "", " \t\n" }) {
            assertCode(SeatHoldException.Code.INVALID_ARGUMENT, () -> engine.hold(invalid, "alice", TTL));
            assertCode(SeatHoldException.Code.INVALID_ARGUMENT, () -> engine.hold("A1", invalid, TTL));
            assertCode(SeatHoldException.Code.INVALID_ARGUMENT, () -> engine.seatStatus(invalid));
            assertCode(SeatHoldException.Code.INVALID_ARGUMENT, () -> engine.confirm(invalid));
            assertCode(SeatHoldException.Code.INVALID_ARGUMENT, () -> engine.release(invalid));
        }
        assertEquals(SeatStatus.free(), engine.seatStatus("A1"));
    }

    @Test
    void nullZeroAndNegativeTtlsAreRejectedIncludingOnRetry() {
        for (Duration invalid : new Duration[] { null, Duration.ZERO, Duration.ofNanos(-1) }) {
            assertCode(SeatHoldException.Code.INVALID_ARGUMENT, () -> engine.hold("A1", "alice", invalid));
        }
        String id = engine.hold("A1", "alice", TTL);
        assertCode(SeatHoldException.Code.INVALID_ARGUMENT,
                () -> engine.hold("A1", "alice", Duration.ZERO));
        assertEquals(id, engine.hold("A1", "alice", TTL));
        assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus("A1"));
    }

    @Test
    void unrepresentableDeadlineIsRejectedWithoutChangingHistory() {
        String oldId = engine.hold("A1", "alice", TTL);
        clock.advance(TTL);
        assertCode(SeatHoldException.Code.INVALID_ARGUMENT,
                () -> engine.hold("A1", "bob", Duration.ofSeconds(Long.MAX_VALUE)));
        engine.confirm(oldId);
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
    }

    @Test
    void unknownHoldIdsHaveExplicitErrors() {
        assertCode(SeatHoldException.Code.HOLD_NOT_FOUND, () -> engine.confirm("unknown"));
        assertCode(SeatHoldException.Code.HOLD_NOT_FOUND, () -> engine.release("unknown"));
        assertEquals(SeatStatus.free(), engine.seatStatus("A1"));
    }

    @Test
    void statusSnapshotDoesNotChangeWhenSeatIsSold() {
        String id = engine.hold("A1", "alice", TTL);
        SeatStatus snapshot = engine.seatStatus("A1");
        engine.confirm(id);
        assertEquals(SeatStatus.held("alice", DEADLINE), snapshot);
        assertEquals(SeatStatus.sold("alice"), engine.seatStatus("A1"));
    }

    @Test
    void twoSeatBatchReturnsRequestedOrderAndCommonDeadline() {
        var result = engine.holdMany("alice", List.of("B", "A"), TTL);
        assertEquals(List.of("B", "A"), new ArrayList<>(result.keySet()));
        assertNotEquals(result.get("A"), result.get("B"));
        assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus("A"));
        assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus("B"));
        engine.confirm(result.get("A"));
        engine.confirm(result.get("B"));
    }

    @Test
    void batchCanAcquireExactlyFourSeats() {
        var result = engine.holdMany("alice", List.of("A", "B", "C", "D"), TTL);
        assertEquals(4, result.size());
        for (String seat : result.keySet()) {
            assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus(seat));
        }
    }

    @Test
    void fiveSeatBatchFailsWithoutAcquiringAnySeat() {
        var seats = List.of("A", "B", "C", "D", "E");
        assertCode(SeatHoldException.Code.USER_HOLD_LIMIT, () -> engine.holdMany("alice", seats, TTL));
        for (String seat : seats) {
            assertEquals(SeatStatus.free(), engine.seatStatus(seat));
        }
        assertEquals("hold-1", engine.hold("E", "alice", TTL));
    }

    @Test
    void unavailableSeatRejectsBatchWithoutAdvancingSequenceOrCreatingRecords() {
        String bobId = engine.hold("B", "bob", TTL);
        assertCode(SeatHoldException.Code.SEAT_UNAVAILABLE,
                () -> engine.holdMany("alice", List.of("A", "B"), TTL));
        assertEquals(SeatStatus.free(), engine.seatStatus("A"));
        assertEquals(SeatStatus.held("bob", DEADLINE), engine.seatStatus("B"));
        assertCode(SeatHoldException.Code.HOLD_NOT_FOUND, () -> engine.confirm("hold-2"));
        assertEquals("hold-2", engine.hold("A", "alice", TTL));
        engine.confirm(bobId);
    }

    @Test
    void soldSeatRejectsEntireBatch() {
        String id = engine.hold("B", "bob", TTL);
        engine.confirm(id);
        assertCode(SeatHoldException.Code.ALREADY_SOLD,
                () -> engine.holdMany("alice", List.of("A", "B"), TTL));
        assertEquals(SeatStatus.free(), engine.seatStatus("A"));
        assertEquals(SeatStatus.sold("bob"), engine.seatStatus("B"));
        assertEquals("hold-2", engine.hold("A", "alice", TTL));
    }

    @Test
    void mixedBatchPreservesExistingIdsAndExpiriesWithoutDoubleCounting() {
        String a = engine.hold("A", "alice", TTL);
        String b = engine.hold("B", "alice", Duration.ofMinutes(2));
        clock.advance(Duration.ofSeconds(30));
        var result = engine.holdMany("alice", List.of("A", "B", "C", "D"), Duration.ofHours(1));
        assertEquals(a, result.get("A"));
        assertEquals(b, result.get("B"));
        assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus("A"));
        assertEquals(SeatStatus.held("alice", START.plusSeconds(120)), engine.seatStatus("B"));
        assertEquals(SeatStatus.held("alice", clock.instant().plusSeconds(3_600)), engine.seatStatus("C"));
        assertCode(SeatHoldException.Code.USER_HOLD_LIMIT,
                () -> engine.holdMany("alice", List.of("A", "B", "C", "D", "E"), TTL));
        assertEquals(SeatStatus.free(), engine.seatStatus("E"));
        engine.confirm(a);
        engine.confirm(b);
    }

    @Test
    void entirelyIdempotentBatchAtLimitPreservesGenerationsAndSequence() {
        var seats = List.of("A", "B", "C", "D");
        var original = engine.holdMany("alice", seats, TTL);
        clock.advance(Duration.ofSeconds(1));
        assertEquals(original, engine.holdMany("alice", seats, Duration.ofHours(1)));
        for (String id : original.values()) {
            engine.confirm(id);
        }
        assertEquals("hold-5", engine.hold("E", "alice", TTL));
    }

    @Test
    void activeHoldsOutsideBatchCountTowardLimit() {
        engine.hold("A", "alice", TTL);
        engine.hold("B", "alice", TTL);
        assertCode(SeatHoldException.Code.USER_HOLD_LIMIT,
                () -> engine.holdMany("alice", List.of("C", "D", "E"), TTL));
        for (String seat : List.of("C", "D", "E")) {
            assertEquals(SeatStatus.free(), engine.seatStatus(seat));
        }
        assertEquals(2, engine.holdMany("alice", List.of("C", "D"), TTL).size());
    }

    @Test
    void expiredSeatsAreNewAcquisitionsAndOldHoldsStayStaleAfterRelease() {
        var original = engine.holdMany("alice", List.of("A", "B", "C", "D"), TTL);
        clock.advance(TTL);
        var renewed = engine.holdMany("alice", List.of("B", "A", "C", "D"), TTL);
        for (String seat : original.keySet()) {
            assertNotEquals(original.get(seat), renewed.get(seat));
            assertEquals(SeatStatus.held("alice", DEADLINE.plus(TTL)), engine.seatStatus(seat));
            engine.release(renewed.get(seat));
            assertCode(SeatHoldException.Code.HOLD_STALE, () -> engine.confirm(original.get(seat)));
        }
    }

    @Test
    void rejectedBatchDoesNotSupersedeExpiredHoldOrChangeExistingRetry() {
        String oldId = engine.hold("A", "alice", TTL);
        String currentId = engine.hold("C", "alice", Duration.ofHours(1));
        engine.hold("B", "bob", Duration.ofHours(1));
        clock.advance(TTL);
        assertCode(SeatHoldException.Code.SEAT_UNAVAILABLE,
                () -> engine.holdMany("alice", List.of("A", "C", "B"), TTL));
        assertEquals(SeatStatus.free(), engine.seatStatus("A"));
        assertEquals(currentId, engine.hold("C", "alice", TTL));
        assertEquals(SeatStatus.held("alice", START.plusSeconds(3_600)), engine.seatStatus("C"));
        engine.confirm(oldId);
        assertEquals("hold-4", engine.hold("D", "alice", TTL));
    }

    @Test
    void duplicateSeatsAreRejectedEvenIfAlreadyHeldBySameUser() {
        String id = engine.hold("A", "alice", TTL);
        assertCode(SeatHoldException.Code.INVALID_ARGUMENT,
                () -> engine.holdMany("alice", List.of("B", "A", "A"), TTL));
        assertEquals(SeatStatus.free(), engine.seatStatus("B"));
        assertEquals(id, engine.hold("A", "alice", TTL));
        assertCode(SeatHoldException.Code.INVALID_ARGUMENT,
                () -> engine.holdMany("alice", List.of("B", "B"), TTL));
        assertEquals("hold-2", engine.hold("B", "alice", TTL));
    }

    @Test
    void nullAndEmptyBatchListsAreInvalid() {
        assertCode(SeatHoldException.Code.INVALID_ARGUMENT, () -> engine.holdMany("alice", null, TTL));
        assertCode(SeatHoldException.Code.INVALID_ARGUMENT, () -> engine.holdMany("alice", List.of(), TTL));
        assertEquals("hold-1", engine.hold("A", "alice", TTL));
    }

    @Test
    void invalidBatchIdsAreRejectedBeforeAnyAcquisition() {
        for (String invalid : new String[] { null, "", " \t\n" }) {
            assertCode(SeatHoldException.Code.INVALID_ARGUMENT,
                    () -> engine.holdMany(invalid, List.of("A"), TTL));
            assertCode(SeatHoldException.Code.INVALID_ARGUMENT,
                    () -> engine.holdMany("alice", Arrays.asList("A", invalid), TTL));
        }
        assertEquals(SeatStatus.free(), engine.seatStatus("A"));
        assertEquals("hold-1", engine.hold("A", "alice", TTL));
    }

    @Test
    void invalidBatchTtlsDoNotChangeState() {
        for (Duration invalid : new Duration[] { null, Duration.ZERO, Duration.ofNanos(-1) }) {
            assertCode(SeatHoldException.Code.INVALID_ARGUMENT,
                    () -> engine.holdMany("alice", List.of("A", "B"), invalid));
        }
        assertEquals(SeatStatus.free(), engine.seatStatus("A"));
        assertEquals(SeatStatus.free(), engine.seatStatus("B"));
        assertEquals("hold-1", engine.hold("A", "alice", TTL));
    }

    @Test
    void batchDeadlineOverflowDoesNotChangeGenerationRecordsOrSequence() {
        String oldId = engine.hold("A", "alice", TTL);
        clock.advance(TTL);
        assertCode(SeatHoldException.Code.INVALID_ARGUMENT,
                () -> engine.holdMany("bob", List.of("B", "A"), Duration.ofSeconds(Long.MAX_VALUE)));
        assertEquals(SeatStatus.free(), engine.seatStatus("B"));
        engine.confirm(oldId);
        assertEquals("hold-2", engine.hold("B", "alice", TTL));
    }

    @Test
    void returnedBatchMapIsDetachedFromEngineState() {
        var result = engine.holdMany("alice", List.of("A", "B"), TTL);
        String a = result.get("A");
        result.clear();
        result.put("C", "invented-id");
        assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus("A"));
        assertEquals(SeatStatus.held("alice", DEADLINE), engine.seatStatus("B"));
        assertEquals(SeatStatus.free(), engine.seatStatus("C"));
        engine.confirm(a);
    }

    private static void assertCode(SeatHoldException.Code code, Runnable operation) {
        SeatHoldException failure = assertThrows(SeatHoldException.class, operation::run);
        assertEquals(code, failure.code());
    }
}
