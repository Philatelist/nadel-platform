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

## Slice 2 — The recalculation planner (pure)

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `167f42e..0ef7ec0` | PASS | — | minor: `Plan.isEmpty()` ignores queue-only outcomes (Slice 3 decides); digest line text has no plot/charge type id; `closed(...)` repeats the month-order rule of `BillingCalendar`; `ExpectedCharges` map put could overwrite (assertion); requirement §3 closed-September reading dates cannot put the first interval in September — tests shift them one month (amounts unchanged), align the doc in Slice 8 |

## Slice 3 — Manual recalculation of tariff charges

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `0ef7ec0..4e38655` | MAJOR_ISSUES | M1 empty reason only disables "Провести перерахунок"; "Вкажіть причину" never shown (key unused, uk text "Вкажіть пояснення") | minor: field errors other than `reason` hide the Notice; label "Пояснення" vs "причина"; booking without digest skips the stale check (MonthRun pattern); `findOfPlotsAndTypes` loads all history then filters by month |
| 2 | + `c6926e6` | PASS | M1 resolved | minor: `reasonTooLong` still says "Пояснення задовге" |

## Slice 4 — Intervals and fixed-percentage losses

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `c6926e6..bd66a08` | MAJOR_ISSUES | M1 an INTERVAL item opened after a recalculation REVERSE is marked RESOLVED by the next retry/month run without charging (the reversed original keeps its key, `charged` predicate says AlreadyCharged); same pattern for TARIFF_MONTH items from Slice 3 | minor: NEW interval line after a back-dated version shows reason METER_TREE (ChangeReasons fallback); current losses mode applied to past fixed-losses charges (document); all-history load then in-memory filter; deviations 1–3 accepted |
