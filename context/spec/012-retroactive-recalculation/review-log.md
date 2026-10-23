# Review log — 012-retroactive-recalculation

## Baseline failures

- Base commit: `2e51e81` (`main`), command: `make -k check`
- Result: 1 failing check, 0 failing tests
- `format-check`: `docs/guide/en/board-guide.html`, `docs/guide/uk/board-guide.html` are not Prettier-formatted (left by PR #44). Fixed on this branch in a separate commit.
- Backend `./mvnw verify`: 2,144 tests, 0 failures, coverage checks met. Clients: ui-tokens 56, api-client 7, shared 130, web 577, all green.
