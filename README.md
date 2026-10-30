# brokerage

A learning brokerage: broker and exchange in one modular monolith. Built chapter by
chapter to exercise the ideas in the system design books; the plan lives in
`tasks/brokerage.md` in the code workspace.

Run: `./gradlew :app:bootRun` (broker, port 10301) and `./gradlew :venue:bootRun`
(fake exchange, port 10401). Through macgrant: `https://brokerage.macgrant-platform.test`.
