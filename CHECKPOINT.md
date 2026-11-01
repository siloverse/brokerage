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
