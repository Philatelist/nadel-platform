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

## Slice 7 — Push on a device and the first push

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `3d5c872..963bebc` | PASS | — | minor: `IllegalStateException` from the crypto escapes `WebPushClient.send` (JobRunr default retry); a failed reschedule drops the push with a WARN; V39 already holds `push_throttle` (Slice 8); `buildPushView` codes of Slice 8 untested until then |

## Slice 8 — The other pushes and removal on leaving

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `963bebc..d6c79f1` | PASS | — | minor: `useScrollToHash` `decodeURIComponent` throws on a malformed hash (`#%`) inside an effect; throttle claimed before enqueue (an enqueue failure spends the hour); no bot-path test for ReadingSubmitted (shared code); a redundant `memberId != null` guard |

## Slice 9 — Phone layouts at 360 px

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `d6c79f1..0e2154d` | PASS | — | minor: unused `cleanupPhoneFixtures`; the invoices phone test issues invoices for demo plots 7 and 15 (append-only); `[&>a:only-child]` in HelpContent also matches prose list items; a no-op `+ 0`; the header link's padded hit area overlaps the line below |

## Slice 10 — Help pages, owner manual and screenshots

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `0e2154d..731537e` | MAJOR_ISSUES | M1 settings help (uk/en) says the bot is not affected by the photo rule; the bot refuses readings while it is on | minor: HEIC conversion wording (only where the browser decodes HEIC); push page could mention the hourly limit of the approvals push |
| 2 | + `795652b` | PASS | M1 resolved | minor: the generic typeNotAllowed message for HEIC |

## Slice 11 — Feature Testing & Regression

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `795652b..a1fb27b` | PASS | — | minor: qa-notes sign-out row should also name `pushDevice.test.ts` signOutCleanup (no e2e of sign-out with push on); acceptance test 2 does not assert the push URL `/account#my-invoices` |

## Fix — full-suite JaCoCo floor (0.97646 < 0.977)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a1fb27b..5bd77bc` | PASS | — | minor: a long Javadoc line in PushSender; `unsignableKeys()` mocks are coupled to VapidSigner internals |

## Fix — CI SAST (Semgrep: unencrypted-socket, gcm-no-tag-length)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `ef20107..6006e4a` | PASS | — | — |

## Fix — CI End-to-end (pay-from-phone «Copied» strict mode)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `6006e4a..5c68807` | PASS | — | minor: `statusOf` depends on the CopyButton DOM shape (a data-testid later) |

### Fix review — owner decisions (13eae91), round 1: MAJOR_ISSUES
- M1: `HelpShot` kept its failed state when the page changed (keyed by index). Fixed: keyed by image and locale; unit test `HelpContent.test.tsx` re-renders with another screenshot. Minor notes taken: expiry comment in `cacheNames.ts`. Not taken: QueryBoundary brief offline flash for the always-mode help query (acceptable, the offline banner is true); e2e "seen" check could also pass from the HTTP cache (the cache path is asserted first).
