# Review log — 004-role-names

## Baseline failures

- Base commit: `23451fb` (`main`), command: `make check` (gen, format-check, lint, backend `./mvnw verify`, typecheck, `pnpm -r test`)
- Result: 0 failing (backend 239 tests; ui-tokens 56, shared 5, web 45 unit tests). Backend line coverage 1,036 / 1,105 (93.8 %), floor 0.90.
- `make e2e` not part of the baseline (ports; not in `make check`); it was 29/29 green on the merged 003 branch.

## Slice 1 — Standard names shown from the new flag

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a35ddff..54971bf` | PASS | — | minor: contract ahead of server until Slice 2; `nameCustomized` not asserted on `/me` and member list; cleanup check misses the flag; list test relies on ordering |
