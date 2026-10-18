# Review log — 006-plots-registry

## Baseline failures

- Base commit: `48cd8e0` (`origin/main`), command: `make check`
- Result: 0 failing tests (backend 278 tests, 0 failures, 0 errors), but **the JaCoCo coverage check failed**: line coverage 1,084 / 1,154 = 0.9393 < floor 0.94 → `BUILD FAILURE`. The 005 branch measured 1,085 / 1,154 = 0.9402 on the same code, so one line is covered only in some runs (timing-dependent path) and the 0.94 floor is flaky on main. 006 must end clearly above the floor; the flakiness is reported to the owner.
- `make e2e` not part of the baseline; it was 48/48 green on the merged 005 branch.
