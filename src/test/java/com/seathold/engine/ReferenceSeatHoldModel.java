package com.seathold.engine;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single-threaded oracle using a history list and explicit supersession, not generations. */
final class ReferenceSeatHoldModel {
    private final List<Entry> history = new ArrayList<>();
    private Instant now;

    ReferenceSeatHoldModel(Instant start) {
        now = start;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    String hold(String seat, String user, Duration ttl) {
        return holdMany(user, Collections.singletonList(seat), ttl).get(seat);
    }

    Map<String, String> holdMany(String user, List<String> seats, Duration ttl) {
        validId(user);
        if (ttl == null || ttl.isZero() || ttl.isNegative() || seats == null || seats.isEmpty()) {
            reject(SeatHoldException.Code.INVALID_ARGUMENT);
        }
        var unique = new HashSet<String>();
        for (String seat : seats) {
            validId(seat);
            if (!unique.add(seat)) {
                reject(SeatHoldException.Code.INVALID_ARGUMENT);
            }
        }
        int additions = 0;
        for (String seat : seats) {
            SeatStatus status = seatStatus(seat);
            if (status.state() == SeatStatus.State.SOLD) {
                reject(SeatHoldException.Code.ALREADY_SOLD);
            }
            if (status.state() == SeatStatus.State.HELD) {
                if (!status.userId().equals(user)) {
                    reject(SeatHoldException.Code.SEAT_UNAVAILABLE);
                }
            } else {
                additions++;
            }
        }
        int owned = 0;
        for (Entry entry : history) {
            if (active(entry) && entry.user.equals(user)) {
                owned++;
            }
        }
        if (owned + additions > 4) {
            reject(SeatHoldException.Code.USER_HOLD_LIMIT);
        }
        Instant expiry = null;
        if (additions != 0) {
            try {
                expiry = now.plus(ttl);
            } catch (DateTimeException | ArithmeticException exception) {
                reject(SeatHoldException.Code.INVALID_ARGUMENT);
            }
        }
        var result = new LinkedHashMap<String, String>();
        for (String seat : seats) {
            Entry existing = null;
            for (Entry entry : history) {
                if (entry.seat.equals(seat) && active(entry)) {
                    existing = entry;
                }
            }
            if (existing == null) {
                // Supersession is permanent, even when a later entry is released or expires.
                for (Entry entry : history) {
                    if (entry.seat.equals(seat)) {
                        entry.superseded = true;
                    }
                }
                existing = new Entry("hold-" + (history.size() + 1), seat, user, expiry);
                history.add(existing);
            }
            result.put(seat, existing.id);
        }
        return result;
    }

    void confirm(String id) {
        Entry entry = find(id);
        if (entry.released) {
            reject(SeatHoldException.Code.HOLD_RELEASED);
        }
        if (entry.superseded) {
            reject(SeatHoldException.Code.HOLD_STALE);
        }
        if (entry.sold) {
            reject(SeatHoldException.Code.ALREADY_SOLD);
        }
        entry.sold = true;
    }

    void release(String id) {
        Entry entry = find(id);
        if (entry.released) {
            reject(SeatHoldException.Code.HOLD_RELEASED);
        }
        if (entry.sold) {
            reject(SeatHoldException.Code.ALREADY_SOLD);
        }
        entry.released = true;
    }

    SeatStatus seatStatus(String seat) {
        validId(seat);
        for (Entry entry : history) {
            if (entry.seat.equals(seat)) {
                if (entry.sold) {
                    return SeatStatus.sold(entry.user);
                }
                if (active(entry)) {
                    return SeatStatus.held(entry.user, entry.expiry);
                }
            }
        }
        return SeatStatus.free();
    }

    private boolean active(Entry entry) {
        return !entry.released && !entry.sold && !entry.superseded && entry.expiry.compareTo(now) > 0;
    }

    private Entry find(String id) {
        validId(id);
        for (Entry entry : history) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        throw new SeatHoldException(SeatHoldException.Code.HOLD_NOT_FOUND, "Unknown reference hold");
    }

    private static void validId(String id) {
        if (id == null || id.isBlank()) {
            reject(SeatHoldException.Code.INVALID_ARGUMENT);
        }
    }

    private static void reject(SeatHoldException.Code code) {
        throw new SeatHoldException(code, "Reference rejection");
    }

    private static final class Entry {
        final String id;
        final String seat;
        final String user;
        final Instant expiry;
        boolean released;
        boolean sold;
        boolean superseded;

        Entry(String id, String seat, String user, Instant expiry) {
            this.id = id;
            this.seat = seat;
            this.user = user;
            this.expiry = expiry;
        }
    }
}
