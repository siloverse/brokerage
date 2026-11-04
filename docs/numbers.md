# Numbers

The back-of-envelope sheet (plan 0.4). Every figure here is an **assumption** until a
measurement replaces it; when that happens, keep the estimate in the row and add the
measured value next to it with the date, so the gap between guess and fact stays visible.

Rules of the sheet: powers of ten, round hard, one line per number as
*input → formula → result*. A wrong input is then a one-cell fix.

Inputs that come from the requirements interview (2026-10-31, recorded in
`tasks/brokerage.md` chapter 0) are marked **[interview]**. Inputs that are our own guess are
marked **[guess]** and say why.

## 1. Workload inputs

| Input | Value | Source |
|---|---|---|
| new orders per day | 50,000 | [interview] |
| peak new orders per second (open) | 40 | [interview] |
| cancel ratio | 0.2 | [interview] |
| peak cancels per second | 8 | [interview] |
| quote reads per second (HTTP) | 200 | [interview, provisional] |
| portfolio reads per second | 100 | [interview, provisional] |
| concurrent WebSocket subscribers | 1,000 | [interview, provisional] |
| instruments per subscriber | 5 | [interview] |
| conflation interval | 250 ms (4 updates/s per instrument, only on change) | [interview] |
| instruments (baseline) | 10 | [interview] |
| instruments (later) | 100 in the plan; the feed is also sized at 1,000 | [interview] |
| trading days per year | 250 | [guess: 52 weeks × 5 days minus ~10 holidays] |
| trading hours per day | 6.5 h = 23,400 s | [guess: one US-style session 09:30–16:00; the venue's `sessionClose` already models this] |
| fills per new order (average) | 1.2 | [guess: most DAY limit orders at a pinned price fill once; a few fill in parts; 20 % are cancelled or expire unfilled] |
| book events per second per instrument, drill mode | 10 | [guess: baseline is 48 commands/s over 10 instruments ≈ 5/s each; a burst doubles it; the 90 % skew run puts ~45/s on one instrument] |
| FIX message log retention | 7 days online, then archive | [guess: 2.7 compares executions daily and chapter 9 settles at T+2, so a week covers every open question; older logs go to object storage in chapter 12] |

## 2. Request rates

| Quantity | Formula | Result |
|---|---|---|
| average orders per second over the trading day | 50,000 / 23,400 s | ≈ 2/s |
| peak-to-average ratio | 40 / 2 | ≈ 20× |
| order commands per second at peak (new + cancel) | 40 + 8 | 48/s |
| FIX messages per second broker → venue at peak | 48 application messages + 1 heartbeat per 30 s | ≈ 50/s |
| FIX messages per second venue → broker at peak (reports per order: 1 New + 1.2 fills ≈ 2.2; 1 per cancel) | 40 × 2.2 + 8 | ≈ 100/s |
| reads per second at peak (quotes + portfolio) | 200 + 100 | 300/s |
| total HTTP requests per second at peak, all types | 48 + 300 | ≈ 350/s |
| writes-to-reads ratio | 48 : 300 | ≈ 1 : 6 |

What the rows tell us: the write side is tiny. One thread handles 48 commands/s with four
orders of magnitude to spare (a matching engine does hundreds of thousands per second, chapter 3).
The system is read-heavy even before the WebSocket feed, and the WebSocket feed (section 4)
is 60× the HTTP read rate in messages. The 20× peak-to-average ratio is why the load generator
must run at the peak, not the average.

## 3. Storage per year

Size one record first. Count the fields, not the bytes of the JSON. Postgres adds ~24 bytes
of row header, and each index costs roughly as much again as the key it indexes, so an
"all-in" figure is about 2–3× the raw payload.

| Record | Fields that matter | Bytes per record |
|---|---|---|
| order row | order id 16, account 8, instrument 8, side 1, qty 8, price 8, tif 1, state 1, client order id 36, venue order id 16, idempotency key 36, version 8, three timestamps 24 ≈ 170 raw | ≈ 500 all-in (row header + 3 indexes: account, client order id, idempotency key) |
| execution report row | exec id 16, order id 16, last qty 8, last px 8, cum 8, leaves 8, seq 8, venue time 8, received 8 ≈ 90 raw | ≈ 300 all-in (2 indexes: exec id, order id) |
| ledger entry (one leg of a transfer) | id 8, transfer id 16, account 8, amount 8, currency 3, kind 1, created 8 ≈ 50 raw | ≈ 200 all-in (2 indexes: account, transfer id) |
| FIX message in the session store | measured in 0.3b: NewOrderSingle 150 B, ExecutionReport ≈ 200 B, + 12 B record header | ≈ 200 average |
| top-of-book snapshot (if persisted) | symbol 8, bid 8, ask 8, bid size 8, ask size 8, seq 8, ts 8 | ≈ 60 raw, ≈ 150 as JSON on the wire |

| Table | Records per day | Formula | Bytes per year |
|---|---|---|---|
| orders | 50,000 (cancels update the row) | 50,000 × 500 B × 250 | ≈ 6 GB |
| executions | 60,000 (50,000 × 1.2 fills) | 60,000 × 300 B × 250 | ≈ 4.5 GB |
| ledger entries | 200,000 (per order: hold 1, release 1, settlement 2 legs) | 200,000 × 200 B × 250 | ≈ 10 GB |
| FIX message logs (both directions) | 170,000 (60,000 out, 110,000 in) | 170,000 × 200 B = 34 MB/day; × 7 days online, × 250 archived | ≈ 240 MB online, ≈ 8.5 GB/year archived |
| **total** | | | **≈ 30 GB/year**, of which ≈ 20 GB in Postgres |

All four tables grow without bound; nothing is ever deleted (orders and executions are the
audit trail, ledger entries are the books). At 30 GB/year a single Postgres is comfortable for
a decade; size alone never forces partitioning at this scale. The first table to need
attention is **ledger entries**, not for size but for the hot rows behind it: every hold and
settlement updates the same account balance row, so contention arrives long before volume
(chapter 1 measures retries per minute for exactly this). The first archiving job is the FIX
log, by retention policy rather than size.

## 4. Price feed bandwidth

One top-of-book update on the wire: ≈ 100 B of JSON + WebSocket frame header (2–4 B) + the
TCP/IP headers of its own packet (≈ 50 B) when sent alone → **≈ 150 B per update**, or ≈ 100 B
when several updates to one client share a packet.

| Mode | Instruments | Formula | Updates per second | Bytes per second |
|---|---|---|---|---|
| conflated, per subscriber | 10 | 5 subscriptions × 4/s | 20/s | 3 KB/s |
| conflated, all subscribers | 10 | 1,000 × 20 | 20,000/s | 3 MB/s ≈ 24 Mbit/s |
| conflated, all subscribers | 1,000 | 1,000 × 20 (each subscriber still has 5 instruments) | 20,000/s | 3 MB/s ≈ 24 Mbit/s |
| unfiltered (drill), all subscribers | 10 | 1,000 × 5 instruments × 10 events/s | 50,000/s | 7.5 MB/s ≈ 60 Mbit/s |
| unfiltered (drill), all subscribers | 1,000 | 1,000 × 5 × 10 (server sees 10,000 events/s instead of 100) | 50,000/s | 7.5 MB/s ≈ 60 Mbit/s |

The thing the table shows: **outbound bandwidth scales with subscribers × subscriptions, not
with the number of instruments.** Going from 10 to 1,000 instruments changes nothing on the
wire; it changes the server's work (4,000 conflation snapshots per second instead of 40, and
10,000 events to route instead of 100) and its memory (a book per instrument). Conflation
caps the client-facing rate at 4 per instrument per second whatever the market does;
unfiltered mode is 2.5× more at our guessed event rate and unbounded in a burst, which is why
it needs the per-client queue and the disconnect rule.

Both modes fit one machine's network card with room to spare: 1 Gbit/s is 125 MB/s, so the
unfiltered drill uses 6 % of it. The wire is not the first bottleneck. In order:

1. **The slow consumer.** One client that stops reading fills its kernel socket buffer, and
   a blocking write inside the fan-out loop stalls every other client (proved in 0.3b, same
   mechanism). Bounded queue + disconnect is the fix, not more bandwidth.
2. **Syscalls.** 50,000 `write` calls/s at ~5 µs each is a quarter of a core; batching the
   updates for one client into one write halves it.
3. **Serialisation CPU.** 50,000 JSON encodes/s at ~2 µs each is a tenth of a core; encode
   each snapshot once and copy the bytes to every subscriber, never once per subscriber.
4. **Connections.** 1,000 open sockets is nothing; 100,000 needs a non-blocking server
   (one thread per connection would be 100,000 threads).

## 5. One machine

Measured 2026-11-04 on the host laptop (NVMe, Linux) and inside the macgrant VM (VirtualBox
virtual disk on the same NVMe). Both columns matter: the broker runs in the VM, and a virtual
disk confirms writes differently from its host.

| Resource | How measured | Host (laptop) | macgrant VM |
|---|---|---|---|
| fsyncs per second on the data disk | `dd bs=4k count=2000 oflag=dsync` (one fsync per 4 KB write) | **≈ 320/s** (3.1 ms each) | **≈ 1,000/s** (1 ms each) in `/var/lib/postgresql`, but see below |
| sequential write throughput | `dd bs=1M count=512 conv=fsync` | **≈ 830 MB/s** | ≈ 890 MB/s |
| memory for the order book, 10 instruments at depth | ≈ 150 B per resting order (object + map entry); say 10,000 resting orders across 10 instruments | ≈ 1.5 MB (at 1,000 instruments and 1 M resting orders: ≈ 150 MB) | same |
| CPU cores available to the JVM | `nproc` | 8 | 2 |
| memory | `free -g` | 60 GB | 5 GB |

**The VM fsync number is a lie, and a useful one.** The guest's disk is a file on the host.
When the guest calls fsync, VirtualBox answers as soon as the bytes are in the host's page
cache, not when the NVMe has them (host I/O cache is on by default). So the VM "does" 1,000
fsyncs per second on a disk that can only do 320. A power cut to the laptop would lose data
the guest was told was durable. Two lessons: a durability promise is only as good as the
lowest layer that honours it, and a cloud VM's "disk" has the same question (which is why
cloud providers publish fsync latency for their persistent disks). For the capacity test we
use the **host figure, 320/s**, as the honest ceiling, and note that the VM will look 3×
better than it is.

The VM also has 2 cores and 5 GB, so Postgres, the broker, the venue, Traefik and Prometheus
share two cores. Expect the latency targets to be harder to meet in the VM than the numbers
above suggest, and expect CPU, not disk, to be the first thing the load generator saturates.

**The durable-order ceiling.** With one fsync per accepted order on the engine log (the
write-ahead rule from 0.3b), the host can accept at most **≈ 320 orders/s** durably, whatever
the code does. That is 8× our 40/s peak, so baseline is safe, but the capacity test raises
load in steps and this is the first wall it hits. The way past it is group commit: wait up to
1 ms, write every order that arrived, fsync once. At 1 ms batches the ceiling becomes
1,000 batches/s × however many orders fit, and the cost is ≤ 1 ms of added latency per
order, well inside the 100 ms target. Chapter 3's three log strategies differ mostly in who
does this batching: our own file WAL, Postgres (which group-commits its WAL already), or
Kafka (which batches on the producer).

Note the shape of the numbers: sequential writes are 830 MB/s but fsyncs are 320/s. The
disk is fast; waiting for it to confirm is slow. Every durable-write design is about
amortising that wait.

## 6. Targets (pass marks for every measurement)

| Target | Value | Measured where | Counted separately |
|---|---|---|---|
| order acknowledgement p99 | ≤ 100 ms at baseline load | load generator, from send until confirmation of durable engine acceptance under the selected replication policy, **not** a fill | rejected and timed-out requests, so a fast failure never improves the latency number |
| quote read p99 | ≤ 50 ms end to end | load generator, request to response | freshness (age of the quote served), tracked on its own; a fast stale answer does not pass |
| engine backlog | ≤ 100 committed-but-unapplied commands, summed across shards and reported per shard | engine metrics | age of the oldest unapplied command |
| slow WebSocket consumer | disconnected when its queue passes 1,000 messages [guess: ≈ 50 s of conflated feed or 20 s of drill feed; long enough to ride out a GC pause, short enough that memory stays bounded at 1,000 clients × 1,000 × 150 B = 150 MB worst case] | server side | disconnects per minute, gap reports sent, queue depth p99 |
| capacity test | highest sustained step rate that meets both latency targets, keeps the backlog under 100 and shows no continuing queue growth; one run with 90 % of orders on one instrument | load generator + engine metrics | reported per step, never as a single number |

## 7. The 100× question (0.6)

Each input alone at 100×, everything else unchanged.

| Input at 100× | What breaks | What it becomes |
|---|---|---|
| orders: 4,000/s peak, 5 M/day | the fsync ceiling (320/s) by 12×; the ledger's balance rows see 4,000 holds/s on a few hot accounts | group commit on the engine log; ledger holds batched or partitioned by account; still one engine thread (chapter 3 shows it has 100× more headroom) |
| HTTP quote reads: 20,000/s | the engine cannot answer reads and match at the same time; Postgres cannot either | a cache or read replica in front of the engine (chapter 4 / 6); honestly the right call at 200/s too |
| WebSocket subscribers: 100,000 | conflated feed becomes 2 M updates/s ≈ 300 MB/s, more than a 1 Gbit card (125 MB/s); 100,000 sockets on one process | a fan-out tier: the engine publishes each snapshot once, several edge nodes each hold a share of the clients (chapter 4) |
| instruments: 1,000 | nothing on the wire (section 4); 100× the conflation work and book memory (150 MB) in one process | shard the engine by instrument (chapter 5), for CPU isolation and the hot-partition problem, not for memory |
| storage: 3 TB/year | one Postgres volume, vacuum, backups, index size | monthly partitions, archive to object storage (chapter 12), an LSM-style store for trade history (chapter 10) |

The one that changes the architecture **first** is the WebSocket subscriber count, because
it is the only input that pushes a single resource (the network card of one machine) past
its physical limit. Everything else at 100× is a software change on the same box. Second is
the ledger, because hot-row contention arrives before any size limit does.

## 8. Measured values

Append one row each time a measurement replaces an estimate.

| Date | Quantity | Estimated | Measured | Note |
|---|---|---|---|---|
| 2026-11-04 | fsyncs per second, host laptop | ~1,000 (SSD rule of thumb) | ≈ 320/s | dd oflag=dsync, 4 KB writes |
| 2026-11-04 | sequential write, host laptop | — | ≈ 830 MB/s | dd 1 MB blocks, conv=fsync |
| 2026-11-04 | fsyncs per second, macgrant VM | same as host or worse | ≈ 1,000/s | faster than the physical disk: VirtualBox acknowledges the flush from the host cache; not a durable number |
| 2026-11-04 | sequential write, macgrant VM | — | ≈ 890 MB/s | same caveat |
| 2026-11-04 | cores / memory, macgrant VM | — | 2 cores, 5 GB | shared by Postgres, broker, venue, Traefik, Prometheus |
| 2026-11-04 | FIX message size | — | NOS 150 B, ExecutionReport ≈ 200 B | from the 0.3b codec fixtures |
