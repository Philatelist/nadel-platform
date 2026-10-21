# Glossary

| Term | Code | Meaning |
|---|---|---|
| Cooperative | `Tenant` | A platform customer. All data belongs to exactly one cooperative |
| Plot | `Plot` | A land plot with a number, area and polygon on the map. Kind: plot or common land |
| Sotka | — | 100 m². Plot area is stored in m² and shown in sotkas in the UI |
| Member | `Member` | A natural person in a cooperative's member list. May own several plots. Signs in when their Keycloak account is linked to the member record. Archived, never deleted |
| Ownership | `Ownership` | Member–plot link with a share and a validity period (`validFrom`, inclusive `validTo`, empty while current). The current owners of a plot (not common land) have shares that add up to exactly one whole, or the plot has no owner. A member may own several plots; the same member never owns a plot in two overlapping periods. Rows are never deleted: a past owner stays in the history (feature 007) |
| Share | `Share` | An exact fraction `n/d` of a plot owned by one owner (`1/2`, `2/3`, default `1/1`; shown in lowest terms, so `2/4` becomes `1/2`; `d` is at most 1000). Never a decimal. Money-bearing features split by shares only through this type |
| Change of owner | `OwnershipCommands.change` | One operation on a plot from a date: every current owner's period ends the day before, the new owners start on that date, with an optional basis (sale contract, inheritance, court decision). An empty list leaves the plot without an owner. The date is not in the future and later than every current start and past end. One audit entry `OWNERSHIP_CHANGED` per change |
| Correction | `OwnershipCommands.correct` | Fixes a mistake in one ownership record (member, share, dates, basis) and, in the same step, the shares of its co-owners, with a required reason. The old values stay in the audit log (`OWNERSHIP_CORRECTED`); records are corrected, never erased |
| Plot status visibility | `memberPlotStatusVisibility` | Cooperative setting (`settings` module, `SETTINGS_MANAGE`): `OWN_ONLY` (default) shows a member the status colour only of plots they own, `ALL_PLOTS` shows every plot's colour to every member. Amounts, owners and personal data of other plots are never shown to a member; people with `PLOTS_VIEW` always see every colour |
| Import | `ImportRun` | Loading plots, members and current ownership from one `.xlsx` file (sheets Plots, Members, Ownership) by the chairman (`IMPORT_RUN`). Two steps: a preview, then a commit in one transaction. Plots are matched by number, members by e-mail or by last name + first name + first phone; ownership already recorded is skipped, different ownership is an error. Re-importing the same file changes nothing. The file never archives or deletes records. Each upload is kept in the import history (feature 008) |
| Preview | `ImportPreview` | The result of checking an uploaded file without saving anything: counts of new, updated and unchanged records per sheet and the problems by sheet, row and column. It can be committed for 30 minutes, only when it has no problems and changes something, and only while the data it was based on has not changed (otherwise `IMPORT_STALE`) |
| Charge type | `ChargeType` | What the cooperative charges for: a seeded type (membership fee, electricity, land tax, water; the first three are active, water is inactive until meters arrive) or a custom one such as a special-purpose fee. Has a name unique among non-archived types (ignoring letter case), a description, an active flag and a position. A seeded type shows a localised default name until it is renamed (`nameCustomized`). An archived type is hidden from the list, its name is freed, nothing about it changes any more, and its tariffs never apply; an inactive type stays in the list but gives no expected amount (feature 009) |
| Tariff | `Tariff` | The calculation rule for one charge type: a name, versions and a scope. A charge type may have several tariffs. A tariff is created together with its first version and applies to all plots until its scope is changed; it is never renamed or deleted (feature 009) |
| Tariff version | `TariffVersion` | Rate, basis, frequency and, for a one-time fee, a due date, effective from `validFrom`. A change of rate is a new version, and two versions of a tariff never start on the same date; a version can be corrected or deleted until a charge uses it, and after that a correction goes through recalculation (feature 012). A version applies until the day before the next one starts; that end date is derived on every read, never stored |
| Rate | `Rate` | The number in a tariff version, for example `4.3200` UAH per kWh. Not negative, at most four decimals, never rounded (a fifth decimal is rejected) and not money: it becomes an amount only through `Money.ofQuotient` in the calculation (ADR 0013) |
| Basis | `Basis` | What a rate applies to: `PER_PLOT`, `PER_AREA_SOTKA` (per sotka of the plot's area), `PER_KWH`, `PER_M3` (both depend on meter readings) or `FIXED_ONE_TIME` (a fixed amount, due once) |
| Frequency | `Frequency` | The period a rate is meant for: `MONTHLY`, `QUARTERLY`, `YEARLY`, or `ONE_OFF` (only with `FIXED_ONE_TIME`, which needs a due date) |
| Tariff scope | `TariffScope` | Which plots a tariff applies to: `ALL_PLOTS` (the default; common land is never included) or `SELECTED_PLOTS`, a list of chosen plots. Replaced as a whole by one change, which is audited |
| Exclusion | `TariffScopePlot` (kind `EXCLUDED`) | A plot of an `ALL_PLOTS` tariff that the tariff does not apply to, with a required reason (up to 500 characters). The expected amount shows it as excluded, with the reason, and not as zero |
| Expected amount | `ExpectedCharge` | What a plot would be charged by its tariffs in a month, calculated on the server by `ChargeCalculator` from the versions valid in that month and the plot area: the days of each version over the days of the month, summed, rounded once. A one-time fee counts only in its due month. A `PER_KWH` or `PER_M3` tariff has no amount ("by readings"). A preview only: nothing is charged until feature 011. A member sees it only for plots they own |
| Meter | `Meter` | Electricity meter of a plot, or the common transformer meter. A plot has zero or one active meter; a replaced meter keeps its history (money-rules 6.1–6.2) |
| Meter reading | `MeterReading` | Meter value on a date. Source: admin, member, photo. Status: pending / approved / rejected |
| Transformer losses | — | Common meter minus the sum of plot meters for the period |
| Substation meter | `Meter` (kind `SUBSTATION`) | Meter at the cooperative's transformer; root of the meter tree |
| Quarter meter | `Meter` (kind `QUARTER`) | Intermediate meter for a group of plots (a street or block); its losses are shared among those plots |
| Common consumer meter | `Meter` (kind `COMMON_CONSUMER`) | Meter of a shared consumer such as a well pump or street lighting |
| Water meter | `Meter` (resource `WATER`) | Future: water meter of a plot, readings in m³ |
| Billing period | `BillingPeriod` | A billing month. Open or closed |
| Charge | `Charge` | Ledger entry: amount due for a plot, charge type and period |
| Reversal | `Charge.kind = REVERSAL` | Entry that cancels an earlier charge |
| Payment | `Payment` | Money received: from a bank statement or as cash through the cash desk |
| Receipt | `Payment.receiptNumber` | Sequential per-cooperative number of a cash payment, shown to the member |
| Allocation | `Allocation` | Link of part of a payment to a specific charge |
| Prepayment | — | Unallocated remainder of a plot's payments |
| Invoice | `Invoice` | Monthly document with the amount due, bank details and an NBU payment QR code |
| Plot status | `PlotStatus` | Map color: RED, YELLOW, GREEN, GREY |
| Treasurer | `TREASURER` | Base role. Admin who allocates payments; starts with View members, View plots and View audit log |
| Chairman | `CHAIRMAN` | Base role. Head admin of the cooperative; always has every permission. A cooperative keeps at least one active chairman |
| Member role | `MEMBER` | Base role every ordinary member holds; no permissions by default, sees only their own data |
| Electrician | — | Admin who works only with meters. Not a base role: a custom role, given the meter permissions `METERS_VIEW` and `READINGS_ENTER` |
| Platform operator | `PLATFORM_OPERATOR` | Owner of the SaaS. Manages cooperatives and subscriptions, cannot see their data |
| Permission | `Permission` | A single allowed action from a fixed list, for example `PAYMENTS_ALLOCATE` or `MEMBERS_EDIT` |
| Role | `Role` | A named set of permissions stored per cooperative. Base roles (Chairman, Treasurer, Member) exist in every cooperative and cannot be deleted, but can be renamed (feature 004; the code stays, `Role.nameCustomized` tells a renamed role from one that still has its standard name); custom roles are added by anyone with `ROLES_MANAGE`. A member may hold several roles (table `member_role`) and gets the union of their permissions |
| Audit entry | `AuditEvent` (table `audit_entry`) | Append-only record of an admin change: who, when, what, and old and new value of each changed field |
