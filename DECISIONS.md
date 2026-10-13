# Seat hold semantics

These decisions are recorded before implementation for the 60-minute assessment.

## Synchronization

Use one engine-wide `java.util.concurrent.locks.ReentrantLock`. This deliberately
prioritizes correctness and a simple atomicity boundary over maximum parallel
throughput. Each operation
checks and changes state while holding this lock. In particular, `holdMany` is
atomic: validation and commit share the same lock. Using one lock eliminates
lock-order deadlocks between overlapping batches. Locking is deliberately
coarse-grained.

## Time and expiry

Expiry is lazy: operations account for expired holds when inspecting relevant
state. There is no background worker. Time comes only from a `java.time.Clock`
injected through the `SeatHoldEngine` constructor; no production clock interface
is needed. Operations read one logical `now` while holding the engine lock.
Deadlines are `Instant` values and TTLs are `Duration` values. A hold is expired
when `currentTime >= expiresAt`. TTL must be positive.

Tests use a thread-safe `MutableClock` with atomic epoch milliseconds and
`advance(Duration)`, without sleeping. This test helper accepts whole-millisecond
times and nonnegative advances; sub-millisecond inputs are rejected rather than
silently rounded. Java's system clock can move backwards; the engine uses the
injected clock's observed time without inventing a second time source.

## Repeated holds

Repeating `hold()` for an active seat already held by the same user returns the
original hold ID. It MUST NOT extend expiry, even if the repeated request has a
different positive TTL. A seat actively held by another user is unavailable.

After expiry, `hold()` creates a new hold with a new ID and deadline. This is a
new hold, not an extension of the expired one.

## Confirmation and stale holds

An expired hold may confirm only if no newer hold generation has ever taken that
seat. A later successful hold permanently invalidates the stale hold, even if the
later hold is subsequently released or expires. Merely
observing lazy expiry does not invalidate this confirmation eligibility.

This requires retaining enough hold history or seat-version information to
distinguish expiry from supersession; checking only whether a seat is currently
free is insufficient. Explicit release invalidates that hold permanently.
A successful confirmation sells the seat, which cannot be held again within
this engine.

Repeating release fails with `HOLD_RELEASED`, even if that hold is now stale.
There is no release idempotency. Releasing an expired but unreleased hold succeeds
and permanently removes its late-confirmation eligibility.
Releasing a confirmed hold fails with
`ALREADY_SOLD`; it never reverses a sale. Releasing a stale hold marks only that
hold as released and cannot change the newer seat owner. Confirmation checks
explicit release before staleness, and staleness before an already-confirmed
sale, so overlapping failure conditions have a deterministic error code.

Confirming an already confirmed hold fails with `ALREADY_SOLD`. A valid but
unknown hold ID fails with `HOLD_NOT_FOUND` for both confirm and release. An
unseen valid seat ID has status FREE. Failed operations leave seat state intact.
Null or blank IDs remain invalid input rather than unknown IDs.

The engine stores immutable hold records and an index of each seat's latest hold
ID. The latest record carries that seat's generation; the index is retained even
after release or expiry. Counts and seat status are derived from those records
under the lock. Hold history is retained for this in-memory assessment rather
than purged in a way that loses late-confirmation or stale-hold information.

## Batch holds and the seat limit

`holdMany` is all-or-nothing: validate the whole request and check availability
and capacity before creating any new holds. Failure creates no partial batch.
Lazy expiry still applies when evaluating the request.

Interpret the four-seat limit as a maximum of four active held seats per user
across the engine. Expired, released, and sold seats do not consume hold capacity.
Seats already actively held by the requesting user count once toward that total;
including them in `holdMany` does not consume another slot. Return their existing
IDs without extending their deadlines.

Duplicate seat IDs in `holdMany` are invalid input, even when they refer to an
idempotently held seat. An empty batch is invalid input. Use one clock reading for
each operation, so all new holds in a batch share the same deadline. Batch results
follow the input seat order.

Batch validation allocates no hold IDs and changes no engine state. After input,
availability, capacity, and deadline checks pass, new records are staged locally
before publishing them under the same lock. Failed requests retain ownership,
hold history, generations, and the next ID sequence. The returned `LinkedHashMap`
is detached from engine state and follows requested seat order.

## Scope

Use Java 17, Maven, JUnit 5, and almost entirely the Java standard library. There
are no production dependencies. Keep state in memory. Do not add Spring Boot,
REST APIs, databases, Redis, Docker, Lombok, dependency injection frameworks,
background workers, or unnecessary abstraction layers. No CLI is needed.

Hold IDs are opaque to callers. An engine-local sequence such as `hold-1`,
`hold-2`, ... is sufficient here and supports deterministic tests. It is not a
globally unique production ID scheme.

`SeatStatus` is an immutable record with FREE, HELD, and SOLD states. `Hold` keeps
its ID, seat, user, deadline, generation, and ACTIVE/RELEASED/CONFIRMED lifecycle.
ACTIVE means not explicitly released or confirmed; whether it is unexpired must
still be checked against time. Expiry and staleness are derived, not extra stored
lifecycle states. Failures use a small `SeatHoldException.Code` enum.

Single-seat lifecycle operations, atomic batch holds, and focused tests are
implemented. Reversed overlapping batches have a coordinated contention test.
Model-based verification compares 600 generated operations over six seats and
four users against a single-threaded history-list model. That model uses explicit
supersession flags and its own logical time, without engine calls or generation
indexes. Each operation compares results/error codes and all six seat statuses;
batch result ordering is compared too.

The default seed is `20261013`; replay with `mvn test -Dseathold.seed=<seed>`.
Ten fresh 60-operation episodes keep permanent sales from exhausting the small
seat pool early. Failures include seed, global operation number, description,
both outcomes, and both sets of seat states.

Concurrent invariant verification uses ten gated workers with derived random
seeds and a shared atomic test clock. Seeded choices do not fix thread scheduling.
A package-private `holdHistorySnapshot()` returns an immutable list of immutable
hold records under the engine lock. This lets tests check that every confirmed
hold belongs to its seat's highest generation; concurrent API return order alone
cannot reliably establish acquisition order. There is no public diagnostic API.
Six fresh rounds each run 80 operations per worker, for 4,800 random operations.
Each round plants an expired hold that was superseded by a later, released hold;
all workers check its rejection before random work begins. Final checks compare
successful confirmation counts with history and seat status, forbid confirmed
older generations, and verify final active capacity and structurally valid
statuses. Worker failures propagate through bounded futures; expected semantic
rejections are checked against the operation's allowed codes. No sleep is used.
