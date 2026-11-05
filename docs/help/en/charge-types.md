---
id: charge-types
title: "Charge types"
description: "The cooperative's charge types: how to add your own type, rename, reorder, switch off or on (for example, water) and archive."
kind: screen
routes:
  - /charge-types
permissions:
  - TARIFFS_VIEW
section: tariffs-charges
order: 40
shots:
  - name: charge-types
    user: chairman
    path: /charge-types
---

## What the screen is for

A charge type is "what for" the cooperative takes money: membership fee, electricity, land tax, water, common consumers. Each type has its own [tariffs](help:tariffs). The order of types in the list matters: when a payment is allocated to debts of the same day, the system follows this order.

## Who sees it

The chairman and the treasurer see the list: those who have the "{{ui:permission.TARIFFS_VIEW.name}}" permission. Only the one who has "{{ui:permission.TARIFFS_MANAGE.name}}", that is, the chairman, can add, change, move and archive.

## Every element

### At the top

| Element | What it does |
|---|---|
| {{ui:chargeTypes.back}} | Goes back to the list of tariffs. |
| {{ui:chargeTypes.add}} | Opens the dialog "{{ui:chargeTypes.dialog.addTitle}}". Chairman only. |

The cooperative has these types from the start: {{ui:chargeTypes.defaultNames.MEMBERSHIP}}, {{ui:chargeTypes.defaultNames.ELECTRICITY}}, {{ui:chargeTypes.defaultNames.LAND_TAX}}, {{ui:chargeTypes.defaultNames.WATER}} and {{ui:chargeTypes.defaultNames.COMMON_CONSUMERS}}. You can add your own, for example "Ремонт дороги". If there are no types, the screen says "{{ui:chargeTypes.empty}}".

> [!important]
> The type "{{ui:chargeTypes.defaultNames.WATER}}" may be switched off while the cooperative has no water meters. Then it is shown in the new tariff form as "name (inactive — enable)". You can switch it on there by choosing it, or here: click "{{ui:chargeTypes.edit}}" next to the type "{{ui:chargeTypes.defaultNames.WATER}}", tick "{{ui:chargeTypes.dialog.active}}" and save. You can also do it from the new tariff form: a switched-off type is shown there as "name (inactive — enable)", and choosing it switches it on. Then create the [tariff](help:tariff-form): a fixed amount per plot, and only plots connected to water pay. The electricity of the well pump is charged not with this type but with the type "{{ui:chargeTypes.defaultNames.COMMON_CONSUMERS}}".

### A type row

The row shows the name, the mark "{{ui:chargeTypes.active}}" or "{{ui:chargeTypes.inactive}}" and the description. The chairman also has buttons:

| Button | What it does |
|---|---|
| {{ui:chargeTypes.moveUp}} | Moves the type up in the list. Unavailable for the first one. |
| {{ui:chargeTypes.moveDown}} | Moves the type down. Unavailable for the last one. |
| {{ui:chargeTypes.edit}} | Opens the dialog "{{ui:chargeTypes.dialog.editTitle}}". |
| {{ui:chargeTypes.archive}} | Asks for confirmation "{{ui:chargeTypes.archiveTitle}}". |

### The type dialog

| Field | What to enter |
|---|---|
| {{ui:chargeTypes.dialog.name}} | Required. A standard type can be renamed; then your name is shown. |
| {{ui:chargeTypes.dialog.description}} | Optional. |
| {{ui:chargeTypes.dialog.active}} | Only when changing. Hint under the field: "{{ui:chargeTypes.dialog.activeHint}}" |

Buttons: "{{ui:chargeTypes.dialog.save}}" and "{{ui:chargeTypes.dialog.cancel}}".

### Archiving

The confirmation "{{ui:chargeTypes.archiveConfirm}}" hides the type from the lists: it will get no new tariffs. Past charges and old tariffs stay. If a type is just temporarily not needed, it is better to switch it off by clearing "{{ui:chargeTypes.dialog.active}}": the type then stays in the list and can be switched on again.

### Error messages

| What is shown | What to do |
|---|---|
| {{ui:problem.CHARGE_TYPE_NAME_TAKEN}} | Choose another name. |
| {{ui:problem.CHARGE_TYPE_ORDER_STALE}} | Someone else changed the list. It has already been refreshed, repeat the move. |
| {{ui:problem.CHARGE_TYPE_ARCHIVED}} | The type is archived and can no longer be changed. |

## Worked example

The cooperative decided to collect money for a road repair.

1. Click "{{ui:chargeTypes.add}}".
2. Name the type "Ремонт дороги", describe it "Цільовий збір на ремонт головної дороги" and save.
3. Move it up with "{{ui:chargeTypes.moveUp}}" until it is where you want it: the higher the type, the earlier its debt is paid off by a payment of the same day.
4. Create a [tariff](help:tariff-form) for it: a one-off amount of 1,200.00 ₴, pay by 01.12.2026.

## Screenshots

![The list of charge types](shot:charge-types)

## Related pages

- [Tariffs](help:tariffs)
- [New tariff](help:tariff-form)
- [Cooperative settings](help:settings)
- [How the system allocates payments](help:how-payments-allocate)
