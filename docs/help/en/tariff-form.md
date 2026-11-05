---
id: tariff-form
title: "New tariff"
description: "How the chairman creates a tariff: charge type, name, and the first version with the rate, basis and frequency."
kind: screen
routes:
  - /tariffs/new
permissions:
  - TARIFFS_MANAGE
section: tariffs-charges
order: 20
shots:
  - name: tariff-form
    user: chairman
    path: /tariffs/new
---

## What the screen is for

Here the chairman creates a new tariff together with its first version. A tariff tells the system what to charge for, how much and how often. After saving, the [tariff card](help:tariff-card) opens, where you must set the plots the tariff applies to.

## Who sees it

The chairman sees the form: the one who has the "{{ui:permission.TARIFFS_MANAGE.name}}" permission. The treasurer sees tariffs but cannot create a new one.

> [!important]
> Two tariffs of one charge type cannot cover the same plots on the same day. The monthly water fee has its own type "{{ui:chargeTypes.defaultNames.WATER}}": a fixed amount per plot, and only connected plots pay. The electricity of the well pump is charged with the type "{{ui:chargeTypes.defaultNames.COMMON_CONSUMERS}}" with the split "{{ui:billing.common.split.CONNECTED_PLOTS}}". The type "{{ui:chargeTypes.defaultNames.WATER}}" may be switched off: then it is shown as "name (inactive — enable)", and choosing it switches it on (or [switch it on in the charge types](help:charge-types)).

> [!important]
> If you created a tariff by mistake and it has no charges yet, you can delete it on the [tariff card](help:tariff-card). If it has charges, it can only be archived.

## Every element

At the top there is the link "{{ui:tariffs.back}}".

### Type and name

| Field | What to enter |
|---|---|
| {{ui:tariffs.form.chargeType}} | Required. The list has all types. A switched-off type is shown as "name (inactive — enable)": choosing it switches the type on when you save the tariff (the screen says "{{ui:tariffs.form.activating}}"), so you do not need to go to the type list. If there are no types, the form is replaced with the message "{{ui:tariffs.form.noActiveTypes}}". |
| {{ui:tariffs.form.name}} | Required, up to 100 characters, for example "Для членів кооперативу". |

Under the type field there is the hint "{{ui:tariffs.form.activeTariffs}}": the current tariffs of the chosen type with their plots (or "{{ui:tariffs.form.noPlots}}", "{{ui:tariffs.form.noActiveTariffs}}"). So you see before saving whether the new tariff would overlap an existing one. Next to it there is the link "{{ui:tariffs.form.missingType}} {{ui:tariffs.form.chargeTypesLink}}", which opens the [list of charge types](help:charge-types).

### The first version

Block heading: "{{ui:tariffs.form.firstVersion}}".

| Field | What to enter |
|---|---|
| {{ui:tariffs.version.validFrom}} | The date the rate takes effect. Required. |
| {{ui:tariffs.version.rate}} | The rate with up to four decimal places, for example 4.32 or 1.4532. The amount is rounded to kopecks only at the end, so extra digits do no harm. On screen the rate is shown without extra zeros: 50.00 ₴, not 50.0000 ₴. |
| {{ui:tariffs.version.basis}} | What the amount is made of. See the table below. |
| {{ui:tariffs.version.frequency}} | {{ui:tariffs.frequency.MONTHLY}}, {{ui:tariffs.frequency.QUARTERLY}} or {{ui:tariffs.frequency.YEARLY}}. For the basis "{{ui:tariffs.basis.FIXED_ONE_TIME}}" always "{{ui:tariffs.frequency.ONE_OFF}}". |
| {{ui:tariffs.version.dueDate}} | Appears only for the basis "{{ui:tariffs.basis.FIXED_ONE_TIME}}" and is then required. |
| {{ui:tariffs.version.note}} | Optional, for example "General meeting decision 14.12.2025". |

If "Valid from" is in the past, a yellow message appears under the fields: "{{ui:tariffs.version.pastDated}}". It is only a warning: a new tariff does not change charges already booked; a recalculation is made separately on the page [New recalculation](help:recalculation-new).

### Bases

| Basis | Example |
|---|---|
| {{ui:tariffs.basis.PER_PLOT}} | 300 ₴ per plot per quarter |
| {{ui:tariffs.basis.PER_AREA_SOTKA}} | Membership fee 50 ₴ per sotka per year |
| {{ui:tariffs.basis.PER_KWH}} | Electricity 4.32 ₴ per kWh, by the meter readings |
| {{ui:tariffs.basis.PER_M3}} | Water by the water meter readings |
| {{ui:tariffs.basis.FIXED_ONE_TIME}} | A targeted fee of 1,200 ₴ with a due date |

The cooperative charges monthly, so a yearly rate is divided by 12 and a quarterly rate by 3. Tariffs per kWh and per m³ apply only to plots that have a meter of that resource.

### Buttons

- {{ui:tariffs.form.create}} saves the tariff and opens its card.
- {{ui:tariffs.form.cancel}} goes back to the previous screen without saving.

### Error messages

| What is shown | What to do |
|---|---|
| A required field message under the name or the type | Fill it in. |
| An error next to the rate | Enter a number with at most four decimal places. |
| "{{ui:problem.TARIFF_VERSION_DATE_TAKEN}}" next to "Valid from" | The tariff already has a version from this date. Choose another date or correct the existing version on the card. |
| A red message above the buttons | Read it: it says what exactly is wrong (for example, "{{ui:problem.CHARGE_TYPE_ARCHIVED}}"). |

## Worked example

The chairman enters the membership fee, as in the demo data.

1. Click "{{ui:tariffs.newTariff}}" on the screen ["Tariffs"](help:tariffs).
2. Choose the type "{{ui:chargeTypes.defaultNames.MEMBERSHIP}}", name the tariff "Для членів кооперативу".
3. "Valid from" 01.01.2026, basis "{{ui:tariffs.basis.PER_AREA_SOTKA}}", frequency "{{ui:tariffs.frequency.YEARLY}}", rate 50.
4. Click "{{ui:tariffs.form.create}}" and set the plots on the card.

For a plot of 6 sotkas the monthly fee is 50 × 6 / 12 = 25.00 ₴.

## Screenshots

![The new tariff form with the first version](shot:tariff-form)

## Related pages

- [Tariffs](help:tariffs)
- [Tariff card: versions and plots](help:tariff-card)
- [Charge types](help:charge-types)
- [How the system calculates tariffs and the expected amount](help:how-tariffs-expected)
