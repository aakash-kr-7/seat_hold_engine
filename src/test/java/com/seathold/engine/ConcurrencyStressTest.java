package com.seathold.engine;

import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyStressTest {
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
