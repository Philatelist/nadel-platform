---
id: meter-tree
title: "Meter tree"
description: "Substations, quarter meters, common consumers and plot meters as a tree; consumption for a period and how to move a meter."
kind: screen
routes:
  - /settings/meter-tree
permissions:
  - METERS_VIEW
  - METERS_MANAGE
section: meters
order: 30
shots:
  - name: meter-tree
    user: chairman
    path: /settings/meter-tree
---

## What the screen is for

The tree shows how electricity flows through the cooperative: a substation, under it quarter meters, common consumers and plot meters. The system calculates losses by it: the difference between what a node showed and what the meters under it read. Here the chairman builds the tree and sees consumption for a period.

## Who sees it

The screen opens from the [cooperative settings](help:settings), the item "{{ui:settings.meterTreeLink}}". It is seen by those who have the "{{ui:permission.METERS_VIEW.name}}" or "{{ui:permission.METERS_MANAGE.name}}" permission. The button "{{ui:meterTree.move}}" is seen by the one who has both, that is, the chairman. The tree has electricity meters only: there are no water meters in it.

## Every element

At the top is the link "{{ui:meterTree.back}}".

### The period

The fields "{{ui:meterTree.from}}" and "{{ui:meterTree.to}}" set the period for which consumption is shown. By default it is from the start of the current month to today. If the end is earlier than the start: "{{ui:meterTree.rangeInvalid}}".

### The tree

Each node is a frame with:

- an electricity icon and the meter name;
- the kind ("{{ui:meters.kind.SUBSTATION}}", "{{ui:meters.kind.QUARTER}}", "{{ui:meters.kind.PLOT}}", "{{ui:meters.kind.COMMON_CONSUMER}}");
- the plot number for a plot meter;
- "{{ui:meterTree.removedOn}}" if the meter is removed;
- consumption for the period: "{{ui:meterTree.consumption}}";
- the button "{{ui:meterTree.move}}" (for a working meter).

Under a node, indented, are its children. If a meter changed its place in the tree during the period, you see the consumption for each segment under it.

If there is no reading on the border of the period, the system calculates the value in proportion to the days between neighbouring readings and marks it "{{ui:meterTree.calculated}}". If there is still no reading after the end of the period: "{{ui:meterTree.noReadingAfter}}"; if there are no readings at all: "{{ui:meterTree.noReading}}".

An empty tree: "{{ui:meterTree.empty}}"

### The dialog "{{ui:meterTree.moveTitle}}"

The button "{{ui:meterTree.move}}" moves the meter under another node from the given date: past periods are calculated by the old tree.

| Field | What to enter |
|---|---|
| {{ui:meterTree.parentField}} | The node the meter is moved under, or "{{ui:meterTree.noParent}}". |
| {{ui:meterTree.effectiveFrom}} | The date from which the new place applies. Without a date: "{{ui:meterTree.dateRequired}}" |

Buttons: "{{ui:meterTree.save}}" and "{{ui:meterTree.cancel}}".

### Errors

| What is shown | What to do |
|---|---|
| {{ui:problem.TREE_QUARTER_UNDER_QUARTER}} | Choose a substation. |
| {{ui:problem.TREE_PARENT_KIND}} | Choose another parent. |
| {{ui:problem.TREE_PARENT_INACTIVE}} | This parent is already removed, choose a working one. |
| {{ui:problem.TREE_DATE_BEFORE_LATEST}} | Choose a later date. |
| {{ui:problem.METER_HAS_CHILDREN}} | Move the children before removing. |

Moving recalculates nothing. For past charges a [separate recalculation](help:recalculation-new) is needed; the screen reminds you about it.

## Worked example

In the demo the substation "Підстанція №1" has the children "Квартал А", "Квартал Б" and "Свердловина №1". The lighting "Освітлення вул. Вишнева" hangs under quarter A, and the plot meters under their quarters.

1. Open "Cooperative settings" and the item "{{ui:settings.meterTreeLink}}".
2. Choose the period "From" 21 days ago, "To" 7 days ago: there are readings in this range.
3. Look at the consumption of the substation and its children. The difference is the losses.
4. To move a plot meter under another quarter, click "{{ui:meterTree.move}}", choose the new node and the date.

## Screenshots

![The meter tree with consumption for a period](shot:meter-tree)

## Related pages

- [Meters](help:meters)
- [Network losses](help:losses)
- [Cooperative settings](help:settings)
- [How the system calculates meters and the tree](help:how-meters-tree)
