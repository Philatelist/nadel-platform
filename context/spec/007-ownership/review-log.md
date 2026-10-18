# Review log — 007-ownership

## Baseline failures

- Base commit: `0af7859` (`main` = `origin/main`), command: `make check` in a fresh detached worktree.
- Result: 0 failing tests (backend 427 tests, 0 failures, 0 errors; client checks green), but **the JaCoCo coverage check failed locally**: line coverage 1,352 / 1,426 = 0.9481 < floor 0.95 → `BUILD FAILURE`. CI on the same commit (run 39166627032) passed the coverage check, and the 006 branch measured 0.9586 locally. The floor is environment-sensitive; 007 must end clearly above it on CI and locally.
- `make e2e` not part of the baseline; it was 57/57 green on the 006 branch.
