# Seat hold semantics

These decisions are recorded before implementation for the 60-minute assessment.

## Synchronization

Use one engine-wide `threading.RLock`. This deliberately prioritizes correctness
and a simple atomicity boundary over maximum parallel throughput. Each operation
checks and changes state while holding this lock. In particular, `hold_many` is
atomic, and using one lock avoids lock-order deadlocks.

## Time and expiry

Expiry is lazy: operations account for expired holds when inspecting relevant
state. There is no background worker. Time comes only from an injected `Clock`,
so tests can advance time without sleeping. The clock supplies nondecreasing time
in seconds; production time should use a monotonic source. A hold is expired when
`now >= expires_at`. TTL must be positive.

## Repeated holds

Repeating `hold()` for an active seat already held by the same user returns the
existing `hold_id`. It MUST NOT extend expiry, even if the repeated request has a
different positive TTL. A seat actively held by another user is unavailable.

After expiry, `hold()` creates a new hold with a new ID and deadline. This is a
new hold, not an extension of the expired one.

## Confirmation and stale holds

An expired hold may confirm only if the seat has not transitioned to any newer
hold or sale since that hold expired. A later hold permanently invalidates the
stale hold, even if the later hold is subsequently released or expires. Merely
observing lazy expiry does not invalidate this confirmation eligibility.

This requires retaining enough hold history or seat-version information to
distinguish expiry from supersession; checking only whether a seat is currently
free is insufficient. A released hold cannot confirm. A successful confirmation
sells the seat, which cannot be held again within this engine.

## Batch holds and the seat limit

`hold_many` is all-or-nothing: validate the whole request and check availability
and capacity before creating any new holds. Failure creates no partial batch.
Lazy expiry still applies when evaluating the request.

Interpret the four-seat limit as a maximum of four active held seats per user
across the engine. Expired, released, and sold seats do not consume hold capacity.
Seats already actively held by the requesting user count once toward that total;
including them in `hold_many` does not consume another slot. Return their existing
IDs without extending their deadlines.

Duplicate seat IDs in `hold_many` are invalid input, even when they refer to an
idempotently held seat. An empty batch is invalid input. Use one clock reading for
each operation, so all new holds in a batch share the same deadline. Batch results
follow the input seat order.

## Scope

Use Python 3.12+, pytest, and the standard library wherever possible. Keep state
in memory. Do not add an HTTP API, database, Redis, Docker, background workers,
or unnecessary abstraction layers. No CLI is needed for this assessment.

Concrete result types and error names will be defined with the implementation;
this skeleton does not claim that an API or tests already exist.
