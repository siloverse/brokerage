# CHECKPOINT

## 2026-10-30 — 0.2 skeleton

Done. Five context modules (`ledger`, `orders`, `engine`, `marketdata`, `portfolio`),
`app` assembles them, `venue` is a separate Boot app that depends on nothing here.
Spring Modulith 2.1.2 via the local catalog; `ModularityTest` green and the module map
prints exactly five modules, no beans. No database yet (arrives with 1.1).

Rule written today: a new Gradle module is justified only by a different consumer or a
different release lifecycle. A different layer is a package inside the module.

macgrant: two hiera lines under `profile::backend::services` (brokerage 10301,
venue 10401). Puppet generated, per service: a Traefik router on `websecure` with TLS
and a service pointing at the host IP and port, plus a Prometheus job with
`metrics_path: /actuator/prometheus`. All three checked after provisioning.

Lessons:
- `vagrant up` does not re-run puppet. The hiera change did nothing until
  `vagrant provision`; Traefik answered 404 until then.
- The observability bundle enables Micrometer's OTLP metrics push by default; the
  platform scrapes instead, so `management.otlp.metrics.export.enabled: false`.
- A `@file:` annotation must be the first line of a Kotlin file, before imports.
- Predictions for DNS / Traefik / Prometheus were not written down before provisioning.
  Do that next time; the step 7 exercise was half lost.

Next: 0.3, the Venue port.

## 2026-11-01 — 0.3 Venue port, design answers

Format: my first answer → final decision, and what changed my mind.

1. **Where does the port live?** Mine: `orders`, because orders is the one that talks to the
   venue and the engine → **kept**, with one wording fix: orders talks to `Venue` only and
   must not know which adapter stands behind it. Adapters: the FIX initiator in the internal
   package `orders.venue.fix`; the engine adapter in `app` (chapter 3). `engine` never imports
   `orders`, `orders` never imports `engine`. It is an *outbound* port: orders is both the
   owner and the only caller; the interface is public only so `app` can implement it.

2. **What does `submit` return?** Mine: the acceptance ("I have taken your order") →
   **changed to a receipt** (venue order id, "I have your request"). Reason: if the venue
   accepts and the response is lost, an acceptance that exists only as a return value is
   gone; so acceptance must also be a report, and then the return value is a second path for
   the same fact. FIX does the same: NewOrderSingle is answered by an ExecutionReport with
   ExecType=New on the stream.

3. **How do reports reach the broker?** Mine: batch files on S3/FTP polled every minute;
   no callbacks because they would slow the venue → **changed**. A one-minute poll breaks my
   own targets (p99 ack ≤ 100 ms, quote freshness ≤ 50 ms). A push does not slow the venue if
   it writes to its own log and sends from another thread; a slow consumer is handled by a
   bounded queue plus sequence numbers and resend. Port: `executions()` returns a
   `Flow<ExecutionReport>`; transport is the adapter's business. Decided the same day:
   transport is **FIX 4.4 over TCP** (plan 0.3b). Files stay for the daily execution list
   used by 2.7 / chapter 9.

4. **Ids.** Mine: client order id by the client; venue order id by the orders module;
   execution id by the venue; sequence number unknown → **two flipped**. Rule for an
   idempotency key: the *sender* creates it before the first attempt and resends it
   unchanged; the receiver stores it with the result and returns the stored result on a
   repeat. Hence: client order id (FIX ClOrdID 11) = broker, the key the venue dedupes on;
   venue order id (OrderID 37) = venue, an identifier, not a key; execution id (ExecID 17) =
   venue, per fill, immutable, the key the broker dedupes reports on; the user → broker hop
   has its own `Idempotency-Key` header; broker → ledger has the transfer key. Sequence
   numbers answer a different question, "did I miss one?", not "did I see this twice?".
   In FIX they are **per session** (MsgSeqNum 34), both directions; a gap triggers
   ResendRequest. Per-order ordering follows from one ordered session stream.

5. **Report types.** Mine: no answer → method: for each lifecycle state ask who learns the
   fact first. Venue-first facts become reports, named after FIX ExecType: `New` (accepted),
   `Rejected`, `Trade` (a fill, partial or last; the broker derives `filled` from
   LeavesQty = 0), `Canceled`, `Expired`, plus `OrderCancelReject` for a cancel of an order
   that is already done. Broker-only facts with no report: `pending_submission`,
   `submission_unknown`, `cancel_pending`, `cancel_unknown`, and `filled` itself.

6. **Price and quantity.** Mine: price as Long minor units, quantity as Double → **quantity
   changed to Long**. A double cannot hold 0.1 exactly; v1 is whole shares; fractional
   shares later become a Long in a fixed small unit, like cents. Ledger needs money =
   amount + currency code and position = quantity + instrument code, both summed in SQL.
   Value classes for ids, price and quantity so the compiler stops a Price going where a
   Quantity belongs.

Open: I still want to see the "callback slows the venue" claim fail or hold in a measurement
— drill for 0.3b: bounded outbound queue, slow broker, watch venue latency.

Next: `Venue.kt` + KDoc contract + `VenueContract` test fixture.

## 2026-11-03 — 0.3b day 0: FIX session layer read, restart exercise

Read: FIX Session Layer Technical Specification (June 2020), chapter 4 in full, 8.5–9 as
reference. Summary in my words: connections, sessions, Logon/Logout; sequence counters,
heartbeats, failure detection; resend, replay, gap fill, reset; validation, persistence,
crash recovery, business idempotency.

Exercise (broker crashed; store: inbound 4711 processed, outbound 300 sent; venue produced
4712–4720 meanwhile):
1. Broker sends Logon 35=A 34=301 — numbering survives a crash; expects inbound 4712.
2. Venue replies Logon 34=4721; broker sees 4721 > 4712, queues the Logon, sends
   ResendRequest 35=2 34=302 7=4712 16=0 (16=0 = "everything from here"; an explicit
   16=4720 also works but assumes knowledge the broker lacks).
3. Venue replays application reports with original 34, 43=Y, 122=OrigSendingTime, new 52.
   Heartbeats in the range are not replayed: SequenceReset-GapFill 35=4 43=Y 123=Y 36=<next
   real seq>. After 4720 and the queued Logon 4721, NextNumIn = 4722.
4. Loops: venue assigns NEW seq numbers to replays (gap never closes); broker discards every
   43=Y (throws away the fill); broker logs on with 300 or 1 (venue rejects, same state,
   same failure).

Follow-ups:
- Write-ahead: broker sends 301, crashes before storing NextNumOut=302, logs on with 301
  again → venue sees lower-than-expected without 43=Y → Logout + disconnect (spec "should").
  Rule: durably store the message AND the counter advance BEFORE writing to the socket.
  Storing only the counter avoids reuse but leaves nothing to replay.
- Missing history: venue store starts at 4716 → it must send GapFill 35=4 34=4712 43=Y
  123=Y 36=4716, then replay 4716–4720. Silence is not a recovery response: the broker
  keeps expecting 4712 forever. Sequence synchronization is NOT business reconciliation:
  the gap-filled range may have held fills → flag incomplete history, reconcile via
  lookup / 2.7.
- Already-applied trade re-delivered with 43=Y: two transactions. Session layer commits
  inbox row + inbound counter together; business layer commits execution-id dedup key +
  ledger + inbox "processed" together. Counter protects the session; execution id
  (port guarantee 2) protects the money. This is the transactional inbox of chapter 8.

Next: stage A, the wire, in a new `fix` module.

## 2026-11-03 — 0.3b stages A and B: the wire and the session layer

`fix` module, no Spring, no brokerage dependency. Packages by responsibility: `message`
(data model) ← `codec` (encoder, decoder, framer) ← `session` (state machine, message
factories) ; `store` (persistence) and `transport` (bytes in/out) stand alone.

Decisions:
- `MessageStore.append(encode)` hands out the number, persists message + counter advance,
  then returns: the write-ahead rule from the exercise, expressed in the type.
- File store = two append-only files, Awais's layout. Each file owns ONE fact
  (`messages.log` → outbound number, `inbound.log` → inbound number), so the two appends
  never need a transaction. Rule learned: when two writes seem to need a transaction,
  check whether one is a copy; delete the copy instead.
- Durability split: messages.log fsync per append (reused number = dead session);
  inbound.log not forced (lost tail = one harmless over-sized resend, absorbed by ExecID
  dedup). Measure the fsync floor in stage D.
- `FixSession` is a pure state machine (option 2): `connect / onBytes / onTick / send /
  logout / disconnected` → `SessionOutput`. The driver owns threads, transport and clock.
  Same shape the chapter 3 engine will have. Every rule is a deterministic test, no sleeps.
- Session rules as tests: store-before-send; in-order; gap → hold + one ResendRequest +
  drain; too-low with PossDup ignored, without → Logout; resend replays app messages with
  43=Y/122 and gap-fills admin + holes; heartbeat / TestRequest / disconnect on silence;
  Logon negotiates the interval (acceptor wins), 141=Y resets both and is echoed.
- Bug found by test: silence threshold used the configured interval, not the negotiated
  one. The acceptor's 20 s beat the initiator's 30 s and the TestRequest came 12 s late.

Stage B exit test green: 10 messages, link cut between 6 and 7, reconnect, each delivered
once, 7–10 PossDup, the heartbeat gap-filled. Day-0 exercise replayed as a test: broker
restarts from its store, logs on with the right number, detects the gap, recovers.

Open for stage C: the driver (threads + transport + reconnect/backoff), acceptor accepting
one session per CompID pair, application-level resend (spec 4.9) left to the application.

## 2026-11-04 — 0.3b stage C: order flow over FIX, the port proven end to end

- `fix.order`: the order-flow constants (tags, ExecType, OrdStatus, side, ord type) in the
  `fix` module because both sides speak them; meaning stays with the users.
- `venue.engine.VenueEngine`: the fake exchange as a pure state machine (messages + ticks
  in, replies out). Random-walk mid per instrument, pinned by `priceStep = 0`; crossing
  orders fill at the mid after `fillDelay`, in 1 or 2 executions; resting orders checked
  per tick and expired at the session close; duplicate ClOrdID → ExecType=I with the same
  OrderID (guarantee 1); cancel of a done order → OrderCancelReject; OrderStatusRequest →
  ExecType=I, unknown order → OrdStatus=8 + text "unknown order". 10 tests.
- `venue.fix.VenueFixServer`: acceptor + one session + engine; ticker drives fills. Replies
  are dropped while the broker is not logged on; the broker asks for status on return (2.6).
- `orders.venue.fix.FixVenue : Venue` (internal package, as decided in 0.3): submit waits
  for the first report with that ClOrdID to learn OrderID; lookup = OrderStatusRequest;
  executions() = shared flow with bounded replay; VenueUnavailableException is the "we do
  not know" outcome. `FixMapping` is the only file that knows FIX tags on the broker side.
- Test-only exception journaled: `venue` depends on `orders` + its test fixtures in
  `testImplementation` only, so the Venue port's contract runs here against the real FIX
  stack over a TCP socket. All four contract tests green.
- Two things the tests caught: (1) `runTest`'s virtual clock fires `withTimeout` the moment
  a coroutine waits on a real thread — the port's contract must run under `runBlocking`;
  (2) a listener that throws used to kill the driver's reader thread silently — now a
  `ListenerFailed` event, session carries on.
- Known gap: an ExecType=I answer carries no execution list, so `lookup().executions` is
  empty until the daily execution file (plan 2.7). Documented in FixMapping.

Next: stage D — chaos knobs on the venue's admin endpoint, guarantees 2 and 4 in the
contract, and the "do callbacks slow the venue" measurement.

## 2026-11-04 — 0.3b stage D: chaos knobs and the slow-consumer measurement

- `ChaosKnobs` on the engine, live through `PUT /admin/chaos` on the venue's HTTP port:
  fillDelay, partialFills, rejectRate, duplicateReportRate (same ExecID twice, new seq),
  phantomReportRate (a Trade for a FILLED order, ExecID `P-n`, order untouched). Actions:
  `/drop-connection` (socket closed, no Logout), `/reset-sequences` (venue forgets its
  counters; next Logon answered with 141=Y; the broker accepts the imposed reset, both
  restart at 1 — spec 4.4.3, now a FixSession feature with a test), `/price/{symbol}/{mid}`.
- Contract guarantees 2 and 4 added to `VenueContract` behind an `induceDuplicateReports()`
  hook: a report delivered twice carries the same ExecutionId and content under a new
  sequence number. Six contract tests green against the FIX stack; the in-process engine
  (chapter 3) will skip these two until it can be told to duplicate.
- `SessionDriver` grew a bounded outbound queue with a writer thread (`outboundQueueLimit`,
  0 = inline for comparison). A full queue drops the peer as a "slow consumer"; the store
  has every message, so the peer recovers by resend. Logout still flushes through the queue.

**The measurement** (broker listener sleeps 50 ms per report; 16 KB socket buffers so the
burst cannot hide in the kernel; venue sends as fast as it can for 3 s):

| mode | sends returned | send p50 | send p99 | outcome |
|---|---|---|---|---|
| inline (write under the lock) | 456 | 83 µs | 243 µs | sender WEDGED in a socket write, lock held, 0 heartbeats, no disconnect — the venue is hostage |
| queue = 2000 | 2482 | 14 µs | 94 µs | queue full → `Disconnect(slow consumer)`, venue free |

First attempt with default (multi-MB) buffers was also instructive: 20,000 reports never
blocked at all, and both modes lost the broker by the heartbeat rule ~2.4 s later, because
its reader thread was stuck in the slow listener and never answered the TestRequest.

Verdict on "callbacks slow the venue" (my 0.3 answer): FALSE as stated, TRUE in one specific
shape. A push does not cost the venue anything while the consumer keeps up or the buffers
absorb the burst. It costs everything the moment a blocking write happens inside the
session's critical section. The fix is not polling: it is a bounded queue, a writer thread,
and the decision to drop a consumer that cannot keep up, which sequence numbers make safe.
Same lesson as the conflated feed in the interview (Q3): bound the queue, disconnect the
slow client, let it resume from a sequence number.

0.3b DONE. Chapter 0 left: 0.4 numbers sheet, 0.4b load generator, 0.6 reflect.

## 2026-11-04 — Module layers: the architecture rule book

- Awais brought a generic modular-monolith rule set (api / application / domain /
  infrastructure inside each module, contract ownership table, dependency direction,
  event delivery rules). Reviewed against the repo: already true in shape, different in
  package layout. Adopted with three adaptations, written down in `docs/architecture.md`:
  Modulith needs `api` declared as a named interface (one `package-info.java` per module,
  Java because Kotlin has no package-info); the Venue port moves from the public package to
  `orders.application` because an outbound port belongs to the consumer's application layer;
  `fix` (protocol library) and `venue` (second executable, the counterparty) stay outside
  the monolith by design.
- Orders is reshaped: `domain` = Price, Quantity, Side, TimeInForce, the four ids,
  Instrument; `application` = Venue port, NewOrder, CancelOrder, ExecutionReport,
  VenueOrderView, TopOfBook, VenueUnavailableException (the port's vocabulary);
  `infrastructure.fix` = FixVenue, FixMapping, FixVenueConfig. `VenueContract` moves with
  the port. The other four modules get their empty `api` named interface now, so chapter 1
  builds ledger in this shape from day one.
- `LayersTest` (ArchUnit, in `app`) guards the inside of a module: domain imports no other
  layer and no framework; application never imports infrastructure; api depends only on
  api, the JDK and Kotlin. `ModularityTest` keeps guarding the walls between modules; its
  map now shows `NamedInterface: name=api` on all five. 121 tests green.
- Rule worth remembering: an `api` DTO is self-contained. Modulith would reject
  `ledger.domain.Money` in another module anyway, so `api` types cannot lean on `domain`.
- Reviewed how a few production services organise their packages before settling the inner
  layout. Flat technical-role packages at the root (builder, mapper, projector, consumer) and
  a `domain` that also holds persistence and framework code scale by file count only; the
  readable parts were always the feature slices. Rule adopted for brokerage: inside
  `application` and `domain`, sub-packages are concepts or use cases, never roles; role names
  only inside `infrastructure` feature slices; split at about a dozen files; create a
  sub-package when its chapter starts. First application of it: the port and its vocabulary
  moved to `orders.application.venue`, leaving room for `placement` and `reconciliation`.
  Worth borrowing in chapter 2: a command → executor state machine with a per-state table of
  allowed commands, with the table kept in one tested domain file.

## 2026-11-04 — 0.4: the numbers sheet

- `docs/numbers.md` filled from the interview inputs plus five own guesses (250 trading days,
  6.5 h session, 1.2 fills per order, 10 book events/s per instrument in drill mode, 7-day
  FIX log retention). Headlines: 48 commands/s and ≈ 100 FIX reports/s at peak against
  300 HTTP reads/s (1 : 6 writes to reads); ≈ 30 GB/year of which 20 GB in Postgres; the
  conflated feed is 20,000 updates/s ≈ 3 MB/s and does not change between 10 and 1,000
  instruments, because bandwidth scales with subscribers × subscriptions; the wire is never
  the first bottleneck, the slow consumer is.
- Measured the machine. Host NVMe: ≈ 320 fsyncs/s, 830 MB/s sequential. macgrant VM:
  ≈ 1,000 fsyncs/s, which is faster than the disk under it: VirtualBox acknowledges the
  flush from the host page cache. The honest durable-order ceiling is the host's 320/s
  (8× the 40/s peak); group commit is the way past it, and the three log strategies in
  chapter 3 differ mostly in who batches. The VM has 2 cores and 5 GB shared by everything,
  so CPU will saturate before disk in the load test.
- 0.6 answered early in section 7: WebSocket subscribers at 100× is the first input that
  breaks a physical limit (one network card); everything else at 100× is a software change
  on the same box. Second is ledger hot-row contention, which arrives before any size limit.

## 2026-11-04 — 0.4b: the load generator and the first latency numbers

- New `loadgen` module (a tool beside the monolith, outside the brokerage package tree like
  `fix` and `venue`): `Profile` (BASELINE = the interview workload, SKEWED = 90 % on one
  instrument), `OpenLoopScheduler`, `LatencyRecorder` on HdrHistogram, `Report` as the
  markdown table for `docs/numbers.md`, `Target` interface with `FixVenueTarget` as the
  first implementation. Quotes and portfolio reads are reported as "no target yet" until the
  broker has endpoints; the tool never invents numbers for them.
- **Open loop, not closed loop.** Requests go out on a fixed timetable whether or not the
  last one was answered, and latency is measured from the scheduled send time. A closed
  loop slows down exactly when the system does and hides the queue it causes (coordinated
  omission). The scheduler test proves it: a target that takes 400 ms still receives 50/s.
- Outcomes are OK / rejected / timeout / error / skipped, counted separately; only OK shapes
  the percentiles, so a fast reject can never flatter p99 (numbers.md section 6).
- Two wrong runs before the right one, both worth keeping:
  1. Run at 20:24 against the venue app: every submit "OK", every cancel rejected, no fills.
     The venue's session had closed at 17:30, so it answered each order with Rejected, and
     the adapter's `submit` treats any first report as a receipt. Fix: the target reads the
     first report itself (New or Trade = OK, Rejected = rejected) and the fill row counts
     Expired/Rejected, so a run outside the session is visible in the table.
  2. After restarting the venue with a late close: every submit timed out. The venue's
     `FileMessageStore` had kept the sequence numbers from run 1; the generator's fresh
     in-memory session logged on at 1, which is "sequence too low" to the venue. This is the
     restart exercise from day 0 happening for real. Fix: `FixVenueConfig.resetOnLogon`
     (141=Y), right for a throwaway session, wrong for the broker, whose store must carry the
     numbers across restarts.
- **First numbers** (baseline 40 orders/s + 8 cancels/s, 60 s, host loopback, no broker in
  the path): order acknowledgement p50 3.9 ms, p99 12.3 ms, max 33 ms, 2,400/2,400 OK;
  cancel p99 14.9 ms, 444/444; first fill p99 17 ms, 1,947 filled. PASS against the 100 ms
  mark with 8× headroom, and this is the floor: everything the broker adds in chapters 1–2
  (ledger hold, persistence, HTTP) sits on top of ~12 ms.
- Full build: 129 tests (fix 89, venue 21, loadgen 8, orders 6, app 5).

Chapter 0 left: nothing. 0.6 was answered in numbers.md section 7. Next: chapter 1 ledger,
which needs the `brokerage` Postgres database in macgrant.

## 2026-11-04 — Chapter 1 opens: the ledger owns its database connection

Awais writes the code from here; this journal records his decisions.

- **Database shape (Awais's decision, stricter than the plan's "schema per module").** One
  Postgres database `brokerage`; one schema *and one role* per module (`ledger`, `orders`,
  `engine`, `marketdata`, `portfolio`), each role owning only its schema; plus a reader role
  `brokerage` with USAGE and SELECT on every schema, including tables the modules' Flyway
  creates later (default privileges). The wall between modules is therefore in Postgres, not
  only in the Modulith test: the ledger's connection cannot see the orders schema at all.
  Proven with psql: ledger creates in `ledger` (ok), in `orders` (permission denied); the
  reader selects from a table created after provisioning (ok), inserts (permission denied).
  macgrant change kept as a separate puppet block; the siloverse block was not touched.
- **What that costs in the app.** Spring Boot auto-configures one datasource; we have one per
  module, so each module wires its own four beans by hand in `<module>.infrastructure
  .persistence`: Hikari pool (3 connections, the VM has two cores), `JdbcClient`,
  `JdbcTransactionManager`, and a Flyway on that pool with `schemas("ledger")` and
  `locations("classpath:db/migration/ledger")`. Bean names carry the module name because
  orders will define the same four next chapter. Rule that follows: every `@Transactional`
  names its manager (`transactionManager = "ledgerTransactionManager"`), or the first call
  fails the day the second datasource appears. The jdbc auto-configurations are excluded
  in `application.yml` so nothing is wired by accident in the single-datasource window.
- **Ledger uses JDBC, not JPA.** Chapter 1 is conditional updates, row locks and isolation
  levels; the SQL is the lesson and an ORM would hide it.
- Two surprises worth keeping: (1) `@Configuration class ... may not be final`: Kotlin classes
  are final and the `jvm-library` convention does not apply the Kotlin Spring plugin (the
  Spring Boot apps get it from their convention). Business modules with Spring beans add
  `id("org.jetbrains.kotlin.plugin.spring")`. (2) `*.macgrant-platform.test` stopped resolving
  on the host: a `vagrant up` started from a background job cannot answer sudo, so the host
  DNS trigger failed silently; `sudo scripts/configure-host-dns.sh` in a real terminal fixes it.
- Proof: `:app:bootRun` applied the baseline migration; `\dt ledger.*` as the reader shows
  `ledger.flyway_schema_history` owned by `ledger`. Build green, 129 tests.

Next: DDIA chapter 7, then 1.0 balance equations written here before any table.

## 2026-11-19 — 1.0 Balance equations and per-asset rules

Written before any table, as the plan demands. The tables in 1.1 must obey every line here.

### 1. The three equations

For one account:

- `booked = sum of its ledger entries`. What the account truly holds.
- `reserved = sum of its open holds`. What is set aside for orders that are still alive.
- `available = booked − reserved`. What the owner can still use.

Stored or computed:

| Value | Truth lives in | Also stored on the account row? | Why |
|---|---|---|---|
| booked | the entries, append-only | yes, as a cached column | summing every entry on every order is too slow; the cache is updated in the same transaction that writes the entries |
| reserved | the open holds | yes, as a cached column | same reason, and it gives every hold one row to lock (see the danger below) |
| available | nothing of its own | yes, as a column with `CHECK (available >= 0)` | the database itself then refuses an overdraft, whatever the code does |

The entries and the holds are the truth. The three columns are a copy kept for speed and for
the lock. A nightly check (1.4, chapter 9) recomputes them from the truth and must find no
difference. Rule: the cached columns change only in the same transaction as the entry or hold
that causes the change.

### 2. What a hold is

A hold is a reservation against one account for one order. It moves nothing and writes no
entry. It only raises `reserved`, so `available` drops and the same money cannot be promised
twice.

- **Buy order:** hold cash = limit price × quantity + the largest fee that could apply.
- **Sell order:** hold the shares, on the position account.

How a hold ends:

| Event | What happens to the hold | Entries written? |
|---|---|---|
| fill (whole or part) | consumed by the filled amount | yes: money and shares really move |
| cancel | the rest is released | no |
| expiry at session close | the rest is released | no |
| reject by the venue | all of it is released | no |

Only the venue's word ends a hold: a Canceled, Expired or Rejected report, or a fill. The
broker never releases on its own guess (guarantee 6 of the Venue port).

A hold has an amount, a consumed amount and a released amount.
`open part = amount − consumed − released`; the hold is closed when that is zero.
`reserved` is the sum of the open parts.

**Price improvement.** The hold was sized for the limit price. If the fill is cheaper, the
fill consumes only what it really cost, and when the order is fully filled the leftover open
part is released, with no entry. The money was never moved, so there is nothing to move back.

### 3. Two kinds of asset, balanced separately

- **Cash:** an amount in minor units (cents) of one currency. Long, never a float.
- **Position:** a number of whole shares of one instrument. Long.

An account holds exactly one asset: Alice has one EUR account and one account per instrument
she owns. Euros and shares are different things and are never added together.

**Balanced** means: inside one transfer, the entries for each asset sum to zero on their own.
A fill touches two assets, so it must balance twice:

| Entry | Asset | Amount |
|---|---|---|
| Alice cash → venue settlement cash | EUR | −5000 / +5000 |
| Alice cash → fee income | EUR | −100 / +100 |
| venue position → Alice position | ACME | −10 / +10 |

EUR sums to zero. ACME sums to zero. A transfer where either sum is not zero is refused.

### 4. Account types

| Type | What it is for |
|---|---|
| user | a customer's cash or position; the only type with holds, and the only type that may never go below zero |
| fee income | where the broker's fees land; the house's earnings |
| venue settlement | the other side of every trade and of every deposit and withdrawal; stands for the outside world, so it may be negative |
| suspense | a parking place for money or shares we cannot explain yet (a report for an unknown order, a mismatch found by reconciliation); it should be empty, and anything in it is a task for a human |

### 5. Decision: one ledger with an asset column

One `account` table and one `ledger_entry` table, each row carrying its asset (kind + code:
`CASH/EUR`, `POSITION/ACME`). Not two separate ledgers.

Why: a fill is one business fact that touches both cash and shares. With one ledger it is one
transfer, written in one transaction, balanced per asset by one rule. With two ledgers the
same fill would be two transfers that must be kept together by extra code, and that seam is
exactly where money and shares could drift apart. Holds, idempotency keys, the audit trail
and the nightly check are also written once instead of twice.

What we give up: the database cannot enforce "cents here, shares there" by table shape, so
the per-asset rule lives in code and in the nightly check; amounts of different meaning share
one column, so every query must filter or group by asset; and a mistake that sums across
assets is possible to write. The guard is the rule in part 3 and a test for it.

### The danger, and what stops it

Two buy orders from Alice arrive at the same moment. Each needs 51 EUR; she has 100
available. If both read `available`, both see 100, both pass, both insert a hold: 102
promised out of 100. In DDIA chapter 8 (2nd edition) terms this is **write skew**: each
transaction reads, decides, and writes a *different* row (its own hold), so neither blocks
the other under read committed or snapshot isolation.

What stops it: both holds must change the **same row**. Each hold does one conditional update
on the account row, `UPDATE account SET reserved = reserved + :x WHERE id = :id AND
booked − reserved >= :x`, and inserts the hold only if one row was updated. The second
transaction waits on the row lock, then re-checks the condition against the committed value
and fails. That is the book's "materialize the conflict", and it is why `reserved` is a
column and not only a sum. The `CHECK (available >= 0)` is the second net under it.
Task 1.5 proves each step with two psql sessions.

### Invariants the tables must keep

1. Entries are never updated or deleted.
2. Every transfer's entries sum to zero per asset.
3. `account.booked` equals the sum of its entries; `account.reserved` equals the sum of the
   open parts of its holds; `available = booked − reserved`.
4. A user account's `available` is never negative.
5. A hold changes only through fill, cancel, expiry or reject, each exactly once per venue
   report (the key is the UUID the orders module gave that execution when it first stored
   it; see the 1.1 entry).
6. The same idempotency key never creates a second transfer.

## 2026-11-20 — 1.1 The ledger tables

Awais wrote the migration by hand, one table at a time, and cut my first sketch down with
one rule: only what this chapter uses goes in; correctness constraints always stay; anything
speculative is named and left out. `V1__ledger_tables.sql`, four tables, 26 constraints.

- **accounts**: `id` bigint identity (internal, for foreign keys) plus `uuid` (public).
  `user_uuid` null for house accounts, tied to `type` by a check. `asset_type` + `asset_code`
  are the asset; for cash the code is the currency. `booked`, `reserved` cached;
  `available` is a generated column, so it cannot drift. The overdraft rule is one check:
  not a user account, or `booked − reserved >= 0`. One account per user per asset, with
  `NULLS NOT DISTINCT` so the house also gets exactly one per role and asset.
- **holds**: `reference` is the caller's UUID for what the reservation is for (today an
  order). Not a foreign key, and unique, so it is also the idempotency key for placing the
  hold. `amount`, `consumed`, `released`; no state column, the numbers say whether it is open.
- **transfers**: the header of one business fact. `kind` + `idempotency_key` unique. No
  amount, account or currency: a fill touches two assets and five accounts, so those live in
  the entries. Immutable.
- **ledger_entries**: transfer, account, signed amount. Bigint id only; nobody outside ever
  names an entry. Append-only. The truth behind `accounts.booked`.

Decisions made on the way, each one Awais's question:

- **Ids.** UUID (version 7, made in Kotlin; Postgres 16 has no `uuidv7()`) for what other
  modules see; bigint identity for internal keys. `GENERATED ALWAYS AS IDENTITY`, never
  `bigserial`: it refuses a hand-supplied id.
- **Idempotency key is always a UUID.** The venue's execution id is foreign text and only
  unique per venue. The orders module stores it at the gate (unique on venue + execution id),
  gives the row a UUID in the same insert, and passes only that on. A duplicate report dies
  at the gate; a duplicate event dies on `transfers_key_once`. The ledger never sees an
  outside identifier. Rule: uuid when we make the value, text when someone else does, and
  the gate turns the second into the first.
- **No optimistic locking.** Balances change by atomic statements
  (`SET booked = booked + :x`, with a `WHERE` condition for holds). A second transaction
  waits on the row lock and applies to the fresh value under read committed. No `version`.
- **Deposits take no hold.** A hold protects value that is about to leave an account that can
  run out. The settlement account cannot run out, and a hold on it would make one row the
  queue for the whole system.
- **Hold is a direct call, fill is an event.** Orders needs the answer before it sends the
  order; a fill is a fact nobody waits for. An adapter in `app` turns the event into the
  ledger's command, so the ledger imports nothing from orders.

Mentioned, not added: `state`, `updated_at` and a `transfer_events` table on transfers (when
a transfer can wait, fail or be reversed); a `reason` beside `holds.reference` (when a
withdrawal places a hold); the asset copied onto entries; scoping the key per user; and
every performance index (first two will be `ledger_entries(account_id)` and
`(transfer_id)`, after `EXPLAIN` shows the scan).

Proof: fifteen attempts to break a rule, run as the `ledger` role in a rolled-back
transaction, each refused by the constraint named for it (two were allowed on purpose: a
negative settlement balance, and the same key under a different kind).

One-time exception: `V1__ledger_tables.sql` replaces the empty `V1_0` baseline that was
already on main, because the only database that ever ran it was the dev VM. From here every
schema change is a new file.

Next: 1.2, deposits and withdrawals with an `Idempotency-Key`.

## 2026-11-22 — 1.2 Deposits (first half)

By hand first: the deposit as one psql script (transfer, two entries, two balance updates),
then two windows inserting the same key. The second insert waits for the first transaction
and is refused when it commits. That wait is the whole idempotency mechanism.

In code:

- **Packages.** DDD, feature first and role second, role folders from the first file:
  `domain/<aggregate>/{model,repositories}`, `application/<usecase>/{model,services}`,
  `infrastructure/persistence/{repositories,config}`, `infrastructure/web/{controllers,model}`.
  Repository interfaces live in `domain` beside their aggregate. Rule book updated.
- **Repositories without an ORM.** Three interfaces (`TransferRepository`,
  `AccountRepository`, `LedgerEntryRepository`) with `JdbcClient` implementations. Enums go
  to SQL as their names; a null user is matched with `is not distinct from`.
- **`DepositFundsService`**, one transaction on the ledger's manager. The transfer insert
  comes first with `on conflict do nothing returning id`: a row back means "new, go on", no
  row means "seen before, return the first receipt". Then both accounts are ensured, two
  entries written (+user, −settlement), and both balances moved with
  `set booked = booked + :amount`.
- **`POST /api/ledger/deposits`** with an `Idempotency-Key` header (a UUID). 201 for a new
  deposit, 200 for a replay, 400 for a bad amount.
- **V2** makes `transfers.idempotency_key` unique on its own. A key names one request; the
  kind adds nothing to that.

Tests: the service with Mockito mocks (`BaseUnitTest` carries the extension), and the
endpoint against a real PostgreSQL 16 container started by the `jdbc:tc:` URL in the test
configuration (`BaseIntegrationTest`). Ten concurrent requests with one key book once: one
201, nine 200. Twenty concurrent users leave every balance equal to the sum of its entries.

Mentioned, not added: loading Mockito as a start-up agent (the self-attach warning is
harmless on Java 21; it belongs in the build convention plugin); a test that stops the
database mid-transfer (needs the container object, so the bean style of Testcontainers).

Not yet done: the curl run against the VM database, which also applies V2 there.

Next: withdrawals, where `accounts_no_overdraft` must become a clean refusal.

## 2026-11-22 — 1.2 Withdrawals (second half)

A withdrawal is a deposit with the signs reversed (−user, +settlement) and one new rule: it
can be refused.

- **The refusal is in the update.** `takeFromAvailable` runs
  `update accounts set booked = booked - :amount where id = :id and available >= :amount`.
  One row changed means the money was there and is taken; zero rows means not enough.
  Checking and taking are one statement, so two withdrawals cannot both pass the check. The
  service throws `InsufficientFundsException`, the transaction rolls back, the controller
  answers 409. The `accounts_no_overdraft` constraint stays behind it as the safety net.
- **Two gates, two jobs.** The unique idempotency key stops the *same* request booking
  twice. It does nothing for two *different* withdrawals from one account: those meet at the
  account row. The second waits for the row lock, and under read committed PostgreSQL
  re-checks the `where` against the committed row before it decides.
- **Lock order.** Every transaction updates account rows in ascending `accounts.id`, in the
  deposit and the withdrawal alike. Two transactions then meet at the lowest shared row and
  the waiting one holds nothing the other needs, so no deadlock can form.
- **`AccountIdentifier`** (id + uuid) is what the repository hands back for an account, so
  the refusal can name the account without a second query.
- **A refused key is forgotten** (Awais's decision). The rollback removes the transfer row,
  so the same key sent again is judged fresh and can succeed once the money is there.
  Remembering a refusal needs a state on the transfer and a committed refusal; that returns
  when transfers get a state.
- **A negative amount** is refused by the command itself, as for a deposit. Without that
  rule a withdrawal of −50 would have been a free deposit.

`POST /api/ledger/withdrawals`: 201 new, 200 replayed, 400 invalid, 409 insufficient funds.

Tests: ten withdrawals of 30 from a balance of 100 at the same moment give exactly three 201
and seven 409, balance 10. Ten deposits and ten withdrawals for one user at the same moment
all succeed with no deadlock. A refused withdrawal leaves no transfer, no entry, and for a
new user no account.

Mentioned, not added: retry on `deadlock detected` (40P01); one repository method that takes
all balance changes and sorts them itself, so the order cannot be forgotten; a limit on the
settlement account (it must never go above zero, which the user-side rule already ensures).

Not yet done: the two-window run by hand and the curl run against the VM database.

Next: 1.3, holds.
