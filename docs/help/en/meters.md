---
id: meters
title: "Meters"
description: "The register of the cooperative's meters: filters, columns, meter kinds and how to register a new meter."
kind: screen
routes:
  - /meters
permissions:
  - METERS_VIEW
section: meters
order: 10
shots:
  - name: meters
    user: chairman
    path: /meters
---

## What the screen is for

This is the register of all the cooperative's meters. Each has a resource icon: a lightning bolt for electricity, a drop for water. From here the chairman registers a new meter, and by opening a meter sees its [card](help:meter-card) with the reading history.

## Who sees it

The chairman and the treasurer see the register: those who have the "{{ui:permission.METERS_VIEW.name}}" permission. Only the one who has both permissions, "{{ui:permission.METERS_VIEW.name}}" and "{{ui:permission.METERS_MANAGE.name}}", that is, the chairman, can register meters. An electrician with "{{ui:permission.READINGS_ENTER.name}}" enters readings on the screen ["Readings"](help:readings-round).

## Every element

### The button

{{ui:meters.add}} opens the dialog "{{ui:meters.registerTitle}}" (chairman only).

### Meter kinds

| Kind | What it is |
|---|---|
| {{ui:meters.kind.PLOT}} | Belongs to a plot. A plot has at most one working meter of each resource. |
| {{ui:meters.kind.SUBSTATION}} | The meter on the cooperative's transformer, the root of the [meter tree](help:meter-tree). |
| {{ui:meters.kind.QUARTER}} | The meter of a group of plots: a street or a quarter. |
| {{ui:meters.kind.COMMON_CONSUMER}} | A well pump, street lighting, the office. |

The number of substations, quarter meters and wells is not limited.

### Filters

| Filter | What it does |
|---|---|
| {{ui:meters.filterResource}} | "{{ui:meters.allResources}}", "{{ui:meters.resource.ELECTRICITY}}" or "{{ui:meters.resource.WATER}}". |
| {{ui:meters.filterKind}} | "{{ui:meters.allKinds}}" or one of the kinds above. |
| {{ui:meters.filterStreet}} | Search by street; the list updates by itself. |
| {{ui:meters.showRemoved}} | Adds removed meters to the list. |

The chosen filters are kept in the page address, so the link can be shared. If nothing matches the filters, the screen says "{{ui:meters.nothingFound}}", and if there are no meters at all: "{{ui:meters.empty}}".

### Columns

{{ui:meters.columns.plot}}, {{ui:meters.columns.name}} (a link to the card), {{ui:meters.columns.serial}}, {{ui:meters.columns.kind}}, {{ui:meters.columns.resource}}, {{ui:meters.columns.lastReading}} (value and date) and {{ui:meters.columns.status}}: "{{ui:meters.active}}" or "{{ui:meters.removed}}".

### The dialog "{{ui:meters.registerTitle}}"

| Field | What to enter |
|---|---|
| {{ui:meters.kindField}} | The meter kind. |
| {{ui:meters.resourceField}} | Electricity or water; for substations, quarters and consumers only electricity. |
| {{ui:meters.plotField}} | For a plot meter: find the plot by number. The button "{{ui:meters.changePlot}}" lets you choose another. Without a plot: "{{ui:meters.plotRequired}}". |
| {{ui:meters.name}} | For non-plot meters, for example "Свердловина №1". |
| {{ui:meters.serial}} | Required. |
| {{ui:meters.installedOn}} | The installation date. |
| {{ui:meters.initialReading}} | The initial reading, at most three decimal places: "{{ui:meters.valueHint}}". |
| {{ui:meters.note}} | Optional. |

Buttons: "{{ui:meters.save}}" and "{{ui:meters.cancel}}".

### Error messages

| What is shown | What to do |
|---|---|
| {{ui:problem.METER_ACTIVE_EXISTS}} | Do not add a second one: replace the current one on its [card](help:meter-card). |
| {{ui:meters.valueInvalid}} | Correct the initial reading. |
| {{ui:meters.dateRequired}} | Choose the installation date. |

## Worked example

The demo has 38 meters: the substation "Підстанція №1", quarters "Квартал А" and "Квартал Б", "Свердловина №1" with a pump, "Освітлення вул. Вишнева", plot meters and four water meters.

1. Open "{{ui:nav.meters}}" and choose the resource "{{ui:meters.resource.WATER}}" to see the water meters.
2. Clear the filter and tick "{{ui:meters.showRemoved}}": the meter ЕЛ-9, removed 30 days ago, appears.
3. Click ЕЛ-9/2 to open its [card](help:meter-card) with the replacement history.

## Screenshots

![The meter register](shot:meters)

## Related pages

- [Meter card](help:meter-card)
- [Meter tree](help:meter-tree)
- [Readings: the electrician's round](help:readings-round)
- [How the system calculates meters and the tree](help:how-meters-tree)
