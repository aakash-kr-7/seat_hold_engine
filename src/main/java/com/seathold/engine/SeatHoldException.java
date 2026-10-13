package com.seathold.engine;

import java.util.Objects;

/** Semantic operation failure; callers can inspect the code without parsing text. */
public final class SeatHoldException extends RuntimeException {
    public enum Code {
        INVALID_ARGUMENT,
        SEAT_UNAVAILABLE,
        USER_HOLD_LIMIT,
        HOLD_NOT_FOUND,
        HOLD_STALE,
        HOLD_RELEASED,
        ALREADY_SOLD
    }

    private final Code code;

    public SeatHoldException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
