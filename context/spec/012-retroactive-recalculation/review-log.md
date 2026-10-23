# Review log — 012-retroactive-recalculation

## Baseline failures

- Base commit: `2e51e81` (`main`), command: `make -k check`
- Result: 1 failing check, 0 failing tests
- `format-check`: `docs/guide/en/board-guide.html`, `docs/guide/uk/board-guide.html` are not Prettier-formatted (left by PR #44). Fixed on this branch in a separate commit.
- Backend `./mvnw verify`: 2,144 tests, 0 failures, coverage checks met. Clients: ui-tokens 56, api-client 7, shared 130, web 577, all green.

## Slice 1 — Adjustments in the ledger and the recalculations list

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `676ebbb..167f42e` | PASS | — | minor: `?adjustments=1` is shared by every plot panel in "Мої нарахування"; recalculation link reuses `openCharge` key; long javadoc line in `Reversals`; `digest`/`reason` without `maxLength` in the contract (Slice 3 decides); `012:` commit subjects follow the pipeline convention, not Conventional Commits |
