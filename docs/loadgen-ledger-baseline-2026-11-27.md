# loadgen baseline against the ledger — 2026-11-27 23:01 (second of two runs, plan chapter 1 "Measure")

Target: ledger over HTTP at http://localhost:10301 (order = hold, cancel = release), 100 users. Requested 60 s, ran 59.993 s. Open loop; latency from the scheduled send time; only OK requests count toward percentiles. Skipped = scheduled but nothing to send (a cancel with no resting order).

| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max | pass mark | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| order | 40.0/s | 2400 | 40.0/s | 2400 | 0 | 0 | 0 | 0 | 6.8 ms | 12.2 ms | 19.8 ms | 43.8 ms | 100.0 ms | PASS |
| cancel | 8.0/s | 443 | 7.4/s | 443 | 0 | 0 | 0 | 37 | 4.6 ms | 8.2 ms | 16.6 ms | 27.1 ms | — | — |

Not run (no target yet): quote, portfolio.
