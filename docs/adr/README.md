# Architecture Decision Records

Format: context → decision → consequences. A new decision is a new file `NNNN-name.md`. Existing ADRs are not rewritten: if a decision changes, write a new ADR with status "supersedes NNNN".

| # | Decision | Status |
|---|---|---|
| 0001 | Modular monolith on Spring Boot + Spring Modulith | accepted |
| 0002 | Multi-tenancy: shared schema + tenant_id + Row Level Security | accepted |
| 0003 | Keycloak for sign-in and roles | accepted |
| 0004 | Contract-first API with OpenAPI | accepted |
| 0005 | TypeScript on all clients: React (web) and Expo (mobile) | accepted |
| 0006 | Append-only money ledger | accepted, implemented (specs 011, 012) |
| 0007 | Domain events through the Spring Modulith outbox; JobRunr for scheduled work | accepted |
| 0008 | Platform operator sees aggregates only; support access is granted and logged | accepted |
| 0009 | Configurable roles as permission sets per cooperative | accepted, implemented (spec 002) |
| 0010 | Hosting: one EU virtual server with Docker Compose, deployed from GitHub Actions | accepted |
| 0011 | Cooperative settings module; ownership inside plots; synchronous veto and read SPIs instead of events | accepted, implemented (spec 007) |
| 0012 | Excel import module: facades in members and plots, preview stored in the database, synchronous two-pass commit | accepted, implemented (spec 008) |
| 0013 | Money module: `Money` is the only place that rounds, `Rate` (4 decimals, never rounded) is not money | accepted, implemented (spec 009) |
| 0014 | Meter quantities: `MeterValue` is not money, consumption is exact rational arithmetic, calculated values are rounded once for display | accepted, implemented (spec 010) |
| 0015 | Report templates: an own streaming OOXML filler in `reports` (no Apache POI), POI 5.x as the recorded fallback; supported placeholders, one repeating row per sheet, A1 reference shifting, no formula evaluation | accepted (spec 013) |
| 0016 | Personal data encryption: a data key and an index key per cooperative wrapped by one master key from the environment, AES-256-GCM bound to its row in the application, HMAC blind indexes for exact search, rotation and key-check at start-up in Flyway | accepted (spec 014) |
