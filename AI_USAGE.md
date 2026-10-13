# AI usage

AI/Codex is being used for implementation assistance, test generation and review,
and documentation refinement. Architecture and specification decisions are being
reviewed by the candidate, who remains responsible for understanding and
explaining the submitted work.

Codex prepared the initial repository skeleton and decision notes, then migrated
the scaffold to Java 17 and Maven following the candidate's stack change. It has
added domain values, a test clock, and focused foundation tests. Codex then
implemented the single-seat lifecycle, generated clock-driven lifecycle tests,
and reviewed state ownership, generation checks, locking, and expiry behavior.
Codex also implemented atomic batch acquisition and added rollback, idempotency,
capacity, and coordinated overlapping-batch contention tests. Model-based tests
and broader engine concurrency stress tests have not been implemented yet.
