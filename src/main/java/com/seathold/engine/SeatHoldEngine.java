package com.seathold.engine;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class SeatHoldEngine {
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Hold> holds = new HashMap<>();
    private final Map<String, String> latestHoldBySeat = new HashMap<>();
    private long holdSequence;

    public SeatHoldEngine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String hold(String seatId, String userId, Duration ttl) {
        lock.lock();
        try {
            Instant now = clock.instant();
            requireId(seatId, "seatId");
            requireId(userId, "userId");
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                throw failure(SeatHoldException.Code.INVALID_ARGUMENT, "TTL must be positive");
            }
            Hold latest = latestHold(seatId);
            if (latest != null && latest.lifecycle() == Hold.Lifecycle.CONFIRMED) {
                throw failure(SeatHoldException.Code.ALREADY_SOLD, "Seat is sold");
            }
            if (isActive(latest, now)) {
                if (latest.userId().equals(userId)) {
                    return latest.holdId();
                }
                throw failure(SeatHoldException.Code.SEAT_UNAVAILABLE, "Seat is held by another user");
            }
            long activeCount = latestHoldBySeat.values().stream()
                    .map(holds::get)
                    .filter(hold -> isActive(hold, now) && hold.userId().equals(userId))
                    .count();
            if (activeCount >= 4) {
                throw failure(SeatHoldException.Code.USER_HOLD_LIMIT, "User already holds four seats");
            }
            Instant expiresAt;
            try {
                expiresAt = now.plus(ttl);
            } catch (DateTimeException | ArithmeticException exception) {
                throw failure(SeatHoldException.Code.INVALID_ARGUMENT, "TTL exceeds the supported time range");
            }
            long sequence = Math.incrementExact(holdSequence);
            long generation = latest == null ? 1 : Math.incrementExact(latest.generation());
            Hold hold = new Hold("hold-" + sequence, seatId, userId, expiresAt,
                    generation, Hold.Lifecycle.ACTIVE);
            holds.put(hold.holdId(), hold);
            latestHoldBySeat.put(seatId, hold.holdId());
            holdSequence = sequence;
            return hold.holdId();
        } finally {
            lock.unlock();
        }
    }

    public void confirm(String holdId) {
        lock.lock();
        try {
            Instant now = clock.instant();
            Hold hold = requireHold(holdId);
            if (hold.lifecycle() == Hold.Lifecycle.RELEASED) {
                throw failure(SeatHoldException.Code.HOLD_RELEASED, "Hold was released");
            }
            // The latest generation survives release and expiry, permanently fencing older holds.
            if (latestHold(hold.seatId()).generation() != hold.generation()) {
                throw failure(SeatHoldException.Code.HOLD_STALE, "A newer hold took this seat");
            }
            if (hold.lifecycle() == Hold.Lifecycle.CONFIRMED) {
                throw failure(SeatHoldException.Code.ALREADY_SOLD, "Seat is already sold");
            }
            // Expiry alone does not revoke confirmation eligibility.
            setLifecycle(hold, Hold.Lifecycle.CONFIRMED);
        } finally {
            lock.unlock();
        }
    }

    public void release(String holdId) {
        lock.lock();
        try {
            Instant now = clock.instant();
            Hold hold = requireHold(holdId);
            if (hold.lifecycle() == Hold.Lifecycle.CONFIRMED) {
                throw failure(SeatHoldException.Code.ALREADY_SOLD, "A sale cannot be released");
            }
            // Change only this hold's history; never remove the latest seat-generation index.
            setLifecycle(hold, Hold.Lifecycle.RELEASED);
        } finally {
            lock.unlock();
        }
    }

    public SeatStatus seatStatus(String seatId) {
        lock.lock();
        try {
            Instant now = clock.instant();
            requireId(seatId, "seatId");
            Hold latest = latestHold(seatId);
            if (latest != null && latest.lifecycle() == Hold.Lifecycle.CONFIRMED) {
                return SeatStatus.sold(latest.userId());
            }
            return isActive(latest, now)
                    ? SeatStatus.held(latest.userId(), latest.expiresAt())
                    : SeatStatus.free();
        } finally {
            lock.unlock();
        }
    }

    private Hold latestHold(String seatId) {
        return holds.get(latestHoldBySeat.get(seatId));
    }

    private static boolean isActive(Hold hold, Instant now) {
        return hold != null && hold.lifecycle() == Hold.Lifecycle.ACTIVE
                && now.isBefore(hold.expiresAt());
    }

    private Hold requireHold(String holdId) {
        requireId(holdId, "holdId");
        Hold hold = holds.get(holdId);
        if (hold == null) {
            throw failure(SeatHoldException.Code.HOLD_NOT_FOUND, "Unknown hold ID");
        }
        return hold;
    }

    private void setLifecycle(Hold hold, Hold.Lifecycle lifecycle) {
        holds.put(hold.holdId(), new Hold(hold.holdId(), hold.seatId(), hold.userId(),
                hold.expiresAt(), hold.generation(), lifecycle));
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw failure(SeatHoldException.Code.INVALID_ARGUMENT, name + " must not be blank");
        }
    }

    private static SeatHoldException failure(SeatHoldException.Code code, String message) {
        return new SeatHoldException(code, message);
    }
}
