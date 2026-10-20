# Review log — 008-excel-import

## Baseline failures

- Base commit: `bcddbbb` (`main` = `origin/main`), command: `make check` in a fresh detached worktree.
- Result: 0 failing — backend 691 tests, 0 failures; coverage check met (line coverage 2,019 / 2,101 = 0.9610 vs floor 0.96; the same code measured 0.9676 in the main checkout, so the local measurement varies by about 0.007 between environments); client tests ui-tokens 56, shared 65, web 186.
- `make e2e` not part of the baseline; it was 72/72 green on the 007 branch.

## Slice 1 — Import page and template download

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `b437cdf..2eb0635` | PASS | — | minor: a 2xx response with broken JSON now yields `undefined` instead of throwing (strict parse suggested for success); `downloadBlob` revokes the URL right after click (older Safari); template file name kept in i18n though not translated text; PR must carry the dependency note |

## Slice 2 — Upload and preview of the Plots sheet

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2eb0635..f51d542` | MAJOR_ISSUES | M1 the size-limit message differs from the spec's required wording ("Файл завеликий — максимум 10 МБ" / "The file is too large — the maximum is 10 MB") | minor: DATE_INVALID hint says 2015-06-01 though only DD.MM.YYYY is accepted (picked up in Slice 4 web); commons-compress used directly but only transitive (declare it or record in ADR 0012); plot numbers lowered in Java vs SQL lower(); HEADER_MISSING row 1 vs "sheet level = row 0" docs; `save` instead of `persist` for the run row; slice commit subjects are not Conventional Commits (squash title is) |
| 2 | + `974bb6a` | PASS | M1 resolved (size message exactly as in the spec) | earlier minor notes still open (non-blocking) |

## Slice 3 — Members sheet in the preview

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `974bb6a..3e5c601` | PASS | — | minor: a member entered by hand without an email is duplicated when a file adds their email (follows the literal matching rule — owner to confirm); phones also split at newlines (docs say commas/semicolons); no length limit on the member key; label "Код члена"/"Member ID" vs "Ключ"/"Member key" in the template; an invalid row still blocks a later row as a duplicate; HEADER_MISSING row 1 vs row 0 docs |
