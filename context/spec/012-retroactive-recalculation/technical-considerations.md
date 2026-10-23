# Technical Specification: Retroactive recalculation

- **Functional Specification:** `context/spec/012-retroactive-recalculation/functional-spec.md`
- **Requirement:** `docs/features/012-retroactive-recalculation.md`
- **Status:** Draft
- **Author(s):** Alex Usov (pipeline; design input from the spring-backend, postgres-database and react-frontend
  specialists)

---

## 1. High-Level Technical Approach

The `ledger` module gets a **recalculation**: a pure planner compares the charges the 011 planners would make today
(**expected**) with the booked chains (**effective** = charge + its adjustments) per charge **identity**, and a booking
service writes the plan in one transaction. Nothing calculates money twice: the 011 planners (`TariffMonthPlanning`,
`ReadingChargePlanning`/`ReadingChargeCalculator`, `LossesPlanning`/`LossesCalculator`,
`CommonConsumerPlanning`/`CommonConsumerSplitCalculator`) are called with a "charged" predicate that ignores existing
keys (`k -> false`), so they return every expected charge of the scope.

Cause-driven changes (tariff versions, readings) are owned by the ledger's recalculation endpoint, which keeps the
existing dependency direction (`ledger → tariffs`, `ledger → meters`): `ledger` calls new public facades in `tariffs`
and `meters` that apply the change inside the ledger's transaction. A **preview applies the change, plans, and rolls
back**; a booking applies the same change, plans, checks the digest and writes. Preview and booking run identical
code, so the digests match by construction. The existing tariff and reading endpoints stay unchanged and keep answering
409 `TARIFF_VERSION_IN_USE` / `READING_IN_USE` for used data; the web then moves the dialog to the recalculation step.

A new charge kind `ADJUSTMENT` (source `RECALCULATION`) corrects closed months in the earliest open month. Reversals
made by a recalculation use source `RECALCULATION`. Every booked recalculation is an append-only `recalculation` record
with `recalculation_line` rows. One per-cooperative advisory lock serialises every charging path.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Database — `V19__recalculation.sql` (expand-only)

Set `lock_timeout` at the top. Constraints on `charge` are added `NOT VALID` then validated.

| Object | Change |
|---|---|
| `recalculation` | new: `id`, `tenant_id`, `cause` (`MANUAL`, `TARIFF_VERSION`, `READING`), `cause_ref_id` (no FK: the row may be removed by the cause), `scope jsonb` (months, charge type ids, plot ids, version/reading ids; ids and codes only), `reason` (1–500), `total numeric(14,2)`, `line_count`, `plot_count`, `digest`, `created_by` (member), `created_at`. `UNIQUE (tenant_id, id)`, indexes `(tenant_id)`, `(tenant_id, created_at desc)` |
| `recalculation_line` | new: `id`, `tenant_id`, `recalculation_id` (composite FK), `plot_id`, `charge_type_id`, `service_from`, `service_to`, `original_charge_id` (nullable composite FK to `charge`), `booked_amount`, `adjusted_so_far`, `expected_amount`, `difference` (all `numeric(14,2)`), `action` (`REVERSE_AND_RECHARGE`, `ADJUST`, `NEW`, `REVERSE`), `reasons text[]` (codes), `run_id` (nullable), `reversal_charge_id`, `new_charge_id`, `adjustment_charge_id` (nullable composite FKs to `charge`), `position`. Indexes `(tenant_id)`, `(tenant_id, recalculation_id)`, `(tenant_id, original_charge_id)` |
| both new tables | RLS + `tenant_isolation` policy (USING and WITH CHECK), `ledger_append_only` BEFORE UPDATE/DELETE and BEFORE TRUNCATE triggers, `nadel_app` INSERT/SELECT only (grants as in V17) |
| `charge` columns | `adjusts_charge_id uuid null` (composite FK to `charge (tenant_id, id)`), `recalculation_id uuid null` (composite FK to `recalculation`); partial indexes on each `WHERE … is not null` |
| `charge_kind_ck` | adds `ADJUSTMENT` |
| `charge_source_ck` | adds `RECALCULATION` |
| `charge_reversal_ck` | recreated: same rules for `REGULAR`/`OPENING` (amount > 0) and `REVERSAL` (amount < 0, `reverses_charge_id`, reason); `ADJUSTMENT` amount ≠ 0 |
| `charge_adjustment_ck` (new) | `(kind = 'ADJUSTMENT') = (adjusts_charge_id is not null)`; an `ADJUSTMENT` has source `RECALCULATION`, a reason and a `recalculation_id`; source `RECALCULATION` only on `ADJUSTMENT` and `REVERSAL` rows, which then have a `recalculation_id` |
| `charge_target_guard` (new BEFORE INSERT trigger, not SECURITY DEFINER) | an `ADJUSTMENT` targets a `REGULAR` charge of the same tenant that is not reversed; a `REVERSAL` never targets an `ADJUSTMENT` or a `REVERSAL` |
| `charge_adjustment_once_uq` | unique `(tenant_id, recalculation_id, adjusts_charge_id) WHERE kind = 'ADJUSTMENT'` |
| `tariff_version.deleted_at` | soft delete (a used version cannot be removed physically: `charge_part_tariff_version_fk` is `ON DELETE RESTRICT` and `charge_part` is append-only; this also fixes the unmapped FK error of 011); the unique index on `(tariff_id, valid_from)` becomes partial `WHERE deleted_at is null` |
| `seed_base_roles()` | redefined with `('CHAIRMAN','RECALCULATION_RUN')`; targeted insert for existing chairman roles (V13 pattern) |

Append-only triggers on `charge`/`charge_part` stay. `LedgerTenantIsolationTests.kindAndSourceAreChecked` (line ~279)
changes its invalid kind from `ADJUSTMENT` to a made-up one.

### 2.2 Backend

**Identity and effective amount** (`ledger.internal.ChargeIdentity`, pure):

| Charge | Identity |
|---|---|
| Tariff (month run, one-off) | `T:{tariff}:{plot}:{YYYY-MM}` (the base key, any `#…` suffix stripped) |
| Interval (consumption, fixed losses, water) | `I:{source}:{meter}:{fromDate}:{toDate}` from the calculation's readings — not the source key, because a corrected reading gets a new id |
| Losses run line | `R:{runId}:{plot}`; common consumers `R:{runId}:{consumer}:{plot}` |

A **booked chain** per identity: the `REGULAR` charges, their reversals and adjustments. Effective = Σ chain. A chain
whose charge was reversed by hand (`MANUAL_REVERSAL`) and never rebooked is **frozen** and skipped (hand reversals stay).
`OPENING` charges are never in scope.

**`RecalculationPlanner`** (pure; inputs: booked chains, expected charges per identity, month statuses, the earliest
open month): produces lines with the cases of the functional spec 2.1 — live charge in an open month and amount differs
→ `REVERSE_AND_RECHARGE`; expected absent → `REVERSE`; live charge in a closed month → `ADJUST` for expected −
effective (or −effective); no live charge and expected > 0 → `NEW`; equal → nothing. An expected `Failed`/`Conflicting`
result counts as absent and becomes a queue item, its reason on the line. `ChangeReasons` (pure) names why a line
changed by comparing booked and expected parts (version/rate, days, units/area, readings, water days, scope, run).

**Expected charges, refactors of 011 (no second implementation):**

| Class | Change |
|---|---|
| `MonthRun` | `expected(snapshot, plots, types, months)` extracted from `plan`; the run uses it |
| `IntervalCharging` | `expected(points, snapshot, window)`; `book` accepts a key override, reason and recalculation id |
| `LossesRuns`, `CommonConsumerRuns` | `plan(…, ignoreRunIds)`, `book(…)`, `reverseLocked(run, reason, source, recalculationId)` extracted from `run()`/`reverse()` |
| `DebtCalculator`, `AccountQueries`, `ChargeViews` | adjustments count in debts; a charge is unpaid while charge + adjustments > 0; account items carry `adjusts`, `adjustedBy`, `recalculationId` |
| `Reversals` | refuses an `ADJUSTMENT` (`ADJUSTMENT_NOT_REVERSIBLE`) |

**Runs:** a run follows its `billing_month_id`. Open month and any line differs → the whole run is reversed (source
`RECALCULATION`) and booked again for the same range and nodes/consumers; closed month → per-plot identity rules
(adjustments; a plot new to the run gets a new charge with the original `run_id`).

**Scope:** manual → months from–to (≤ current month, at most 24 months), charge types (active), plots (chargeable,
not archived, not common land); runs whose `to` lies in the months. Tariff version cause → plots in that tariff's
scope and the months the old and new validity touch, plus runs of that charge type overlapping them. Reading cause →
the meter's intervals around the old and new dates, and runs with `meter_value_use` rows in that window.

**Cause-driven facades** (new public API, `Propagation.MANDATORY`, own row locks, same validation as today, no usage
check, return the affected window and an unpublished `AuditEvent`):

- `tariffs.TariffVersionChanges` — `add`, `update`, `delete` (soft);
- `meters.ReadingChanges` — `enter`, `correct`, `delete`; they do **not** publish `PlotMeterPointsChanged` (the
  recalculation charges the window itself, so the listener cannot book a duplicate).

The existing endpoints keep their behaviour; editing only the note of a used version skips the usage check.

**Booking** (`RecalculationService`): one transaction, `TransactionTemplate`; a preview is the same code with
`setRollbackOnly()` on the outermost transaction. Order:

1. lock `ledger.charging:{tenant}` (new per-cooperative advisory lock; also taken first by the month-run booking, queue
   retries, the `ReadingCharges` listener, losses and common-consumer booking/reversal, and manual `Reversals`; nobody
   takes it while holding another lock), then the change's row lock, plot meters + per-meter locks in id order, the
   shared network if runs are in scope, the queue lock, tariffs and billing months `FOR SHARE`;
2. apply the change (cause-driven), compute expected, plan, compute the digest; a booking with a different digest →
   409 `PREVIEW_OUTDATED`; an empty manual plan → 409 `NOTHING_TO_RECALCULATE`; an empty cause-driven plan saves the
   change and books nothing (no reason needed);
3. write: `recalculation` → run reversals and rebooked runs → reversals → new `REGULAR` charges (base key, or
   `base#r{recalculationId}` when the base key exists) → `ADJUSTMENT`s (key `ADJUSTMENT:{recalculationId}:{originalId}`,
   the original's service period, `accrued_on`, tariff/meter/run, the expected parts and `meter_value_use` rows; booked
   in `openForBooking(original.accruedOn)`) → queue (resolve, open, mark obsolete) → `recalculation_line` → one
   `AuditBatch` → `ChargesBooked` (≤ 500 plots per event).

**Digest:** SHA-256 over the normalised cause (no generated ids), the scope and the sorted lines
(`identity|case|liveId|effective|expected|difference|targetMonth|servicePeriod`) plus run and queue outcomes.

**Permissions:** `RECALCULATION_RUN` added to `identity.Permission` and the permission catalog. `POST /recalculations`
needs it (`@PreAuthorize`); the service also requires `TARIFFS_MANAGE` for a tariff cause and `READINGS_ENTER` for a
reading cause (403). `GET /recalculations*` needs `CHARGES_VIEW`.

**Audit:** in the booking's `AuditBatch`: the change itself (`TARIFF_VERSION_ADDED/UPDATED/DELETED`,
`READING_ENTERED/CORRECTED/DELETED`, old and new values, `recalculationId`) and `RECALCULATION_BOOKED` (entity
`RECALCULATION`: cause, scope, reason, count, total). Charges are not repeated in the audit log.

### 2.3 API contract (`api/openapi.yaml`, tag `recalculations`)

| Endpoint | Shape |
|---|---|
| `POST /recalculations?preview=true\|false` | body `{cause: {type, manual?, tariffVersion?, reading?}, reason?, digest?}`; `type` ∈ `MANUAL`, `TARIFF_VERSION_ADD`, `TARIFF_VERSION_UPDATE`, `TARIFF_VERSION_DELETE`, `READING_ENTER`, `READING_CORRECT`, `READING_DELETE` (flat with a discriminator field, not `oneOf`); sub-objects reuse `TariffVersionCreate` / `ReadingCorrection`. Response `Recalculation`: `{id?, preview, booked, changeApplied, cause, scope, lines[], totals[], total, plotCount, notCharged[], digest, createdAt?, createdBy?}`; line `{plotId, plotNumber, chargeType, serviceFrom, serviceTo, originalChargeId?, booked, adjustments, effective, expected, difference, action, bookedIn, reasons[], runId?, createdChargeIds[]}` |
| `GET /recalculations` | page of summaries, newest first |
| `GET /recalculations/{id}` | the record with its lines |
| `GET /plots/{id}/account` | `Charge.kind` gains `ADJUSTMENT`, `source` gains `RECALCULATION`; items gain `adjusts {chargeId, billingMonth, serviceFrom, serviceTo}`, `adjustedBy[]`, `recalculationId` (only with `CHARGES_VIEW`); optional `kind` filter |

New problem codes: `ADJUSTMENT_NOT_REVERSIBLE`, `NOTHING_TO_RECALCULATE`, `RECALCULATION_RANGE_TOO_LONG`. Reused:
`PREVIEW_OUTDATED`, `VALIDATION_FAILED` (`reason` `REQUIRED`/`TOO_LONG`, months `INVALID`/`DATE_IN_FUTURE`),
`TARIFF_VERSION_IN_USE`, `READING_IN_USE`, `PERMISSION_DENIED`.

### 2.4 Web (`web/src`)

| Area | Files |
|---|---|
| Shared | `components/recalculation/RecalculationPreview.tsx` (presentational: scope, lines grouped by plot then month, totals, "Змін немає", reason with counter; no arithmetic), `RecalculationStep.tsx` (preview call, "Провести перерахунок", "Назад", re-preview on `PREVIEW_OUTDATED`), `useCauseDrivenChange.ts` (state union `edit \| preview \| saving`), `recalcLabels.ts` (exhaustive action/cause/reason maps), `RecalcHint.tsx` |
| Tariff card | `NewVersionForm.tsx` (a version starting before the next month → preview first via `TARIFF_VERSION_ADD`; empty plan → plain save), `VersionHistory.tsx` (edit/delete of a used version → step 2 for holders of `RECALCULATION_RUN`, the 409 message otherwise) |
| Readings | `CorrectReadingDialog.tsx` (meter card, round, approvals reuse it), delete action, the reading entry dialog for a back-dated reading: on 409 `READING_IN_USE` → step 2 with `READING_*` causes; without `RECALCULATION_RUN` → "Показ уже нараховано — зверніться до голови" |
| Charges | routes `charges/recalculations`, `charges/recalculations/new` (before `:id`), `charges/recalculations/:id`; pages `RecalculationsPage`, `RecalculationNewPage` (`RequirePermission RECALCULATION_RUN`), `RecalculationDetailPage`; tab in `ChargesTabs.tsx` |
| Account | `ChargeList`, `ChargeDetailDialog`, `PlotAccountPanel`, `MyChargesPanel`, `chargeLabels.ts`: adjustment rows "Коригування за <місяць>: … — <причина>", links (original charge, recalculation when allowed), no "Сторнувати", filter "Лише коригування" (`?adjustments=1`) |
| Hints | `PlotForm.tsx` (area), `ScopeEditor.tsx`, `WaterConnectionPanel.tsx`, `MeterTreePage.tsx`/`MoveMeterDialog.tsx`, for holders of `RECALCULATION_RUN` |
| Invalidation | `invalidateAfterRecalculation` in `chargeInvalidation.ts` |
| i18n | `recalculation.*` namespace, `charges.kind.ADJUSTMENT`, `charges.source.RECALCULATION`, account texts, problem messages, audit action/entity/fields, permission name and description — uk and en |

### 2.5 Demo data, docs, guides

- `R__demo_data.sql` (demo ledger block, date-relative): a membership charge of 30,00 in the closed month on one
  plot (not 12 or 14), a booked recalculation (cause manual, reason "Виправлено ставку членського внеску"): adjustment
  −5,00 in the open month, the open month's 30,00 reversed and 25,00 charged (`#r` key), two lines; cleanup deletes
  lines, recalculations and adjustments first. `DemoLedgerDataTests` learns about it.
- Docs: `money-rules.md` §5 rewritten (2.1–2.5 and the examples), glossary (adjustment, recalculation, effective
  amount), `ledger`, `tariffs`, `meters` and `audit` READMEs, ADR 0006 note.
- Guides (uk/en): board guide chapter "Виправлення і перерахунок" → works with screens; owner manual screens-and-buttons
  and permissions; `capture-screens.mjs` entries (recalculations list, new, detail, tariff step 2, reading step 2, plot
  account adjustment, member account, electrician hint); `make guide-shots`, `make guide`.

---

## 3. Impact and Risk Analysis

- **Module boundaries:** ledger → tariffs/meters only; the new facades are public API of those modules. Modulith
  verification must stay green.
- **Rollback-only preview:** must be the outermost transaction (an inner rollback-only throws
  `UnexpectedRollbackException`); outbox rows of the change roll back with it.
- **Digest stability:** generated ids (new version, new reading) differ between preview and booking; the digest uses
  identities, never ids or keys.
- **Locking:** one per-cooperative lock taken first by every charging path; preview takes it too (briefly). The known
  rare deadlock between a losses run and replacing a parent meter stays.
- **Data without dated history** (area, tariff scope, archived/active flags) is applied to the past; archived plots
  and inactive types are excluded, and the preview shows every change before booking.
- **Large manual scope:** synchronous under the lock; capped at 24 months.
- **Schema changes on `charge`:** brief ACCESS EXCLUSIVE; constraints `NOT VALID` + `VALIDATE`; `lock_timeout`.
- **Downgrade:** an older release cannot read `ADJUSTMENT`/`RECALCULATION` rows; noted for deployment.
- **Correcting a used version in place:** historical charge parts keep their own rate and calculation text, so the
  past stays explainable.

---

## 4. Testing Strategy

- **Pure planner** (`RecalculationPlannerTests`): one test per row of requirement §3 (functional spec 2.1 criteria),
  plus identity, frozen chains, effective amount and digest stability.
- **Booking, Testcontainers:** manual tariff recalculation (open, closed, new charge, idempotency, stale preview,
  reason), intervals and fixed losses (correction, deletion/merge, back-dated reading, queue resolution), runs (open:
  reverse + rebook; closed: per-plot adjustments), tariff causes (add, update, soft delete, note only), reading causes
  (no point event, electrician 409 + 403 on `/recalculations`).
- **Schema:** `RecalculationTenantIsolationTests` (both tables, RLS, append-only, composite FKs, `charge` constraints
  and guard trigger), `LedgerAppendOnlyTests` still green, permission seeding.
- **Permissions:** allowed and denied per endpoint (chairman, treasurer, member, electrician, other cooperative).
- **Web:** vitest for the preview, step, hook, dialogs, pages, account rows and filter, labels exhaustiveness.
- **e2e** (`web/e2e/recalculation.spec.ts`, date-independent helpers, sorts before `zz-closing.spec.ts`): `@smoke` the
  chairman corrects a used version, sees the preview and the change on the plot account; the chairman corrects a
  charged reading; the electrician is refused with the hint; manual page "Змін немає" on a second run.
- **Full suite once in Phase 4** (`make check`, `make e2e`); builders run only their slice's tests (owner's rule).

---

## Assumptions (pipeline)

- **Assumption:** the cause-driven block lives on `POST /recalculations` (cause types) instead of on the tariff and
  reading endpoints (requirement §4). Same behaviour for the user; avoids preview-or-entity responses and a body on
  DELETE. Without the recalculation step the existing endpoints keep answering 409.
- **Assumption:** the preview applies the change in a transaction that is rolled back, instead of a "what-if" override
  (readings have supersede, boundary and interpolation rules that live in `meters`).
- **Assumption:** reversals made by a recalculation use source `RECALCULATION` (not `MANUAL_REVERSAL`), so hand
  reversals can be told apart (frozen chains).
- **Assumption:** an `ADJUSTMENT` carries a source key `ADJUSTMENT:{recalculationId}:{originalId}`; new charges use the
  base key or `base#r{recalculationId}`.
- **Assumption:** a used tariff version is deleted softly (`deleted_at`).
- **Assumption:** a reading changed by a recalculation does not publish `PlotMeterPointsChanged`.
- **Assumption:** a manual recalculation spans at most 24 months, and covers active charge types and chargeable plots.
- **Assumption:** the electrician gets 409 `READING_IN_USE` from the reading endpoint and 403 from
  `/recalculations`; the web shows "Показ уже нараховано — зверніться до голови" when the user lacks
  `RECALCULATION_RUN`.
- **Assumption:** a new version dated before the next month is previewed first from the dialog; when nothing changes it
  is saved through the existing endpoint.
