# Review log — 021 member PWA

## Baseline failures
- Base: origin/main @ 3e39ab2, `make check` (contract, format, lint, backend `./mvnw verify` 4405 tests, clients typecheck/test): **none** (exit 0). e2e not part of the baseline.

## Slice 1 — Expo shell removed, ADR 0021 written

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `7cb7b53..8ffadbe` | PASS | — | minor: the diff deletes `mobile/CLAUDE.md` and `mobile/.claude/settings.json` (inside the requested `mobile/` deletion; flagged to the owner in owner-proposals.md); owner manual Expo lines left for Slice 10; `docs/roadmap.md` still says mobile apps; `nadel-mobile` realm client left on purpose |

## Slice 2 — Stand fixes and their script test

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `8ffadbe..9652767` | MAJOR_ISSUES | M1 navigations served by the Caddy catch-all lack `Cache-Control: no-cache` | minor: wrong admin password retried for 2 min; DER rebuild hardcodes 0x31 (correct for P-256) |
| 2 | + `51e4a15` | PASS | M1 resolved | — |

## Slice 3 — Paying from the phone

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `51e4a15..81269a4` | PASS | — | minor: an empty `role="status"` region per copy button; help `member-bill.md` update is Slice 10 |

## Slice 4 — A member submits a reading with a photo; the board sees it in approvals

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `81269a4..323e274` | PASS | — | minor: V38 also adds `reading_photo_required` (used by Slice 5, as the tasks plan); one `photos.has` query per round row (N+1); PNG > 2000 px becomes JPEG (transparency flattened); `photoError` not cleared by «Прибрати»; thumbnail contentType hard-coded |

## Slice 5 — The photo rule and photos from the board and the electrician

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `323e274..a4d349f` | PASS | — | minor: a javadoc line over 120 chars in ReadingsController; RoundRow photo buttons inside the form rely on Button's default type |

## Slice 6 — The installable app

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a4d349f..3d5c872` | PASS | — | minor: hourly update-check interval never cleared (single registration); `promptInstall` drops the event before `prompt()`; the SW contract test is source-text based; import order of `useDirtyGuard` |
