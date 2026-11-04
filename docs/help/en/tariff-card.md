---
id: tariff-card
title: "Tariff card: versions and plots"
description: "The versions of a tariff with their dates, adding a new version, correcting and deleting a version, the plots a tariff applies to and the exclusions."
kind: screen
routes:
  - /tariffs/:id
permissions:
  - TARIFFS_VIEW
section: tariffs-charges
order: 30
shots:
  - name: tariff-card
    user: chairman
    path: /tariffs
    open: { css: 'main a[href^="/tariffs/"]:not([href$="/new"])' }
  - name: tariff-recalculation-step
    user: chairman
    path: /tariffs
    open: { css: 'main a[href^="/tariffs/"]:not([href$="/new"])' }
    steps:
      - { click: Edit }
      - { fill: { label: 'Rate, ₴', value: '55', within: Edit version } }
      - { click: Save }
---

## What the screen is for

This screen shows one tariff: what it was and is, by versions, which plots it applies to and which exclusions it has. Here the chairman changes the rate (with a new version), corrects mistakes in versions and decides the plots.

## Who sees it

The chairman and the treasurer see the card: those who have the "{{ui:permission.TARIFFS_VIEW.name}}" permission. Only the chairman can change things, that is, the one who also has "{{ui:permission.TARIFFS_MANAGE.name}}". If the charge type is switched off, the buttons for changing versions disappear.

## Every element

At the top there is the link "{{ui:tariffs.back}}", then the tariff name, the charge type ("{{ui:tariffs.card.chargeType}}") and the mark "{{ui:tariffs.inactive}}" if the type is switched off.

### The overlap warning

If the tariff overlaps another tariff of the same type, you see a yellow message "{{ui:tariffs.conflict.overlaps}}" with the name of the other tariff, the number of shared plots and their numbers. Those plots are not charged until you change the plots of one of the tariffs in the block "{{ui:tariffs.scope.title}}".

### The block "{{ui:tariffs.scope.title}}"

It shows who the tariff applies to. There are two ways:

- "{{ui:tariffs.scope.all}}" (except common land) and, if needed, exclusions with a required reason, for example "exempted by the general meeting";
- "{{ui:tariffs.scope.selected}}": an explicit list of plots.

If a plot is archived, it carries the mark "{{ui:tariffs.scope.archivedMark}}". If there are no exclusions, it says "{{ui:tariffs.scope.noExclusions}}", and if no plot is selected, "{{ui:tariffs.scope.noSelected}}".

The button "{{ui:tariffs.scope.edit}}" (chairman only) opens the editor:

| Element | What it does |
|---|---|
| {{ui:tariffs.scope.kind}} | A switch between "{{ui:tariffs.scope.all}}" and "{{ui:tariffs.scope.selected}}". If you switch, the exclusions or the selected plots are deleted on saving, and a yellow message warns about it. |
| {{ui:tariffs.scope.searchPlot}} | Search by number; plot suggestions below. The button "{{ui:tariffs.scope.addExclusion}}" or "{{ui:tariffs.scope.addSelected}}" adds the plot. |
| {{ui:tariffs.scope.reason}} | Required for every exclusion, up to 500 characters. Without it you get "{{ui:tariffs.scope.reasonRequired}}". |
| {{ui:tariffs.scope.remove}} | Removes the plot from the list. |
| {{ui:tariffs.scope.save}} | Saves. After that you see "{{ui:tariffs.scope.saved}}". |
| {{ui:tariffs.scope.cancel}} | Closes the editor without changes. |

Editor errors: "{{ui:tariffs.scope.errors.TARIFF_COMMON_LAND}}", "{{ui:tariffs.scope.errors.PLOT_ARCHIVED}}", "{{ui:tariffs.scope.errors.UNKNOWN_PLOT}}", "{{ui:tariffs.scope.errors.DUPLICATE}}" (the plot is given twice) and "{{ui:tariffs.scope.errors.TOO_LONG}}". Correct the row next to which they appear.

> [!important]
> A tariff cannot be deleted. If a tariff was created by mistake, switch it off: choose "{{ui:tariffs.scope.selected}}" and add no plot, or exclude all plots. Then it is charged to nobody.

> [!important]
> Two tariffs of one type cannot apply to the same plot on the same day. Tariffs per kWh and per m³ apply only to plots that have a meter of that resource: on the plot card, where there is none, you see "{{ui:tariffs.expected.noMeter}}".

### The block "{{ui:tariffs.card.history}}"

The list of versions, the newest on top. A version row says: "from … to …" (or "from …" if it is still valid), the rate, the basis, the frequency and, for a one-off fee, "pay by …", and the note.

Every row has buttons (chairman only):

| Button | What it does |
|---|---|
| {{ui:tariffs.card.edit}} | Opens the form "{{ui:tariffs.card.editVersion}}" with the same fields as the [new tariff form](help:tariff-form). The button "{{ui:tariffs.card.save}}" saves. |
| {{ui:tariffs.card.delete}} | Asks for confirmation "{{ui:tariffs.card.deleteTitle}}". This cannot be undone. |

While a version has no charges, it can be corrected or deleted simply: you see "{{ui:tariffs.card.saved}}" or "{{ui:tariffs.card.deleted}}". The only version of a tariff cannot be deleted ("{{ui:problem.TARIFF_LAST_VERSION}}"): archive the tariff by switching off its plots, or add another version.

If a version already has charges, a hint "{{ui:tariffs.card.inUse}}" stands next to it. Then:

- the chairman who can recalculate (the "{{ui:permission.RECALCULATION_RUN.name}}" permission), after "{{ui:tariffs.card.save}}" or the delete confirmation, sees the recalculation preview step: what will change in past charges. The version change and the recalculation are saved together or not at all. The action is completed by the button "{{ui:recalculation.step.book}}" after entering the reason. More on the page [New recalculation](help:recalculation-new);
- without that permission the system says "{{ui:problem.TARIFF_VERSION_IN_USE}}" and changes nothing.

### The block "{{ui:tariffs.card.newVersion}}"

A tariff is changed not by editing but by a new version with the date it takes effect. The fields are the same as in the first version of a tariff, and the starting values come from the latest one. The button "{{ui:tariffs.card.add}}" adds the version: "{{ui:tariffs.card.added}}".

If the new version takes effect from a past date, the system warns: "{{ui:tariffs.version.pastDated}}". When you have the right to recalculate, you see the preview step and then "{{ui:tariffs.card.addedRecalculated}}". Without that right, past charges stay as they were.

The error "{{ui:problem.TARIFF_VERSION_DATE_TAKEN}}" means a version from this date already exists: correct it instead of adding.

## Worked example

September is closed, October is open. From 1 September 60 ₴/sotka/year was entered by mistake instead of 50, the plot has 6 sotkas (in the demo it is plot 2).

1. Open the tariff "Для членів кооперативу" and click "{{ui:tariffs.card.edit}}" next to the version.
2. Change the rate to 50 and click "{{ui:tariffs.card.save}}". Because the version is already used, the preview step opens.
3. Look at what will change: in closed September nothing; in October a reversal of 30.00 and a new charge of 25.00; in addition, in October an adjustment of −5.00 "for September".
4. Enter the reason and click "{{ui:recalculation.step.book}}".

The new rate gives 50 × 6 / 12 = 25.00 ₴ per month, the old one was 60 × 6 / 12 = 30.00 ₴. If the member has already paid at the old rate, the excess becomes a prepayment, not a "negative debt".

## Screenshots

![The tariff card with versions and plots](shot:tariff-card)

![The recalculation preview step after correcting a rate](shot:tariff-recalculation-step)

## Related pages

- [Tariffs](help:tariffs)
- [New tariff](help:tariff-form)
- [New recalculation](help:recalculation-new)
- [Not charged](help:not-charged)
- [How the system calculates tariffs and the expected amount](help:how-tariffs-expected)
- [How the system calculates a recalculation](help:how-recalculation)
