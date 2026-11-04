# loadgen baseline — 2026-11-04 20:29 (first run, plan 0.4b)

Target: fake venue over FIX at 127.0.0.1:10402 via orders.infrastructure.fix.FixVenue. Requested 60 s, ran 59.987 s. Open loop; latency from the scheduled send time; only OK requests count toward percentiles. Skipped = scheduled but nothing to send (a cancel with no resting order).

| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max | pass mark | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| order | 40.0/s | 2400 | 40.0/s | 2400 | 0 | 0 | 0 | 0 | 3.9 ms | 6.8 ms | 12.3 ms | 33.1 ms | 100.0 ms | PASS |
| cancel | 8.0/s | 444 | 7.4/s | 444 | 0 | 0 | 0 | 36 | 6.1 ms | 10.4 ms | 14.9 ms | 17.0 ms | — | — |
| fill | — | 1947 | — | 1947 | 0 | 0 | 0 | 0 | 6.8 ms | 9.8 ms | 17.0 ms | 34.0 ms | — | — |

Not run (no target yet): quote, portfolio.
