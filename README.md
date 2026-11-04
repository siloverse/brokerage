# brokerage

A learning brokerage: broker and exchange in one modular monolith. Built chapter by
chapter to exercise the ideas in the system design books; the plan lives in
`tasks/brokerage.md` in the code workspace. The module and package rules the build
enforces are in [docs/architecture.md](docs/architecture.md).

Run: `./gradlew :app:bootRun` (broker, port 10301) and `./gradlew :venue:bootRun`
(fake exchange, port 10401). Through macgrant: `https://brokerage.macgrant-platform.test`.

Load test: start the venue, then `./gradlew :loadgen:run --args="--profile baseline --duration 60"`
(profiles `baseline`, `skewed`; `--scale 10` multiplies the rates). The report lands in
`loadgen/build/loadgen/` and is pasted into `docs/numbers.md` section 8.
