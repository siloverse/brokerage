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
        api/              public operations, command contracts, DTOs, published events
        application/      use cases, orchestration, transaction boundaries, outbound ports
        domain/           business models, invariants, domain services, domain interfaces
        infrastructure/   persistence, messaging adapters, HTTP controllers, external clients
    src/test/kotlin/io/github/siloverse/brokerage/<module>/
```

- `api` is what other modules may import. Nothing else in the module is visible to them.
- `application` owns the use cases and the **outbound ports** those use cases need. A port
  is an interface written in the consumer's words (the Venue port lives in
  `orders.application`).
- `domain` keeps business rules free of Spring, persistence, and transport. It may depend
  on the JDK and Kotlin only.
- `infrastructure` holds the adapters: the FIX venue adapter is `orders.infrastructure.fix`.
- These are packages, never Gradle subprojects. A new subproject needs a different
  consumer or a different lifecycle, not a different layer.
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
the JDK, and Kotlin.

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

## 4. Who owns a contract

Inbound and outbound describe direction relative to a module. They do not decide ownership.

| Contract | Declared by | Where |
|---|---|---|
| Public command asking for an action (`HoldFunds`) | the module that handles it | handler's `api` |
| Integration event announcing a fact (`TransferBooked`) | the module that produced the fact | publisher's `api` |
| Outbound port describing a need (`Venue`) | the module that has the need | consumer's `application` or `domain` |

Commands use imperative names, events use past tense. A subscriber uses the publisher's
event type; it does not copy it because it is inbound. A consumer may translate an incoming
contract into its own model. Messages are never pooled into a generic `shared` or
`messages` project just because several modules use them.

## 5. Cross-module communication

- A synchronous call to another module's `api` when an immediate result is needed. A
  command can be a plain Kotlin object handed to a function; no bus, no broker.
- An event when decoupling pays for itself (`TradeExecuted` to the ledger). Not for every
  interaction.
- A direct dependency on a provider's `api` when it is intentional and acyclic.
- When the direction must be inverted, an outbound port in the consumer and an adapter
  that implements it. The adapter lives in the consumer's `infrastructure` when that adds
  no unwanted dependency, otherwise in `app`. The adapter translates and delegates; the
  business decision stays in the owning module.
- Check the compile-time graph. If `orders` imports `ledger`'s commands and `ledger`
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

## 7. Build rules

- Versions live in the catalogs: the shared `libs` catalog from siloverse-build and the
  local `gradle/dep.versions.toml`. No version strings in a module's build file.
- Reuse the platform's Kotlin, Java, Spring Boot, and Gradle versions through the
  convention plugins.
- No `common`, `shared`, or `utils` modules. See section 1 for the one exception, `fix`.
- No microservices, no network calls between local modules, no separate databases to
  "enforce" modularity. The tests enforce it.
- Tests cover business rules, module interactions, and the dependency rules above.
