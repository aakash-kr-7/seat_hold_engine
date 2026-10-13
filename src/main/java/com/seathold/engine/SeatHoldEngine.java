package com.seathold.engine;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Foundation only; seat operations will be implemented in the next step. */
public final class SeatHoldEngine {
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public SeatHoldEngine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }
}
