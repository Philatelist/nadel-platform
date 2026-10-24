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
| 2 | + `6abf51c` | MAJOR_ISSUES | M1 resolved (open month); M2 closed-month path: an identity adjusted to 0.00 and queued still counts as charged (`ChargeKeys`), so the next retry/month run resolves it without charging | minor: `revisionsOf` substring scan cannot use the index; Javadoc/README wording narrower than the code; long README line |
| 3 | + `47aa592` | PASS | M2 resolved | minor: comment why `setAsideBy` adjustment branch ignores `:source`; two long Javadoc lines; no test for a chain set aside twice |

## Slice 5 — Losses and common consumers runs

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `bd66a08..e161d9a` | MAJOR_ISSUES | M1 a run's open/closed state is taken from the first live charge (a NEW plot of a closed run is booked in the open month), so a closed run can be reversed and rebooked; `rebookRun` takes the month from the first line | minor: losses mode not re-checked — after a switch PROPORTIONAL→FIXED a recalculation can add fixed losses and rebook the proportional run (double losses; decision/doc needed); a chosen plot pulls the whole run in (document); no test for RunAction.REVERSE or the plot-scope filter; known gaps (hand-reversed plot left out of a rebooked run; unknown node in a closed month gets no queue item) |
| 2 | + `0dbe2f1` | PASS | M1 resolved | minor: test fixture derives run months from the earliest REGULAR row; `requireNonNull` without a message; the fix commit carried the Slice 4 r2 log row |

## Slice 6 — Correcting tariff versions

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `47aa592..99f3084` | PASS | — | minor: inactive charge type → tariff cause gives an empty plan and saves silently (web blocks it; document or 409); moving an unused version's start into a charged month saves plainly (spec §2.4) unlike the add path; `tariffOf` loads the version before the tariff lock (stale-state 500 in a rare race); empty re-preview after PREVIEW_OUTDATED does not save; `statesInOrder()` read before `lockAllForShare()` |

## Slice 7 — Correcting readings

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `99f3084..23e808a` | MAJOR_ISSUES | M1 a corrected interval can be charged twice: after an ADJUST (closed month) or an empty plan no charge carries the new 011 key (`CONSUMPTION:…:{newReadingId}`), so a redelivered/republished `PlotMeterPointsChanged` books a full REGULAR charge again | minor: `RoundRow` builds the READING_ENTER cause from render-time values, not the refused request; approvals have no step 2 (record as a spec deviation in the spec assumptions and guides); reading scope uses all charge types (comment that it is intentional) |
| 2 | + `b45a8e2` | PASS | M1 resolved | minor: interval identity rule now exists in SQL and in `ChargeIdentity` (cross-reference or test); README could say a hand-reversed charge counts only by its own key |

## Slice 8 — Demo recalculation, docs, guides and end-to-end specs

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `b45a8e2..6a3618e` | MAJOR_ISSUES | M1 `web/e2e/recalculation.spec.ts:200-203` waits for the async reading charge with a single check and swallows a `bookMonth` failure (timing-dependent) | minor: demo recalculation has no RECALCULATION_BOOKED audit row and is MANUAL while the story is a version correction; demo status cache `oldest` does not skip adjustments; guide "Known limit" wording differs from money-rules §5.3 (pending reading in a charged interval); approvals step 2 still a recorded deviation; ReadingHistory change judged correct |
| 2 | + `1de90c8` | PASS | M1 resolved | minor: 15 s poll timeout is a literal; round-1 minors stand |

## Slice 9 — Feature Testing & Regression

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `1de90c8..bda3052` | PASS | — | minor: functional-spec "reading entered after the preview" wording is too strong — staleness means the plan changed (amend; optionally test a READING_CORRECT preview followed by the next reading); MyChargesPanel and the `/charges/recalculations/new` route have no own tests; WaterConnectionPanel test renders twice without cleanup; guide chapter checked by reading only |

## Fix — web typecheck TS2339 `onSuccess` in `ScopeEditor.test.tsx` (Phase 4 full suite)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `bda3052..e13dcea` | PASS | — | — |

## Fix — verify: §2.7 record lines link to the charges; §2.2/§2.3 labels and signs

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `e13dcea..50fac8f` | PASS | — | minor: ADJUST label would end in a dangling "в" if `bookedIn` were missing; `formatSignedMoney` has no unit test of its own; `?charge=` opens nothing when the date filter hides the charge; state setter naming; "Оригінал" link sits under the "Записи" column |

## Fix — e2e permission counts, tariffs-acceptance step, adjustment detail names its original

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `50fac8f..037e106` | MAJOR_ISSUES | M1 `tariffs-acceptance.spec.ts:225-227` books a recalculation for an ALL_PLOTS tariff: every run adds permanent charges to every demo plot (shared DB) | minor: missing-original fallback shows a bare month; fragile `not '5.00'` assertion; hard-coded 20 |
| 2 | + `4fa32b5` | PASS | M1 resolved | minor: loose `getByText(plot.number)` locator; a charged throwaway plot may stay unarchived (allowed by the leftover policy) |
