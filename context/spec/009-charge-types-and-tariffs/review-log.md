# Review log — 009-charge-types-and-tariffs

## Baseline failures

- Base commit: `eca55b9` (`main` = `origin/main`), command: `make check` in a fresh detached worktree.
- Result: 0 failing — backend 1,096 tests, 0 failures; coverage check met (line coverage 3,871 / 3,958 = 0.9780 vs floor 0.97); client tests ui-tokens 56, shared 67, api-client 7, web 241.
- `make e2e` not part of the baseline; it was 81/81 green on the 008 branch.

## Slice 1 — Permissions and charge types

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a6cdf22..1b100ae` | PASS | — | minor: audit label/old name of an unrenamed seeded type is the stored English default; concurrent creates may share a position (harmless, ordered by position, id); `ChargeTypeAuditTests` counts all CHARGE_TYPE entries after a sleep (could flake on a slow machine); test cleanup `delete from charge_type` will hit the RESTRICT FK once Slice 4 adds demo tariffs; Ukrainian literals in Java tests fall under the default-names exception (name it in the CLAUDE.md proposal); Treasurer description not mentioning tariffs (kept unchanged by design) |
