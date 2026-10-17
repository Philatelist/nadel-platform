# Review log — 003-ui-design-system

## Baseline failures

- Base commit: `e85f746` (`main`), command: `make check` (gen, format-check, lint, backend `./mvnw verify`, `pnpm -r typecheck`)
- Result: 0 failing (backend 239 tests, 0 failures, 0 errors; format, lint and typecheck green)
- Note: the first attempt errored in 173 backend tests because the Docker daemon was not running; Docker Desktop was started and the suite re-run green. `make e2e` was not part of the baseline (it needs ports 8080/5173 and is not in `make check`).

## Slice 1 — New tokens, self-hosted IBM Plex fonts and unit-test tooling

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `f9e5866..0b35db5` | MAJOR_ISSUES | M1 `MapPage.tsx:58` reads removed `--color-bg` | minor: serif headings keep `font-bold` (only 600 loaded); no `--text-*` size variables; mobile deps order; theme test resolves path from cwd |
