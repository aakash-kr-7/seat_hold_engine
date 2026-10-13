package com.seathold.engine;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyStressTest {
    private static final long BASE_SEED = 20261013L;
    private static final int WORKERS = 10;
    private static final int ROUNDS = 6;
    private static final int STEPS = 80;
    private static final List<String> SEATS = List.of("S1", "S2", "S3", "S4", "S5", "S6");
    private static final List<String> USERS = List.of("alice", "bob", "carol", "dave");

    @Test
    void concurrentWorkloadPreservesLifecycleInvariants() throws Exception {
        String context = "base seed=" + BASE_SEED;
        var executor = Executors.newFixedThreadPool(WORKERS, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        var completed = new AtomicInteger();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
                var engine = new SeatHoldEngine(clock);
                var known = new ConcurrentHashMap<String, Owner>();
                var sales = new ConcurrentHashMap<String, AtomicInteger>();
                String old = engine.hold("S1", "alice", Duration.ofMillis(1));
                remember(known, old, "S1", "alice", context);
                clock.advance(Duration.ofMillis(1));
                String newer = engine.hold("S1", "bob", Duration.ofMillis(1));
                remember(known, newer, "S1", "bob", context);
                engine.release(newer);
                var ready = new CountDownLatch(WORKERS);
                var start = new CountDownLatch(1);
                var probesFinished = new CountDownLatch(WORKERS);
                var futures = new ArrayList<Future<?>>();
                for (int worker = 0; worker < WORKERS; worker++) {
                    long seed = BASE_SEED + 1_000_003L * round + 97L * worker;
                    String workerContext = context + ", round=" + round + ", worker=" + worker;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS), workerContext + ": start gate timed out");
                        var stale = assertThrows(SeatHoldException.class, () -> engine.confirm(old), workerContext);
                        assertEquals(SeatHoldException.Code.HOLD_STALE, stale.code(), workerContext);
                        probesFinished.countDown();
                        assertTrue(probesFinished.await(5, TimeUnit.SECONDS),
                                workerContext + ": stale probes did not finish");
                        var random = new Random(seed);
                        for (int step = 0; step < STEPS; step++) {
                            runOperation(engine, clock, known, sales, random, workerContext + ", step=" + step);
                            completed.incrementAndGet();
                        }
                        return null;
                    }));
                }
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS), context + ": workers did not reach gate");
                    start.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    for (Future<?> future : futures) {
                        long remaining = deadline - System.nanoTime();
                        assertTrue(remaining > 0, context + ": worker deadline exceeded");
                        future.get(remaining, TimeUnit.NANOSECONDS);
                    }
                } catch (ExecutionException | TimeoutException exception) {
                    throw new AssertionError(context + ": worker failed or did not terminate", exception);
                } finally {
                    start.countDown();
                }
                assertFinalInvariants(engine, clock.instant(), known, sales, context + ", round=" + round);
            }
            assertEquals(WORKERS * ROUNDS * STEPS, completed.get(), context + ": incomplete workload");
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), context + ": executor did not terminate");
        }
    }

    private static void runOperation(SeatHoldEngine engine, MutableClock clock,
                                     ConcurrentHashMap<String, Owner> known,
                                     ConcurrentHashMap<String, AtomicInteger> sales,
                                     Random random, String context) {
        int operation = random.nextInt(20);
        String seat = SEATS.get(random.nextInt(SEATS.size()));
        String user = USERS.get(random.nextInt(USERS.size()));
        Duration ttl = Duration.ofMillis(1 + random.nextInt(10));
        try {
            if (operation < 6) {
                remember(known, engine.hold(seat, user, ttl), seat, user, context);
            } else if (operation < 10) {
                var requested = new ArrayList<>(SEATS);
                Collections.shuffle(requested, random);
                var result = engine.holdMany(user, requested.subList(0, 1 + random.nextInt(3)), ttl);
                result.forEach((heldSeat, id) -> remember(known, id, heldSeat, user, context));
            } else if (operation < 15) {
                var ids = new ArrayList<>(known.keySet());
                Collections.sort(ids);
                String id = ids.get(random.nextInt(ids.size()));
                if (operation == 14) {
                    engine.confirm(id);
                    sales.computeIfAbsent(known.get(id).seat(), ignored -> new AtomicInteger()).incrementAndGet();
                } else {
                    engine.release(id);
                }
            } else if (operation < 18) {
                assertStructure(engine.seatStatus(seat), null, context);
            } else {
                clock.advance(Duration.ofMillis(random.nextInt(4)));
            }
        } catch (SeatHoldException exception) {
            EnumSet<SeatHoldException.Code> allowed = operation < 10
                    ? EnumSet.of(SeatHoldException.Code.SEAT_UNAVAILABLE, SeatHoldException.Code.USER_HOLD_LIMIT,
                            SeatHoldException.Code.ALREADY_SOLD)
                    : operation == 14
                    ? EnumSet.of(SeatHoldException.Code.HOLD_RELEASED, SeatHoldException.Code.HOLD_STALE,
                            SeatHoldException.Code.ALREADY_SOLD)
                    : EnumSet.of(SeatHoldException.Code.HOLD_RELEASED, SeatHoldException.Code.ALREADY_SOLD);
            assertTrue(operation < 15 && allowed.contains(exception.code()),
                    context + ", operation=" + operation + ": unexpected semantic error " + exception.code());
        } catch (RuntimeException exception) {
            throw new AssertionError(context + ", operation=" + operation + ": unexpected worker exception", exception);
        }
    }

    private static void remember(ConcurrentHashMap<String, Owner> known, String id, String seat,
                                 String user, String context) {
        assertNotNull(id, context);
        assertFalse(id.isBlank(), context);
        Owner owner = new Owner(seat, user);
        Owner previous = known.putIfAbsent(id, owner);
        assertTrue(previous == null || previous.equals(owner), context + ": hold ID reused for another owner");
    }

    private static void assertFinalInvariants(SeatHoldEngine engine, Instant now,
                                              Map<String, Owner> known, Map<String, AtomicInteger> sales,
                                              String context) {
        List<Hold> history = engine.holdHistorySnapshot();
        assertThrows(UnsupportedOperationException.class, history::clear, context + ": mutable snapshot");
        assertEquals(known.keySet(), history.stream().map(Hold::holdId).collect(java.util.stream.Collectors.toSet()),
                context + ": returned IDs and engine history differ");
        var highest = new HashMap<String, Long>();
        for (Hold hold : history) {
            highest.merge(hold.seatId(), hold.generation(), Math::max);
            assertEquals(new Owner(hold.seatId(), hold.userId()), known.get(hold.holdId()), context);
        }
        var activeByUser = new HashMap<String, Integer>();
        for (String seat : SEATS) {
            SeatStatus status = engine.seatStatus(seat);
            assertStructure(status, now, context + ", seat=" + seat);
            var confirmed = history.stream().filter(hold -> hold.seatId().equals(seat)
                    && hold.lifecycle() == Hold.Lifecycle.CONFIRMED).toList();
            var active = history.stream().filter(hold -> hold.seatId().equals(seat)
                    && hold.generation() == highest.get(seat)
                    && hold.lifecycle() == Hold.Lifecycle.ACTIVE && now.isBefore(hold.expiresAt())).toList();
            int successfulSales = sales.containsKey(seat) ? sales.get(seat).get() : 0;
            assertTrue(successfulSales <= 1, context + ": multiple successful sales of " + seat);
            assertEquals(successfulSales, confirmed.size(), context + ": sale history mismatch for " + seat);
            assertTrue(active.size() <= 1, context + ": multiple current holds for " + seat);
            for (Hold hold : confirmed) {
                assertEquals(highest.get(seat).longValue(), hold.generation(),
                        context + ": stale generation confirmed for " + seat);
            }
            if (!confirmed.isEmpty()) {
                assertTrue(active.isEmpty(), context + ": sold seat also held: " + seat);
                assertEquals(SeatStatus.sold(confirmed.get(0).userId()), status, context);
            } else if (!active.isEmpty()) {
                assertEquals(SeatStatus.held(active.get(0).userId(), active.get(0).expiresAt()), status, context);
            } else {
                assertEquals(SeatStatus.free(), status, context);
            }
            if (status.state() == SeatStatus.State.HELD) {
                activeByUser.merge(status.userId(), 1, Integer::sum);
            }
        }
        activeByUser.forEach((user, count) -> assertTrue(count <= 4, context + ": excess holds for " + user));
    }

    private static void assertStructure(SeatStatus status, Instant now, String context) {
        assertNotNull(status, context);
        assertNotNull(status.state(), context);
        switch (status.state()) {
            case FREE -> {
                assertNull(status.userId(), context);
                assertNull(status.expiresAt(), context);
            }
            case HELD -> {
                assertTrue(USERS.contains(status.userId()), context);
                assertNotNull(status.expiresAt(), context);
                if (now != null) {
                    assertTrue(now.isBefore(status.expiresAt()), context + ": expired final hold");
                }
            }
            case SOLD -> {
                assertTrue(USERS.contains(status.userId()), context);
                assertNull(status.expiresAt(), context);
            }
        }
    }

    private record Owner(String seat, String user) { }

    @RepeatedTest(10)
    void reversedOverlappingBatchesHaveOneCompleteWinner() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var engine = new SeatHoldEngine(clock);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        try {
            var alice = executor.submit(() -> acquire(engine, "alice", List.of("S1", "S2"), ready, start));
            var bob = executor.submit(() -> acquire(engine, "bob", List.of("S2", "S1"), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both workers must reach the starting gate");
            start.countDown();
            Map<String, String> aliceResult = alice.get(5, TimeUnit.SECONDS);
            Map<String, String> bobResult = bob.get(5, TimeUnit.SECONDS);
            assertTrue((aliceResult == null) != (bobResult == null), "Exactly one full batch must succeed");
            String winner = aliceResult != null ? "alice" : "bob";
            var result = aliceResult != null ? aliceResult : bobResult;
            assertEquals(aliceResult != null ? List.of("S1", "S2") : List.of("S2", "S1"),
                    new ArrayList<>(result.keySet()));
            assertEquals(2, result.size());
            assertNotEquals(result.get("S1"), result.get("S2"));
            for (String seat : List.of("S1", "S2")) {
                assertEquals(SeatStatus.held(winner, clock.instant().plusSeconds(60)), engine.seatStatus(seat));
                engine.confirm(result.get(seat));
                assertEquals(SeatStatus.sold(winner), engine.seatStatus(seat));
            }
            assertEquals("hold-3", engine.hold("S3", "charlie", Duration.ofMinutes(1)));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Workers must terminate");
        }
    }

    private static Map<String, String> acquire(SeatHoldEngine engine, String user, List<String> seats,
                                             CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS), "Start gate must open");
        try {
            return engine.holdMany(user, seats, Duration.ofMinutes(1));
        } catch (SeatHoldException failure) {
            assertEquals(SeatHoldException.Code.SEAT_UNAVAILABLE, failure.code());
            return null;
        }
    }
}
