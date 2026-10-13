# Bugs I Fear

## Clock discontinuities / wall-clock jumps

Expiry uses the injected clock's `Instant`. When a caller supplies
[`Clock.systemUTC()`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/Clock.html#systemUTC()),
it follows machine wall time, which can change through NTP corrections, VM clock
changes, or operator adjustments. A backward jump can prolong a hold or make a
previously expired, unsuperseded hold appear active again. A large forward jump
can expire holds earlier than their intended elapsed TTL.

The tests advance `MutableClock` monotonically. Some advance by a day, but they do
not simulate rollback or compare wall-clock jumps with independently elapsed
time. The assessment implementation does not solve or monitor this issue.

In production I would:

- Base TTL decisions on a monotonic elapsed-time source where possible, such as
  [`System.nanoTime()` differences](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#nanoTime()),
  keeping wall-clock timestamps for external display and audit.
- Test rollback and large forward jumps during active holds, including capacity
  and same-user retries after apparent expiry.
- Monitor wall-clock deltas against elapsed time and emit metrics/logs when
  backward movement or unexpected forward deltas violate expected bounds.
