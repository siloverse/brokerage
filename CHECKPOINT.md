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
