---
id: tariffs
title: "Tariffs"
description: "The list of tariffs by charge type: rate, basis, frequency, how many plots a tariff covers and whether it overlaps another one."
kind: screen
routes:
  - /tariffs
permissions:
  - TARIFFS_VIEW
section: tariffs-charges
order: 10
shots:
  - name: tariffs
    user: chairman
    path: /tariffs
---

## What the screen is for

This screen shows what the cooperative charges for and how much. Tariffs are grouped by charge type: membership fee, electricity, land tax, water and others. The charges themselves appear on the "{{ui:billing.title}}" screen. But already here, and on every plot's card, you can see how much a plot will be charged.

## Who sees it

The chairman and the treasurer see this screen: those who have the "{{ui:permission.TARIFFS_VIEW.name}}" permission. The create buttons are shown only to those who have "{{ui:permission.TARIFFS_MANAGE.name}}", that is, the chairman. A member does not see this screen: they find their expected amounts in their account, on the page [My account](help:account).

## Every element

### Buttons at the top

| Button | What it does |
|---|---|
| {{ui:tariffs.chargeTypesAction}} | Opens the [list of charge types](help:charge-types): you can add your own type, rename, reorder, archive or switch a type on. |
| {{ui:tariffs.newTariff}} | Opens the [new tariff form](help:tariff-form). |

Both buttons are shown to the chairman only.

### The "{{ui:tariffs.list.active}}" / "{{ui:tariffs.list.archived}}" switch

Above the list there is the switch "{{ui:tariffs.list.statusLabel}}". "{{ui:tariffs.list.active}}" is shown by default. A tariff that was [archived](help:tariff-card) moves to "{{ui:tariffs.list.archived}}": there it carries the mark "{{ui:tariffs.archivedBadge}}" and shows the date until which it applied. If there are no archived tariffs, the screen says "{{ui:tariffs.list.noArchived}}".

### Blocks by charge type

Each charge type has its own block with the type name. If the type is switched off, it carries the mark "{{ui:tariffs.inactive}}". Such a type stays in the list, but its tariffs give no expected amount.

If a type has no tariffs yet, the block says "{{ui:tariffs.noTariffs}}". If there are no types at all, the screen says "{{ui:tariffs.empty}}".

### A tariff row

Each row has:

- the tariff name; click it to open the [tariff card](help:tariff-card);
- the rate, the basis and the frequency, for example "50 ₴ · {{ui:tariffs.basis.PER_AREA_SOTKA}} · {{ui:tariffs.frequency.YEARLY}}";
- the date the current version takes effect and the number of plots the tariff concerns;
- an overlap warning, if there is one.

The bases are: {{ui:tariffs.basis.PER_PLOT}}, {{ui:tariffs.basis.PER_AREA_SOTKA}}, {{ui:tariffs.basis.PER_KWH}}, {{ui:tariffs.basis.PER_M3}} and {{ui:tariffs.basis.FIXED_ONE_TIME}}. More about them in the [new tariff form](help:tariff-form).

### The overlap warning

Two tariffs of one charge type cannot apply to the same plot on the same day. If they do, a yellow warning appears under the tariff: "{{ui:tariffs.conflict.overlaps}}" with the name of the other tariff, the number of shared plots and their numbers. Those plots are not charged until you change the plots of one of the two tariffs on the [tariff card](help:tariff-card). The warning reads: "{{ui:tariffs.conflict.text}}" So open the card of one of the tariffs and change its plots, or choose a different charge type. While the overlap stays, those plots land in the queue ["Not charged"](help:not-charged).

## Worked example

The demo cooperative has four tariffs:

| Type | Tariff | Rate |
|---|---|---|
| Membership fee | Для членів кооперативу | 50.00 ₴ per sotka per year |
| Land tax | Земельний податок | 35.00 ₴ per sotka per year |
| Electricity | Електроенергія за лічильником | 4.32 ₴ per kWh per month |
| Road repair | Збір на ремонт дороги | 1,200.00 ₴ once, pay by 01.12.2026 |

1. Open "{{ui:nav.tariffs}}".
2. In the "Membership fee" block find the tariff "Для членів кооперативу" and see how many plots it covers.
3. Click its name to see the versions and the plots.

For a plot of 600 m² (6 sotkas) the membership fee per month is 50 × 6 / 12 = 25.00 ₴, and the land tax is 35 × 6 / 12 = 17.50 ₴.

## Screenshots

![The list of tariffs by charge type](shot:tariffs)

## Related pages

- [New tariff](help:tariff-form)
- [Tariff card: versions and plots](help:tariff-card)
- [Charge types](help:charge-types)
- [Not charged](help:not-charged)
- [How the system calculates tariffs and the expected amount](help:how-tariffs-expected)
