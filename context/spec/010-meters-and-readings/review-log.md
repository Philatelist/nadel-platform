# Review log — 010-meters-and-readings

## Baseline failures

- Base commit: `063af9f` (`main` = `origin/main`), command: `make check` in a fresh detached worktree.
- Result: 0 failing — backend 1,380 tests, 0 failures; coverage check met (line coverage 4,775 / 4,875 = 0.9795 vs floor 0.97); client tests ui-tokens 56, shared 79, api-client 7, web 345.
- `make e2e` not part of the baseline; it was 94/94 green on 009-tariff-version-correction (#33).

## Slice 1 — Data model, permissions and the consumption rules

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `d418b60..d201cfc` | MAJOR_ISSUES | M1 `parseMeterValue` ignores the locale: en "8,205" parsed as 8.205; M2 `formatMeterValue` keeps the server's trailing zeros ("8 205,000") against ADR 0014 "clients trim the zeros" | minor: `Quantity.toMeterValue()` throws `InvalidMeterValueException` above 11 integer digits (Javadoc); glossary states the Electrician demo grants before Slice 7 adds them; unused `MeterUnit` type spelled differently from the catalog keys |
