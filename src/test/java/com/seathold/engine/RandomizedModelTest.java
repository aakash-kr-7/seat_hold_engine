package com.seathold.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

class RandomizedModelTest {
    private static final long DEFAULT_SEED = 20261013L;
    private static final int OPERATIONS = 600;
    private static final List<String> SEATS = List.of("S1", "S2", "S3", "S4", "S5", "S6");
    private static final List<String> USERS = List.of("alice", "bob", "carol", "dave");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void generatedOperationsMatchIndependentModel() {
        String configured = System.getProperty("seathold.seed", Long.toString(DEFAULT_SEED));
        long seed;
        try {
            seed = Long.parseLong(configured);
        } catch (NumberFormatException exception) {
            throw new AssertionError("Invalid seed=" + configured, exception);
        }
        var random = new Random(seed);
        MutableClock clock = null;
        SeatHoldEngine engine = null;
        ReferenceSeatHoldModel model = null;
        var observedIds = new ArrayList<String>();
        for (int number = 1; number <= OPERATIONS; number++) {
            // Fresh episodes prevent six permanent sales from making the remaining run trivial.
            if ((number - 1) % 60 == 0) {
                clock = new MutableClock(START, ZoneOffset.UTC);
                engine = new SeatHoldEngine(clock);
                model = new ReferenceSeatHoldModel(START);
                observedIds.clear();
            }
            Operation operation = generate(random, model, clock.instant(), observedIds);
            ReferenceSeatHoldModel reference = model;
            SeatHoldEngine production = engine;
            MutableClock productionClock = clock;
            Outcome expected = capture(() -> executeReference(operation, reference));
            Outcome actual = capture(() -> executeProduction(operation, production, productionClock));
            var expectedStates = states(reference::seatStatus);
            var actualStates = states(production::seatStatus);
            if (expected.unexpected != null || actual.unexpected != null
                    || !expected.equals(actual) || !expectedStates.equals(actualStates)
                    || expectedStates.values().stream().anyMatch(value -> value.unexpected != null)
                    || actualStates.values().stream().anyMatch(value -> value.unexpected != null)) {
                fail("seed=" + seed + ", operation=" + number + ", description=" + operation
                        + "\nproduction result=" + actual + "\nreference result=" + expected
                        + "\nproduction seat states=" + actualStates + "\nreference seat states=" + expectedStates);
            }
            if (operation.kind == Kind.HOLD && expected.value instanceof String id) {
                remember(observedIds, id);
            } else if (operation.kind == Kind.BATCH && expected.value instanceof List<?> entries) {
                for (Object entry : entries) {
                    remember(observedIds, (String) ((Map.Entry<?, ?>) entry).getValue());
                }
            }
        }
    }

    private static Operation generate(Random random, ReferenceSeatHoldModel model, Instant now,
                                      List<String> observedIds) {
        Kind kind = Kind.values()[random.nextInt(Kind.values().length)];
        String seat = SEATS.get(random.nextInt(SEATS.size()));
        String user = USERS.get(random.nextInt(USERS.size()));
        Duration duration = Duration.ofMillis(new long[] { 1, 1_000, 5_000, 10_000 }[random.nextInt(4)]);
        var seats = new ArrayList<String>();
        String holdId = observedIds.isEmpty() || random.nextInt(10) == 0
                ? "unknown" : observedIds.get(random.nextInt(observedIds.size()));
        if (kind == Kind.HOLD || kind == Kind.BATCH) {
            SeatStatus status = model.seatStatus(seat);
            if (status.state() == SeatStatus.State.HELD && random.nextBoolean()) {
                user = status.userId();
            }
            if (random.nextInt(10) == 0) {
                duration = Duration.ZERO;
            }
        }
        if (kind == Kind.BATCH) {
            int count = random.nextInt(6);
            for (int index = 0; index < count; index++) {
                seats.add(SEATS.get(random.nextInt(SEATS.size())));
            }
            if (!seats.isEmpty() && random.nextInt(10) == 0) {
                seats.set(seats.size() - 1, " ");
            }
        } else if (kind == Kind.ADVANCE) {
            Instant nearest = null;
            for (String candidate : SEATS) {
                SeatStatus status = model.seatStatus(candidate);
                if (status.state() == SeatStatus.State.HELD
                        && (nearest == null || status.expiresAt().isBefore(nearest))) {
                    nearest = status.expiresAt();
                }
            }
            duration = nearest != null && random.nextBoolean()
                    ? Duration.between(now, nearest) : Duration.ofMillis(random.nextInt(5_001));
        }
        return new Operation(kind, seat, user, seats, duration, holdId);
    }

    private static Object executeReference(Operation op, ReferenceSeatHoldModel model) {
        return switch (op.kind) {
            case HOLD -> model.hold(op.seat, op.user, op.duration);
            case BATCH -> model.holdMany(op.user, op.seats, op.duration);
            case CONFIRM -> { model.confirm(op.holdId); yield "OK"; }
            case RELEASE -> { model.release(op.holdId); yield "OK"; }
            case STATUS -> model.seatStatus(op.seat);
            case ADVANCE -> { model.advance(op.duration); yield "OK"; }
        };
    }

    private static Object executeProduction(Operation op, SeatHoldEngine engine, MutableClock clock) {
        return switch (op.kind) {
            case HOLD -> engine.hold(op.seat, op.user, op.duration);
            case BATCH -> engine.holdMany(op.user, op.seats, op.duration);
            case CONFIRM -> { engine.confirm(op.holdId); yield "OK"; }
            case RELEASE -> { engine.release(op.holdId); yield "OK"; }
            case STATUS -> engine.seatStatus(op.seat);
            case ADVANCE -> { clock.advance(op.duration); yield "OK"; }
        };
    }

    private static Outcome capture(Supplier<Object> operation) {
        try {
            Object value = operation.get();
            if (value instanceof Map<?, ?> map) {
                // Map.equals ignores iteration order, so compare ordered entries instead.
                value = map.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
            }
            return new Outcome(value, null, null);
        } catch (SeatHoldException exception) {
            return new Outcome(null, exception.code(), null);
        } catch (RuntimeException exception) {
            return new Outcome(null, null, exception.toString());
        }
    }

    private static Map<String, Outcome> states(java.util.function.Function<String, SeatStatus> status) {
        var states = new LinkedHashMap<String, Outcome>();
        for (String seat : SEATS) {
            states.put(seat, capture(() -> status.apply(seat)));
        }
        return states;
    }

    private static void remember(List<String> observedIds, String id) {
        if (!observedIds.contains(id)) {
            observedIds.add(id);
        }
    }

    private enum Kind { HOLD, BATCH, CONFIRM, RELEASE, STATUS, ADVANCE }

    private record Outcome(Object value, SeatHoldException.Code error, String unexpected) { }

    private record Operation(Kind kind, String seat, String user, List<String> seats,
                             Duration duration, String holdId) {
        @Override
        public String toString() {
            return switch (kind) {
                case HOLD -> "hold(" + seat + ", " + user + ", " + duration + ")";
                case BATCH -> "holdMany(" + user + ", " + seats + ", " + duration + ")";
                case CONFIRM -> "confirm(" + holdId + ")";
                case RELEASE -> "release(" + holdId + ")";
                case STATUS -> "seatStatus(" + seat + ")";
                case ADVANCE -> "advance(" + duration + ")";
            };
        }
    }
}
