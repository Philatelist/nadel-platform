---
id: audit
title: "Change log"
description: "Who changed what and when: filters by record kind and date, old and new values, and hidden personal data."
kind: screen
routes:
  - /audit
permissions:
  - AUDIT_VIEW
section: settings
order: 30
shots:
  - name: audit
    user: chairman
    path: /audit
---

## What the screen is for

Every action of the board is written to the log: who, when and what changed, with old and new values. The log helps to find out where a change came from without relying on memory. Log records are never changed or deleted. Data entered by demonstration scripts is not shown in the log: only the actions of people in the system.

## Who sees it

The log is read by whoever has the permission "{{ui:permission.AUDIT_VIEW.name}}": the chairman and the treasurer. The old and new values of phones, e-mail, address, Telegram, Viber and notes of a member are seen only by those who also have "{{ui:permission.MEMBERS_VIEW.name}}". Everyone else sees that the field changed, and the word "{{ui:audit.hiddenValue}}" stands instead of the values. Other fields, such as the name, are shown as usual. The mailbox password and the Telegram bot token are shown to nobody: the log says only "{{ui:audit.secretChanged}}".

## Every element

### Filters

- "{{ui:audit.filters.entityType}}": "{{ui:audit.filters.all}}" or one record kind: {{ui:audit.entityType.MEMBER}}, {{ui:audit.entityType.ROLE}}, {{ui:audit.entityType.PLOT}}, {{ui:audit.entityType.SETTINGS}}, {{ui:audit.entityType.OWNERSHIP}}, {{ui:audit.entityType.CHARGE_TYPE}}, {{ui:audit.entityType.TARIFF}}, {{ui:audit.entityType.METER}}, {{ui:audit.entityType.METER_READING}}, {{ui:audit.entityType.CHARGE}}, {{ui:audit.entityType.LOSSES_RUN}}, {{ui:audit.entityType.COMMON_CONSUMER_RUN}}, {{ui:audit.entityType.RECALCULATION}} and {{ui:audit.entityType.PAYMENT}}.
- "{{ui:audit.filters.from}}" and "{{ui:audit.filters.to}}": the limits by date. If a date is wrong, the system shows it next to the field.
- The marks "{{ui:audit.filters.onlyRecord}}" and "{{ui:audit.filters.onlyAuthor}}" appear when the log is filtered to one record or one author. The cross on a mark ("{{ui:audit.filters.remove}}") cancels that filter. The log filtered to one member is opened by the "{{ui:members.history}}" button on the [member card](help:member-card).

Filters are kept in the page address, so a link to a filtered log can be shared. When there are no records yet, it says: "{{ui:audit.empty}}", and when a filter found nothing: "{{ui:audit.nothingFound}}". A page holds fifty records, with a page switch at the bottom.

### Columns

| Column | What is in it |
|---|---|
| {{ui:audit.columns.time}} | The date and hour of the action. |
| {{ui:audit.columns.author}} | Who did it. Clicking the name leaves only the records of this author. Actions done by the system itself are marked "{{ui:audit.system}}". |
| {{ui:audit.columns.action}} | What was done, for example "{{ui:audit.action.OWNERSHIP_CHANGED}}", "{{ui:audit.action.PLOT_UPDATED}}", "{{ui:audit.action.PAYMENT_REVERSED}}" or "{{ui:audit.action.SETTINGS_UPDATED}}". |
| {{ui:audit.columns.record}} | The kind of record and the record itself. For members, plots, tariffs, meters, recalculations and others it is a link to their card. If the record was deleted, it says "{{ui:audit.unlabelled}}". |
| {{ui:audit.columns.changes}} | The old and new value of every changed field, joined by an arrow. Only fields that really changed are shown. For a role you see the added ("+") and removed ("−") permissions. |

The log records almost every action that changes data: adding and changing members, roles, plots, owners, settings, import, charge types and tariffs, meters and readings, charges, reversals, month closing, recalculations, payments, reports, invoices, certificates, delivery, the cash desk and the bank.

## Worked example

The chairman wants to find out who changed the owner of plot 9.

1. Open "{{ui:nav.audit}}" and in "{{ui:audit.filters.entityType}}" choose "{{ui:audit.entityType.OWNERSHIP}}".
2. Find the record with the action "{{ui:audit.action.OWNERSHIP_CHANGED}}", the record "Plot 9" and the changes: the owners, the "{{ui:audit.fields.effectiveFrom}}" date, the basis.
3. Click the author's name: only their actions stay in the log.
4. Click "Plot 9" in the record column to open the [plot card](help:plot-card).

Suppose the chairman created a custom role that reads the log but does not have the permission "{{ui:permission.MEMBERS_VIEW.name}}". Such a person will see that the "{{ui:member.fields.phones}}" field of a member changed, but will read "{{ui:audit.hiddenValue}}" instead of the numbers. The chairman sees both the old and the new number.

## Screenshots

![The change log with filters and records](shot:audit)

## Related pages

- [Member card](help:member-card)
- [Plot card](help:plot-card)
- [Roles and permissions](help:roles)
- [Cooperative settings](help:settings)
