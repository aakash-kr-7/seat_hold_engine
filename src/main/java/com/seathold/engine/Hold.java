package com.seathold.engine;

import java.time.Instant;
import java.util.Objects;

/** Immutable hold snapshot. Expiry is derived from time, not a stored state. */
public record Hold(String holdId, String seatId, String userId, Instant expiresAt,
                   long generation, Lifecycle lifecycle) {
    public enum Lifecycle { ACTIVE, RELEASED, CONFIRMED }

    public Hold {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (generation <= 0) {
            throw new IllegalArgumentException("Hold generation must be positive");
        }
    }
}
