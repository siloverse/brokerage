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
