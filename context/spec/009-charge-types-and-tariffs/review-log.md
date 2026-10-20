# Review log — 009-charge-types-and-tariffs

## Baseline failures

- Base commit: `eca55b9` (`main` = `origin/main`), command: `make check` in a fresh detached worktree.
- Result: 0 failing — backend 1,096 tests, 0 failures; coverage check met (line coverage 3,871 / 3,958 = 0.9780 vs floor 0.97); client tests ui-tokens 56, shared 67, api-client 7, web 241.
- `make e2e` not part of the baseline; it was 81/81 green on the 008 branch.
