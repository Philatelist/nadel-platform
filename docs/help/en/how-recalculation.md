---
id: how-recalculation
title: "Corrections and recalculation"
description: "How the system corrects charges after a new tariff version, an area change or a corrected reading."
kind: topic
routes: []
permissions: []
section: how-it-calculates
order: 80
shots: []
---

A back-dated tariff version, a correction of a used version or of a used reading — one mechanism:

- **Open month**: the old charge is reversed and a new one created.
- **Closed month**: nothing in it changes; the current open month gets an adjustment for the difference, referring to the original month.
- A reason is required. Before posting, a preview: how many plots and how much.
- If a debt goes down after it was already paid, the excess becomes a prepayment, never a "negative debt".

## On screen

**Recalculations** is a tab of {{ui:nav.charges}} (viewing needs {{ui:permission.CHARGES_VIEW.name}}). It is a log: when, the cause (manual, tariff version, reading), who booked it, the explanation and the {{ui:tariffs.expected.total}} difference. {{ui:billing.status.OPEN}} shows one recalculation: the scope, the totals by charge type and the lines "was charged → should be → difference", the action ({{ui:recalculation.action.REVERSE_AND_RECHARGE}}, {{ui:charges.kind.ADJUSTMENT}}) and the entries created, with a link to the plot account. A recalculation is never changed or undone: a mistake is corrected by the next recalculation.

**New recalculation** ({{ui:permission.RECALCULATION_RUN.name}}, the chairman only): choose {{ui:reports.filters.params.fromMonth}} and {{ui:reports.filters.params.toMonth}} (not later than the current month, at most 24 months), the charge types and the plots ({{ui:audit.filters.all}} or "Chosen"), and press {{ui:billing.rangeRun.preview}}. The preview step shows what would change: the plots, the totals by charge type and the lines with their reasons ({{ui:recalculation.changeReason.TARIFF_VERSION}}, {{ui:recalculation.changeReason.AREA}}…). Nothing is written until you enter a **reason** (required, up to 500 characters) and press **{{ui:recalculation.step.book}}**; exactly the preview you saw is booked. If the charges changed in the meantime, the preview is refreshed and must be confirmed again. When the charges already match the data, the preview says {{ui:recalculation.preview.noChanges}} and there is nothing to book.

**Correcting a used tariff version** is done on the tariff card: {{ui:rolesPage.edit}} or {{ui:rolesPage.delete}} the version. When charges already use it, the same preview step opens instead of {{ui:tariffs.card.saved}}: the change of the version and the recalculation are saved together or not at all. The same goes for a new back-dated version.

**Correcting a charged reading** is done on the meter card: {{ui:ownership.correct}} or {{ui:rolesPage.delete}}. The chairman gets the preview step ({{ui:readings.recalc.title}}) with a reason; the electrician (may enter readings, may not recalculate) sees {{ui:readings.usedNeedsChairman}} and nothing changes.

**On the plot account** the adjustment of a closed month is a separate row "Adjustment for Sep 2026: −₴5.00" with the reason, the links {{ui:charges.adjustment.openOriginal}} and {{ui:audit.entityType.RECALCULATION}} and the badge {{ui:charges.kind.ADJUSTMENT}}. The checkbox **{{ui:charges.adjustment.only}}** keeps only such rows. A member sees the adjustments of their own plots (the reason, but not the recalculation). The demo data has such a row on plot 2.

Screens that change data affecting past charges (plot area, tariff scope, water connection, moving a meter in the tree) recalculate nothing themselves and remind you: "Past charges have not changed. If needed — Charges → Recalculation". Only those who may recalculate see the hint.

> **Example**
>
> From 1 September 60 ₴/sotka/year was entered by mistake instead of 50; a 6-sotka plot. September is closed, October open.
>
> |  | Was | After the correction |
> |---|---|---|
> | September (closed) | 30.00 | Unchanged; in October an adjustment −5.00 "for September" |
> | October (open) | 30.00 | Reversal −30.00 and a new charge 25.00 |
> | Total | 60.00 | 50.00; if 60.00 was paid, a prepayment of 10.00 |

> [!important]
> **Known limit**
>
> {{ui:nav.approvals}} has no recalculation step. If a reading is already approved and charged, correct it on the meter card, which has the preview step.
