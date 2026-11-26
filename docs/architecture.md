# Architecture rules

The brokerage is a **modular monolith**: one repository, one Gradle build, one process,
one Gradle subproject per business capability. This page is the rule book. The build
enforces most of it (`ModularityTest`, `LayersTest` in `app`); the rest is convention.
When a chapter needs an exception, write it down here first.

## 1. Project organization

- One root build. One subproject per cohesive business capability: `orders`, `ledger`,
  `engine`, `marketdata`, `portfolio`. Names come from the requirements interview in
  `tasks/brokerage.md`, not from a template.
- `app` is the Spring Boot entry point and the composition root. It is the only subproject
  that produces the brokerage executable. Every business subproject is a plain library.
- Everything runs and deploys as one process (`:app:bootRun`, port 10301).
- Two things sit outside the monolith on purpose:
  - `fix` is a protocol library (`io.github.siloverse.fix`). It has no Spring, no business
    rules, and a stable concept (FIX 4.4 session and application messages). It is the one
    "concrete, stable shared concept" that justifies a shared library. Nothing else does.
  - `venue` is a second executable: the fake exchange the broker talks to over FIX. It is
    the counterparty, not a module of the broker, so it depends on `fix` only. Its tests
    may depend on `orders` to run the Venue port's contract over a real socket.

## 2. Packages inside a business module

```text
<module>/
    build.gradle.kts
    src/main/java/io/github/siloverse/brokerage/<module>/api/package-info.java
    src/main/kotlin/io/github/siloverse/brokerage/<module>/
        api/              public operations, instruction contracts, DTOs, published events
        application/      use cases, orchestration, transaction boundaries, outbound ports
        domain/           business models, invariants, domain services, domain interfaces
        infrastructure/   persistence, messaging adapters, HTTP controllers, external clients
    src/test/kotlin/io/github/siloverse/brokerage/<module>/
```

- `api` is what other modules may import. Nothing else in the module is visible to them.
- `application` owns the use cases, one sub-package each (`ledger.application.deposit`):
  the instruction, the result, and the service that runs the steps inside one transaction. It
  also owns the **outbound ports** to other systems, written in the consumer's words (the
  Venue port lives in `orders.application.venue`). A repository is not such a port: it
  belongs to its aggregate in `domain`.
- `domain` keeps business rules free of Spring, persistence, and transport. It may depend
  on the JDK and Kotlin only. It follows domain-driven design: one sub-package per
  **aggregate** (`ledger.domain.account`, `ledger.domain.transfer`), holding the aggregate's
  types, its rules, and its **repository interface**. The repository is declared where the
  thing it stores lives; its implementation is in `infrastructure.persistence`.
- `infrastructure` holds the adapters: the FIX venue adapter is `orders.infrastructure.fix`.
- Inside `application` and `domain`, sub-packages are named after a **concept or use
  case** (`application.deposit`, `domain.account`) first. Feature first, role second:
  **inside** each of those, files are grouped by role from the first file on, with the same
  folder names everywhere: `model` (types, instructions, results; enums in `model/enums`),
  `services` (use case services), `exception` (the refusals a use case can throw),
  `repositories` (repository interfaces in `domain`, their implementations in
  `infrastructure.persistence`), `config`, and, where a design pattern is used, the
  pattern's own name: `command`, `strategy`. A role package never sits above a feature:
  no `application.services`, no `domain.repositories`. Role names such as `mapper`, `repository`, `controller` are allowed only
  inside `infrastructure`, and only within a feature slice (`infrastructure.web`,
  `infrastructure.persistence`). A feature that passes about a dozen files in one role
  folder is probably two features: split by sub-feature. A feature package is created when
  its chapter starts, never ahead of it.
- These are packages, never Gradle subprojects. A new subproject needs a different
  consumer or a different lifecycle, not a different layer.
- The word **command** is kept for the Command pattern: an object with `execute()` that
  packs one step for an invoker (`domain.account.command`). The input of a use case is an
  **instruction** (`FillInstruction`); what arrives over HTTP is a **request**
  (`FillRequest`).
- Design patterns are named with the words of *Design Patterns* (Gamma, Helm, Johnson,
  Vlissides) and *Head First Design Patterns*, in class names and in conversation. The
  ledger uses four:
  - **Command**: `Command` and its concrete commands in `domain.account.command`, with
    `AccountRepository` as the receiver and `TransferInvoker` as the invoker. The invoker
    writes one ledger entry per command and executes them in ascending account id.
  - **Strategy**: `FillStrategy` with `BuyFillStrategy` and `SellFillStrategy` in
    `domain.transfer.strategy`; `FillService` is the context.
  - **Template Method**: `TransferTemplate` in `application.transfer.services` is the
    abstract class; the deposit, withdrawal and fill services are its concrete classes.
  - **Chain of Responsibility**: `FundingHandler` and its three handlers in
    `domain.transfer.funding` decide where the money for a fill comes from: the hold, the
    free balance, the receivable.
- A domain class carries no Spring annotation. When Spring must manage one, a
  `@Configuration` class in the `application` layer declares the bean
  (`application.transfer.config`).
- Internal domain events stay inside the module. Integration events, the ones other
  modules subscribe to, are declared in `api`.

### How Spring Modulith sees this

Modulith's default is "the module's top-level package is public, every sub-package is
internal". We keep the public types in `api` instead, so each module carries a
`package-info.java` that marks `api` as a named interface:

```java
@org.springframework.modulith.NamedInterface("api")
package io.github.siloverse.brokerage.orders.api;
```

Kotlin cannot write `package-info`, so that one file is Java. `ModularityTest` in `app`
then fails any import of `application`, `domain`, or `infrastructure` from another module.
`LayersTest` adds the rules inside a module: `domain` imports no other layer and no
framework; `application` never imports `infrastructure`; `api` depends only on `api`,
the JDK, Kotlin, and two packages of the messaging library: `messaging.core.api` (the
`Event` marker an integration event must carry) and `messaging.core.naming` (the wire
names). Nothing else of that library, and no Spring.

Modulith does not include sub-packages in a named interface. When `api` is split
(`ledger.api.messages`, `ledger.api.messages.events`), each sub-package gets its own
`package-info.java` with the same `@NamedInterface("api")`; Modulith merges them into one
interface. The annotation's `propagate` switch is about something else and does not do this.

Kotlin `internal` is used where it does not fight the tests. The Venue port and its FIX
adapter stay public because the `venue` app's test runs the contract against them from
another Gradle module. The architecture tests are the wall; `internal` is a courtesy.

## 3. Module boundaries

- Each module owns its business rules and its data. In chapter 1 that becomes one Postgres
  schema per module in the shared `brokerage` database. No module reads another's tables.
- Other modules interact through the module's `api` or its published events.
- Public contracts never expose persistence entities or internal implementation types.
  An `api` DTO is self-contained. If two modules need the same value concept (money, an
  instrument), each declares its own until a stable shared concept earns a library.
- Gradle dependencies are explicit and acyclic. `app` depends on every module; modules
  depend on each other only when the direction is intentional.
- `app` holds startup, configuration, wiring, and cross-module adapters. No business rules.
  Its classes live under `io.github.siloverse.brokerage.app` (`app.config`,
  `app.consumers`), so Modulith sees one module `app` and not one per package.

## 4. Who owns a contract

Inbound and outbound describe direction relative to a module. They do not decide ownership.

| Contract | Declared by | Where |
|---|---|---|
| Public instruction asking for an action (`HoldFunds`) | the module that handles it | handler's `api` |
| Integration event announcing a fact (`TransferBooked`) | the module that produced the fact | publisher's `api` |
| Outbound port describing a need (`Venue`) | the module that has the need | consumer's `application` or `domain` |

Instructions use imperative names, events use past tense. A subscriber uses the publisher's
event type; it does not copy it because it is inbound. A consumer may translate an incoming
contract into its own model. Messages are never pooled into a generic `shared` or
`messages` project just because several modules use them.

## 5. Cross-module communication

Integration events travel through the siloverse messaging library, not through in-process
Spring events:

- The publisher calls `TransactionAwareAsynchronousBus.publish` inside its own transaction.
  That only inserts a row into `messaging_outbox`, on the module's own connection, so the
  event exists exactly when the business change does.
- The relay sends unpublished rows to RabbitMQ; a consumer in another module is a method
  marked `@Consumer(id = ...)`. The id names the queue and must not be renamed.
- Delivery is at least once. A consumer that changes state must cope with a repeat
  (`dedup = true` and an inbox table in its own database).
- The event class and its wire name are declared in the publisher's `api`. The wire name is
  written by hand (`ledger.transfer-booked`), never derived from the class name.
- The broker wiring is in `app.config`. Today there is one outbox, in the ledger's schema,
  because the library takes one `JdbcTemplate`.

- A synchronous call to another module's `api` when an immediate result is needed. A
  instruction can be a plain Kotlin object handed to a function; no bus, no broker.
- An event when decoupling pays for itself (`TradeExecuted` to the ledger). Not for every
  interaction.
- A direct dependency on a provider's `api` when it is intentional and acyclic.
- When the direction must be inverted, an outbound port in the consumer and an adapter
  that implements it. The adapter lives in the consumer's `infrastructure` when that adds
  no unwanted dependency, otherwise in `app`. The adapter translates and delegates; the
  business decision stays in the owning module.
- Check the compile-time graph. If `orders` imports `ledger`'s instructions and `ledger`
  imports `orders`' events, that is a Gradle cycle even if the events are asynchronous.
  Break it with a direction change or an adapter in `app`.

## 6. Data, transactions, events

- Every module states its transaction boundaries. The chapters record them in
  `CHECKPOINT.md` as they are built.
- One physical database, one schema per module, each module the only writer and reader of
  its schema.
- Every published event states how its handlers run: in the same transaction, after
  commit, or asynchronously. The default is Modulith's event publication registry: after
  commit, with a durable log so an in-process event is not lost on a crash.
- An in-process event is not retried by magic. Outbox, retries, and idempotency keys
  arrive where a chapter's reliability requirement calls for them (chapter 1 ledger first).

### Lock order in the ledger

Every ledger transaction takes its row locks in one order. A transaction that breaks it can
deadlock with any other.

1. The hold row, if the transaction has one.
2. The account rows, in ascending `accounts.id`.

- Balance changes go through commands and `TransferInvoker`, which executes them in that
  order. A service never updates an account row directly, except the hold service for its
  single account.
- A transaction that must read before it decides (a fill) locks first, with
  `HoldRepository.lockRest` and `AccountRepository.lockAvailable`, then decides, then
  writes. `lockAvailable` orders the rows in SQL (`order by id`); sorting a Kotlin list
  does not decide the order in which PostgreSQL locks.
- A locking read uses `for no key update`, the lock a normal update takes. `for update` is
  stronger and conflicts with the lock a foreign key takes on the row it points at: two
  fills that had each inserted a transfer for the same hold deadlocked on it.
- Inserts lock too: a foreign key locks the row it points at, and a unique index makes a
  second insert of the same key wait for the first.
- One exception, to be removed: a user's receivable account is found and changed after the
  other accounts are locked. It is safe today only because every transaction that touches
  it locks that user's cash or position account first.

## 7. Build rules

- Versions live in the catalogs: the shared `libs` catalog from siloverse-build and the
  local `gradle/dep.versions.toml`. No version strings in a module's build file.
- Reuse the platform's Kotlin, Java, Spring Boot, and Gradle versions through the
  convention plugins.
- No `common`, `shared`, or `utils` modules. See section 1 for the one exception, `fix`.
- No microservices, no network calls between local modules, no separate databases to
  "enforce" modularity. The tests enforce it.
- Tests cover business rules, module interactions, and the dependency rules above.
