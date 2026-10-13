# Seat Hold Engine

A small Java 17 library for concurrency-safe ticket seat holds. It keeps state
in memory, uses one lock for atomic operations, and has no production dependencies.

## Problem

Seats are temporarily held while payment occurs. Concurrent callers must never
double-allocate or double-sell a seat. Holds expire, and acquiring several seats
must either succeed completely or leave state unchanged.

## Design

### Single transaction boundary

One engine-wide `ReentrantLock` protects seat state, the user's four-hold limit,
hold history and generations, and multi-seat transactions. This is a deliberate
correctness-first choice for a small in-memory library. It offers lower write
parallelism than fine-grained locking, with simpler correctness reasoning and no
lock-order deadlocks. Every state operation releases the lock in `finally`.

### Time

`java.time.Clock` is injected through the constructor. Each operation reads it
once after acquiring the lock. Tests use the thread-safe `MutableClock` and never
sleep. Expiry is lazy: `now >= expiresAt` makes a hold logically expired when
state is inspected; there is no expiry worker.

### Stale hold protection

Each new acquisition advances that seat's generation. A later successful hold
permanently invalidates an older hold's ability to confirm, even if the newer
hold is released afterward. History is retained so a free seat cannot conceal
that intervening acquisition.

### Atomic holdMany

`holdMany` validates the entire request, checks availability and capacity, and
stages new records before mutating state, all within one critical section. It
behaves like a small in-memory transaction. Failed validation consumes no IDs or
generations. The detached result map follows requested seat order.

## Rule interpretations

- Retrying a hold never extends its TTL.
- A same-user retry is idempotent only while the original hold is active and
  unexpired; after expiry it creates a new ID and generation.
- An expired hold may late-confirm if it was not released, nobody acquired the
  seat afterward, and the seat has not been sold.
- Any later acquisition permanently makes the older hold stale.
- Existing active same-user holds in `holdMany` count once toward the four-seat
  limit; only genuinely new acquisitions add slots.

Repeated confirmation or release is an error. An unseen seat is FREE; an unknown
hold ID produces `HOLD_NOT_FOUND`. See [DECISIONS.md](DECISIONS.md) for the complete
choices and error precedence.

## Project structure

```text
.gitignore
pom.xml
README.md
DECISIONS.md
AI_USAGE.md
BUGS_I_FEAR.md
src/main/java/com/seathold/engine/
  SeatHoldEngine.java
  SeatStatus.java
  Hold.java
  SeatHoldException.java
src/test/java/com/seathold/engine/
  MutableClock.java
  MutableClockTest.java
  SeatHoldEngineTest.java
  ReferenceSeatHoldModel.java
  RandomizedModelTest.java
  ConcurrencyStressTest.java
```

## Requirements

- JDK 17 or later
- Maven

## Running tests

From the repository root:

```sh
mvn test
```

For a fresh compilation:

```sh
mvn clean test
```

## Example

```java
import com.seathold.engine.SeatHoldEngine;
import java.time.Clock;
import java.time.Duration;

Clock clock = Clock.systemUTC();
SeatHoldEngine engine = new SeatHoldEngine(clock);
String holdId = engine.hold("A1", "alice", Duration.ofMinutes(2));
engine.confirm(holdId);
```

These statements can be used inside a method. `SeatHoldException.code()` exposes
semantic failures without requiring callers to parse messages.

## Testing strategy

1. Deterministic unit and edge tests cover expiry boundaries, retries, release,
   sales, capacity, stale generations, and batch rollback.
2. Seeded reference-model testing compares 600 operations over six seats and four
   users with an independent, single-threaded history-list model. It compares
   outcomes and every seat's status after each operation. The default seed is
   `20261013`; fresh episodes prevent permanent sales from exhausting the pool.
3. Real multithreaded stress testing uses ten gated workers, a shared atomic clock,
   and 4,800 operations across six rounds. It checks bounded completion, sale
   counts, capacity, status validity, and confirmed generations. A separate
   opposite-order batch test checks that exactly one whole batch wins.

Randomized and stress failures include their seeds. Randomized failures also show
the operation, both outcomes, and all seat states. Stress assertions do not assume
a reproducible thread schedule. The package-private history snapshot used for
generation checks is immutable and is not a public debugging API.

## What is implemented

- At most one active hold per seat, and a permanent sale that cannot occur twice.
- At most four active, unexpired holds per user; expired holds consume no capacity.
- Late confirmation after expiry when there was no release, newer acquisition,
  or sale; permanent rejection of stale holds.
- Same-user active retries return the original ID and preserve expiry; reacquisition
  after expiry creates a new hold.
- Explicit release permanently invalidates that hold and never frees a newer
  owner's hold or reverses a sale.
- All-or-nothing batch holds with duplicate/input validation, ordered results,
  and correct accounting for existing same-user holds.

## Deliberate scope boundaries

This is in-memory only, within a single JVM process and engine instance. It has
no database, distributed synchronization, REST/API layer, background expiry
worker, or persistence across process restart. Hold history grows with new
acquisitions, and capacity checks scan current seat records. Hold IDs are local
to an engine instance, not globally unique.

Wall-clock jumps remain an unresolved production risk; see
[BUGS_I_FEAR.md](BUGS_I_FEAR.md). AI assistance is disclosed in
[AI_USAGE.md](AI_USAGE.md).

## Production evolution

Possible next steps are durable transactional storage, version/compare-and-swap
seat updates, idempotency keys at an API boundary, and observability around
contention and stale confirmations. Finer-grained synchronization should follow
profiling that demonstrates a need. These are future directions, not implemented
features.
