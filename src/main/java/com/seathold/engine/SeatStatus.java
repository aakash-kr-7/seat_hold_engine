package com.seathold.engine;

import java.time.Instant;
import java.util.Objects;

/** Immutable public view of a seat. Only HELD seats have an expiry. */
public record SeatStatus(State state, String userId, Instant expiresAt) {
    public enum State { FREE, HELD, SOLD }

    public SeatStatus {
        Objects.requireNonNull(state, "state");
        switch (state) {
            case FREE -> {
                if (userId != null || expiresAt != null) {
                    throw new IllegalArgumentException("A free seat has no user or expiry");
                }
            }
            case HELD -> {
                Objects.requireNonNull(userId, "userId");
                Objects.requireNonNull(expiresAt, "expiresAt");
            }
            case SOLD -> {
                Objects.requireNonNull(userId, "userId");
                if (expiresAt != null) {
                    throw new IllegalArgumentException("A sold seat has no expiry");
                }
            }
        }
    }

    public static SeatStatus free() {
        return new SeatStatus(State.FREE, null, null);
    }

    public static SeatStatus held(String userId, Instant expiresAt) {
        return new SeatStatus(State.HELD, userId, expiresAt);
    }

    public static SeatStatus sold(String userId) {
        return new SeatStatus(State.SOLD, userId, null);
    }
}
