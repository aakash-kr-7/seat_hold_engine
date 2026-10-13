package com.seathold.engine;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MutableClockTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void advancesDeterministicallyAndAllowsZero() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        clock.advance(Duration.ZERO);
        assertEquals(START, clock.instant());
        clock.advance(Duration.ofMillis(1_250));
        assertEquals(START.plusMillis(1_250), clock.instant());
        assertEquals(START.toEpochMilli() + 1_250, clock.millis());
    }

    @Test
    void zoneViewsShareTimeInBothDirections() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        Clock view = clock.withZone(ZoneId.of("Asia/Kolkata"));
        assertEquals(ZoneOffset.UTC, clock.getZone());
        assertEquals(ZoneId.of("Asia/Kolkata"), view.getZone());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(clock.instant(), view.instant());
        ((MutableClock) view).advance(Duration.ofSeconds(2));
        assertEquals(START.plusSeconds(3), clock.instant());
    }

    @Test
    void rejectsBackwardsAndSubMillisecondAdvancesWithoutChangingTime() {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofNanos(1)));
        assertEquals(START, clock.instant());
        assertThrows(IllegalArgumentException.class,
                () -> new MutableClock(START.plusNanos(1), ZoneOffset.UTC));
    }

    @Test
    void overflowDoesNotWrapTime() {
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(Long.MAX_VALUE), ZoneOffset.UTC);
        assertThrows(ArithmeticException.class, () -> clock.advance(Duration.ofMillis(1)));
        assertEquals(Long.MAX_VALUE, clock.millis());
    }

    @Test
    void concurrentAdvancesDoNotLoseUpdates() throws Exception {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int thread = 0; thread < 4; thread++) {
                futures.add(executor.submit(() -> {
                    for (int step = 0; step < 1_000; step++) {
                        clock.advance(Duration.ofMillis(1));
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertEquals(START.plusMillis(4_000), clock.instant());
        } finally {
            executor.shutdownNow();
        }
    }
}
