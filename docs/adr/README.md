# Architecture Decision Records

Format: context → decision → consequences. A new decision is a new file `NNNN-name.md`. Existing ADRs are not rewritten: if a decision changes, write a new ADR with status "supersedes NNNN".

| # | Decision | Status |
|---|---|---|
| 0001 | Modular monolith on Spring Boot + Spring Modulith | accepted |
| 0002 | Multi-tenancy: shared schema + tenant_id + Row Level Security | accepted |
| 0003 | Keycloak for sign-in and roles | accepted |
| 0004 | Contract-first API with OpenAPI | accepted |
| 0005 | TypeScript on all clients: React (web) and Expo (mobile) | accepted, mobile part superseded by 0021 |
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
| 0017 | Documents: PDFs drawn with Apache PDFBox 3.0.8 and an own small layout layer, the embedded IBM Plex Sans (OFL-1.1), byte-stable files (fixed `/ID` and dates), rendered on request and never stored | proposed (spec 016) |
| 0018 | Delivery: each cooperative's own mailbox and bot, their secrets sealed in its data with its data key (the exception to "secrets only from the environment"), write-only in the API, long polling with a lease and take-before-answer instead of webhooks, two `SECURITY DEFINER` discovery functions returning ids only, a delivery register worked by JobRunr with a lease per lane | accepted (spec 017) |
| 0019 | Cash book: member payment movements written by a synchronous in-transaction listener (the one exception to the outbox rule), receipt files in the database (5 × 5 MiB, `STORAGE EXTERNAL`) with JDK-only metadata stripping, HEIC decoded only in the browser (no LGPL library), the migration-time backfill read of `payment`; V33/V34 reasons | accepted (spec 018) |
| 0020 | Member invitations: a confidential Keycloak client with three realm-management roles, a backend single-use token (hash stored, 72 h), the set-password page and the password set through the Admin API, a synchronous delivery SPI, the third `SECURITY DEFINER` function `members_invitation_tenant`, stored access state, the first-chairman command | accepted (spec 020) |
| 0021 | Phone access as a PWA: the web app installable with a service worker (vite-plugin-pwa, injectManifest, no runtime caching) and Web Push built on JDK-only crypto (RFC 8291/8292); the Expo app removed; meter photos stored like ADR 0019 | accepted (spec 021) |
