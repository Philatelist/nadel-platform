# Review log — 005-ui-preferences

## Baseline failures

- Base commit: `5999343` (`main`), command: `make check`
- Result: 0 failing (backend 259 tests; ui-tokens 56, shared 5, web 65 unit tests). Backend line coverage 1,065 / 1,134 (93.92 %), floor 0.93.
- `make e2e` not part of the baseline; it was 33/33 green on the merged 004 branch.
