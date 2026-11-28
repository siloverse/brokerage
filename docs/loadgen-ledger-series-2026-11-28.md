# Ledger load series — 2026-11-28, 00:44 to 00:59

The whole series in one go, by script: the app started three times with different settings,
every run 60 s (H5: 300 s) at the baseline profile, a 30 s warm-up after each start. App and
load generator on the host laptop, PostgreSQL in the macgrant VM; everything shares the same
cores, so the numbers compare with each other and not with a real server.

After every run the script read the app's own counters. "server status codes" is the proof of
what the requests did: a placed hold is a 201. This series replaces the runs of 2026-11-27,
which measured replays (see `loadgen-ledger-baseline-2026-11-27.md`).

| # | Run | isolation | pool | holds placed | failed | p50 | p99 | max |
|---|---|---|---|---|---|---|---|---|
| 1 | baseline, 40 holds/s, 100 users | read committed | 3 | 2,400 | 0 | 8.5 ms | 22.9 ms | 41 ms |
| 2 | baseline again | read committed | 3 | 2,400 | 0 | 8.3 ms | 23.1 ms | 53 ms |
| 3 | one hot account | read committed | 3 | 2,400 | 0 | 7.9 ms | 27.4 ms | 57 ms |
| 4 | five times the load | read committed | 3 | 12,000 | 0 | 5.0 ms | 100.5 ms | 367 ms |
| 5 | baseline | read committed | 10 | 2,400 | 0 | 7.9 ms | 31.0 ms | 74 ms |
| 6 | five times the load | read committed | 10 | 12,000 | 0 | 5.1 ms | 36.0 ms | 133 ms |
| 7 | H5: 100 users, 5 minutes | serializable | 3 | 11,925 | 75 (0.6 %) | 7.7 ms | 24.6 ms | 67 ms |
| 8 | one hot account | serializable | 3 | 2,036 | 364 (15.2 %) | 7.6 ms | 20.6 ms | 38 ms |

Latency is the load generator's, from the scheduled send time, over the requests that
succeeded. No retry existed yet: a transaction the database cancelled came back as a 500.

The serialization failures, from the logs:

| Run | "concurrent update" | "read/write dependencies" (pivot) |
|---|---|---|
| 7, 100 users | 22 | 54 |
| 8, one account | 364 | 1 |

## The script's output

```text
MEASUREMENT SERIES started 2026-11-28 00:44:23

##### APP START: defaults (read committed, pool 3)
app up after 11s

=== warm-up (ignore)   [00:44:34]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 1200 | 40.0/s | 1200 | 0 | 0 | 0 | 0 | 9.4 ms | 20.1 ms | 33.3 ms | 68.0 ms
| cancel | 8.0/s | 209 | 7.0/s | 208 | 1 | 0 | 0 | 31 | 7.6 ms | 17.6 ms | 30.1 ms | 45.4 ms | — | — |
  server status codes: hold 201: 1200, release 200: 208, release 404: 1
  server-side, placed holds only: p50 7.8 ms  p99 28.0 ms
  connection held avg 9.0 ms over 1642 uses; wait for a connection avg 0.02 ms; pool timeouts 0
  error log lines: 0
  pool, sampled each second: peak active 2, peak pending 0

=== 1. baseline, first run   [00:45:09]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 2400 | 40.0/s | 2400 | 0 | 0 | 0 | 0 | 8.5 ms | 16.3 ms | 22.9 ms | 41.2 ms
| cancel | 8.0/s | 443 | 7.4/s | 440 | 3 | 0 | 0 | 37 | 7.2 ms | 13.3 ms | 19.2 ms | 33.8 ms | — | — |
  server status codes: hold 201: 2400, release 200: 440, release 404: 3
  server-side, placed holds only: p50 7.0 ms  p99 19.3 ms
  connection held avg 7.7 ms over 3105 uses; wait for a connection avg 0.03 ms; pool timeouts 0
  error log lines: 0
  pool, sampled each second: peak active 2, peak pending 0

=== 2. baseline, second run   [00:46:14]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 2400 | 40.0/s | 2400 | 0 | 0 | 0 | 0 | 8.3 ms | 16.3 ms | 23.1 ms | 53.2 ms
| cancel | 8.0/s | 443 | 7.4/s | 442 | 1 | 0 | 0 | 37 | 6.7 ms | 12.8 ms | 18.5 ms | 23.2 ms | — | — |
  server status codes: hold 201: 2400, release 200: 442, release 404: 1
  server-side, placed holds only: p50 7.0 ms  p99 20.4 ms
  connection held avg 7.8 ms over 3105 uses; wait for a connection avg 0.03 ms; pool timeouts 0
  error log lines: 0
  pool, sampled each second: peak active 3, peak pending 0

=== 3. one hot account   [00:47:18]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 2400 | 40.0/s | 2400 | 0 | 0 | 0 | 0 | 7.9 ms | 15.9 ms | 27.4 ms | 56.9 ms
| cancel | 8.0/s | 444 | 7.4/s | 442 | 2 | 0 | 0 | 36 | 6.3 ms | 12.6 ms | 20.4 ms | 22.2 ms | — | — |
  server status codes: hold 201: 2400, release 200: 442, release 404: 2
  server-side, placed holds only: p50 6.5 ms  p99 21.6 ms
  connection held avg 7.0 ms over 2905 uses; wait for a connection avg 0.02 ms; pool timeouts 0
  error log lines: 0
  pool, sampled each second: peak active 2, peak pending 0

=== 4. five times the load, pool 3   [00:48:19]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 200.0/s | 12000 | 200.0/s | 12000 | 0 | 0 | 0 | 0 | 5.0 ms | 11.5 ms | 100.5 ms | 367.1 ms
| cancel | 40.0/s | 2344 | 39.1/s | 2334 | 10 | 0 | 0 | 56 | 3.8 ms | 8.3 ms | 89.8 ms | 289.3 ms | — | — |
  server status codes: hold 201: 12000, release 200: 2334, release 404: 10
  server-side, placed holds only: p50 4.9 ms  p99 97.3 ms
  connection held avg 4.8 ms over 14607 uses; wait for a connection avg 3.14 ms; pool timeouts 0
  error log lines: 0
  pool, sampled each second: peak active 3, peak pending 12

##### APP START: --brokerage.ledger.datasource.pool-size=10
app up after 10s

=== warm-up (ignore)   [00:49:34]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 1200 | 40.0/s | 1200 | 0 | 0 | 0 | 0 | 9.1 ms | 19.2 ms | 36.4 ms | 66.6 ms
| cancel | 8.0/s | 209 | 7.0/s | 208 | 1 | 0 | 0 | 31 | 7.2 ms | 17.5 ms | 32.2 ms | 38.7 ms | — | — |
  server status codes: hold 201: 1200, release 200: 208, release 404: 1
  server-side, placed holds only: p50 7.6 ms  p99 30.8 ms
  connection held avg 9.1 ms over 1643 uses; wait for a connection avg 0.01 ms; pool timeouts 0
  error log lines: 0
  pool, sampled each second: peak active 2, peak pending 0

=== 5. baseline, pool 10   [00:50:10]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 2400 | 40.0/s | 2400 | 0 | 0 | 0 | 0 | 7.9 ms | 17.2 ms | 31.0 ms | 74.4 ms
| cancel | 8.0/s | 444 | 7.4/s | 442 | 2 | 0 | 0 | 36 | 6.6 ms | 13.8 ms | 24.6 ms | 52.3 ms | — | — |
  server status codes: hold 201: 2400, release 200: 442, release 404: 2
  server-side, placed holds only: p50 6.8 ms  p99 26.1 ms
  connection held avg 7.8 ms over 3106 uses; wait for a connection avg 0.00 ms; pool timeouts 0
  error log lines: 0
  pool, sampled each second: peak active 2, peak pending 0

=== 6. five times the load, pool 10   [00:51:15]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 200.0/s | 12000 | 200.0/s | 12000 | 0 | 0 | 0 | 0 | 5.1 ms | 9.8 ms | 36.0 ms | 133.4 ms
| cancel | 40.0/s | 2344 | 39.1/s | 2326 | 18 | 0 | 0 | 56 | 3.9 ms | 6.9 ms | 24.2 ms | 79.1 ms | — | — |
  server status codes: hold 201: 12000, release 200: 2326, release 404: 18
  server-side, placed holds only: p50 5.0 ms  p99 32.2 ms
  connection held avg 5.2 ms over 14606 uses; wait for a connection avg 0.14 ms; pool timeouts 0
  error log lines: 0
  pool, sampled each second: peak active 5, peak pending 0

##### APP START: --brokerage.ledger.datasource.isolation=serializable
app up after 11s

=== warm-up (ignore)   [00:52:32]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 1200 | 40.0/s | 1176 | 0 | 0 | 24 | 0 | 9.4 ms | 20.2 ms | 33.1 ms | 88.1 ms
| cancel | 8.0/s | 209 | 7.0/s | 204 | 5 | 0 | 0 | 31 | 7.3 ms | 15.6 ms | 27.8 ms | 31.7 ms | — | — |
  server status codes: hold 201: 1176, hold 500: 24, release 200: 204, release 404: 5
  server-side, placed holds only: p50 7.8 ms  p99 27.7 ms
  connection held avg 9.5 ms over 1643 uses; wait for a connection avg 0.02 ms; pool timeouts 0
  error log lines: 24
  pool, sampled each second: peak active 2, peak pending 0

=== 7. H5: serializable, 100 users, 5 minutes   [00:53:07]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 12000 | 40.0/s | 11925 | 0 | 0 | 75 | 0 | 7.7 ms | 16.0 ms | 24.6 ms | 67.2 ms
| cancel | 8.0/s | 2344 | 7.8/s | 2318 | 25 | 0 | 1 | 56 | 5.8 ms | 11.5 ms | 18.1 ms | 46.7 ms | — | — |
  server status codes: hold 201: 11925, hold 500: 75, release 200: 2318, release 404: 25, release 500: 1
  server-side, placed holds only: p50 7.0 ms  p99 21.8 ms
  connection held avg 7.6 ms over 14839 uses; wait for a connection avg 0.02 ms; pool timeouts 0
  error log lines: 76
  pool, sampled each second: peak active 2, peak pending 0

=== 8. serializable, one hot account   [00:58:12]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 2400 | 40.0/s | 2036 | 0 | 0 | 364 | 0 | 7.6 ms | 15.3 ms | 20.6 ms | 38.2 ms
| cancel | 8.0/s | 443 | 7.4/s | 365 | 77 | 0 | 1 | 37 | 6.6 ms | 12.2 ms | 18.4 ms | 62.7 ms | — | — |
  server status codes: hold 201: 2036, hold 500: 364, release 200: 365, release 404: 77, release 500: 1
  server-side, placed holds only: p50 6.5 ms  p99 16.5 ms
  connection held avg 6.9 ms over 2904 uses; wait for a connection avg 0.02 ms; pool timeouts 0
  error log lines: 365
  pool, sampled each second: peak active 3, peak pending 0

SERIES DONE 00:59:15
```

## With the retry, 01:16 to 01:22

The same two serializable runs after `@RetryLostTransaction` was added: up to three more
attempts after about 5, 10 and 20 ms. A request that still loses is answered 503.

| # | Run | first attempt and retries all lost (503) | retries | p50 | p99 | max |
|---|---|---|---|---|---|---|
| 9 | serializable, 100 users, 5 minutes | 33 of 12,000 holds (0.3 %) | 591 for holds, 118 a minute | 9.3 ms | 66.5 ms | 319 ms |
| 10 | serializable, one hot account | 30 of 2,400 holds (1.3 %) | 779 for holds in one minute | 9.4 ms | 89.0 ms | 134 ms |

Compare runs 7 and 8 above, without the retry: 75 and 364 failures, p99 24.6 and 20.6 ms.

The "release given_up" counts below (9 and 13) equal the releases answered 404. The listener
counted every failure Spring would not retry; it now counts only lost transactions.

```text
MEASUREMENT SERIES started 2026-11-28 01:15:38

##### APP START: --brokerage.ledger.datasource.isolation=serializable
app up after 17s

=== warm-up (ignore)   [01:15:55]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 1200 | 40.0/s | 1139 | 0 | 0 | 61 | 0 | 15.5 ms | 141.8 ms | 492.3 ms | 758.8 ms
| cancel | 8.0/s | 210 | 7.0/s | 192 | 18 | 0 | 0 | 30 | 14.6 ms | 94.5 ms | 226.9 ms | 335.4 ms | — | — |
  server status codes: hold 201: 1139, hold 503: 61, release 200: 192, release 404: 18
  server-side, placed holds only: p50 12.1 ms  p99 495.4 ms
  connection held avg 17.3 ms over 2198 uses; wait for a connection avg 16.91 ms; pool timeouts 0
  error log lines: 0
  retries counted by the ledger: hold given_up: 61, hold retried: 611, release given_up: 18, release retried: 22
  pool, sampled each second: peak active 3, peak pending 16

=== 9. H5 with retry: serializable, 100 users, 5 minutes   [01:16:32]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 12000 | 40.0/s | 11967 | 0 | 0 | 33 | 0 | 9.3 ms | 20.2 ms | 66.5 ms | 318.5 ms
| cancel | 8.0/s | 2344 | 7.8/s | 2335 | 9 | 0 | 0 | 56 | 7.0 ms | 15.0 ms | 34.3 ms | 135.3 ms | — | — |
  server status codes: hold 201: 11967, hold 503: 33, release 200: 2335, release 404: 9
  server-side, placed holds only: p50 8.3 ms  p99 60.8 ms
  connection held avg 9.4 ms over 15402 uses; wait for a connection avg 0.16 ms; pool timeouts 0
  error log lines: 0
  retries counted by the ledger: hold given_up: 33, hold retried: 591, release given_up: 9, release retried: 15
  pool, sampled each second: peak active 3, peak pending 3

=== 10. serializable with retry, one hot account   [01:21:37]
| type | target rate | sent | achieved rate | ok | rejected | timeout | error | skipped | p50 | p90 | p99 | max 
| order | 40.0/s | 2400 | 40.0/s | 2370 | 0 | 0 | 30 | 0 | 9.4 ms | 29.0 ms | 89.0 ms | 134.0 ms
| cancel | 8.0/s | 443 | 7.4/s | 430 | 13 | 0 | 0 | 37 | 6.0 ms | 14.0 ms | 37.1 ms | 70.4 ms | — | — |
  server status codes: hold 201: 2370, hold 503: 30, release 200: 430, release 404: 13
  server-side, placed holds only: p50 8.0 ms  p99 85.3 ms
  connection held avg 8.2 ms over 3663 uses; wait for a connection avg 0.09 ms; pool timeouts 0
  error log lines: 0
  retries counted by the ledger: hold given_up: 30, hold retried: 779, release given_up: 13, release retried: 23
  pool, sampled each second: peak active 3, peak pending 0

SERIES DONE 01:22:40
```

