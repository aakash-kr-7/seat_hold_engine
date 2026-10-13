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
capacity, and coordinated overlapping-batch contention tests.

Codex performed a focused correctness pass across the decisions, engine, and
existing tests, added lifecycle boundary checks, and changed repeated release to
an explicit error after recording that decision. The pass covered expiry,
multi-generation staleness, partial expiry at capacity, and batch rollback.

Codex added an independent reference model and a seeded randomized comparison
test. It tried the default seed and four alternate seeds, then verified that a
temporary expiry-boundary defect was detected at operation 41 with replay
diagnostics. The temporary defect was restored before the full suite was run;
this experiment is evidence about the test, not a bug found in the submitted
engine.

Codex added a concurrent workload with ten workers, six fresh rounds, and 4,800
random operations. It checks bounded completion, sale counts, final seat shape
and capacity, and confirmed hold generations through a package-private immutable
snapshot. A test-side scheduling assumption in the initial stale probe was
corrected with a gate before random operations; no engine bug was found in that
failure. The candidate still reviews the decisions and submitted behavior.
The corrected stress test passed 20 consecutive development runs, followed by
three successful full Maven suite runs with 74 test executions per run.
