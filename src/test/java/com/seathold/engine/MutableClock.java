package com.seathold.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe test clock with whole-millisecond precision. Zone views share time. */
public final class MutableClock extends Clock {
    private final AtomicLong epochMillis;
    private final ZoneId zone;

    public MutableClock(Instant initialTime, ZoneId zone) {
        this(new AtomicLong(Objects.requireNonNull(initialTime, "initialTime").toEpochMilli()), zone);
        if (!initialTime.equals(Instant.ofEpochMilli(epochMillis.get()))) {
            throw new IllegalArgumentException("Initial time must use whole milliseconds");
        }
    }

    private MutableClock(AtomicLong epochMillis, ZoneId zone) {
        this.epochMillis = epochMillis;
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Cannot advance time backwards");
        }
        long millis = duration.toMillis();
        if (!duration.equals(Duration.ofMillis(millis))) {
            throw new IllegalArgumentException("Advance must use whole milliseconds");
        }
        epochMillis.updateAndGet(current -> Math.addExact(current, millis));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(epochMillis, zone);
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(epochMillis.get());
    }

    @Override
    public long millis() {
        return epochMillis.get();
    }
}
