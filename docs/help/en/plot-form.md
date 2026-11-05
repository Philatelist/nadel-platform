---
id: plot-form
title: "New plot and editing"
description: "How to add a plot or change its data: number, street, kind, area, cadastral number, building and notes."
kind: screen
routes:
  - /plots/new
  - /plots/:id/edit
permissions:
  - PLOTS_EDIT
section: plots-members
order: 30
shots:
  - name: plot-form
    user: chairman
    path: /plots/new
  - name: plot-form-edit
    user: chairman
    path: /plots
    open: { css: 'main a[href^="/plots/"]', text: '9' }
    steps:
      - { click: Edit }
---

## What the screen is for

One form for two jobs: add a new plot and correct the data of an existing one. The form records only the reference data of the plot. Owners are changed on the [plot card](help:plot-card), and money and charges are not changed here.

## Who sees it

The form is seen by whoever has the permission "{{ui:permission.PLOTS_EDIT.name}}". By default this is the chairman. The "{{ui:plots.add}}" button in the list and the "{{ui:plot.edit}}" button on the card appear only with this permission. The form title is "{{ui:plot.addTitle}}" for a new plot and "Edit: Plot 9" for an existing one.

## Every element

Above the form is the link "{{ui:plot.back}}". The fields come in this order.

| Field | What to enter |
|---|---|
| {{ui:plot.fields.number}} | Required, up to 20 characters. Unique among the active plots of the cooperative: 12, 12а, R-1. Lists use natural order: 2 before 10, and "12а" after 12. |
| {{ui:plot.fields.street}} | Optional, up to 100 characters. |
| {{ui:plot.fields.kind}} | "{{ui:plot.kind.PLOT}}" or "{{ui:plot.kind.COMMON}}". Common land (roads, shared territory) has no owners, tariffs or plot meters. |
| {{ui:plot.fields.area}} and {{ui:plotForm.areaUnit}} | Required. Choose the unit "{{ui:plotForm.unitSotka}}" or "{{ui:plotForm.unitM2}}": 1 sotka is 100 m². When you switch the unit, the number in the field is converted by itself. For a plot the area must be greater than zero, with at most two decimal places in m². |
| {{ui:plot.fields.cadastralNumber}} | Optional. Format: NNNNNNNNNN:NN:NNN:NNNN, for example 4810136300:02:001:0001. |
| {{ui:plotForm.hasBuilding}} | A tick for reference. It does not affect charges. |
| {{ui:plot.fields.notes}} | Optional, up to 2000 characters. Seen only by those who have "{{ui:permission.PLOTS_VIEW.name}}". |

The {{ui:plotForm.save}} button saves the plot and opens its card. The {{ui:plotForm.cancel}} button goes back without changes.

When you edit an existing plot, a hint appears under the area: "{{ui:recalculation.hint.text}}" Changing the area does not rewrite charges that were already made. To correct them, make a [recalculation](help:recalculation-new).

### Error messages

| What is shown | What to do |
|---|---|
| {{ui:validation.REQUIRED_number}} | Enter the number. |
| {{ui:validation.REQUIRED_areaM2}} | Enter the area. |
| {{ui:validation.AREA_INVALID}} | Fix the area: a number above zero, at most two decimal places. |
| {{ui:validation.CADASTRAL_INVALID}} | Enter the number in this format or leave the field empty. |
| {{ui:problem.PLOT_NUMBER_TAKEN}} | Choose another number. An archived plot does not occupy a number. |
| {{ui:problem.CADASTRAL_NUMBER_TAKEN}} | Check the number: such a plot already exists in the cooperative. |
| {{ui:validation.PLOT_HAS_OWNERSHIP_HISTORY_kind}} | The kind of such a plot cannot be changed. If this is a mistake, contact the chairman. |
| {{ui:validation.PLOT_HAS_WATER_CONNECTION_kind}} | First disconnect the plot from water on its card. |

## Worked example

A new plot 31 has appeared on Vyshneva street.

1. Open "{{ui:nav.plots}}" and press {{ui:plots.add}}.
2. In the "{{ui:plot.fields.number}}" field enter 31, in the "{{ui:plot.fields.street}}" field enter "вул. Вишнева".
3. Leave the kind "{{ui:plot.kind.PLOT}}". In the "{{ui:plot.fields.area}}" field enter 12, unit "{{ui:plotForm.unitSotka}}". If you switch the unit to "{{ui:plotForm.unitM2}}", the field shows 1200.
4. Tick "{{ui:plotForm.hasBuilding}}" if there is a house on the plot.
5. Press {{ui:plotForm.save}}. The card of the new plot opens.

The plot is grey for now: it has no owner. Add an owner on the card with the "{{ui:ownership.changeOwner}}" button, and the plot turns green as long as there is no debt. Existing plots work the same way: plot 9 has 15 sotky, that is 1,500 m².

## Screenshots

![An empty new plot form](shot:plot-form)

![The edit form of plot 9](shot:plot-form-edit)

## Related pages

- [Plots](help:plots)
- [Plot card](help:plot-card)
- [Import from Excel](help:imports)
- [Tariffs](help:tariffs)
