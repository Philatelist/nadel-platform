---
id: members
title: "Members"
description: "The list of cooperative members with search by name, phone and e-mail and a switch to show archived members."
kind: screen
routes:
  - /members
permissions:
  - MEMBERS_VIEW
section: plots-members
order: 40
shots:
  - name: members
    user: chairman
    path: /members
---

## What the screen is for

This is the list of all members of the cooperative. Here you look for a person, see their contacts, the way they receive invoices and their roles, and open the [member card](help:member-card). A new member is added from here.

## Who sees it

The list is seen by those who have the permission "{{ui:permission.MEMBERS_VIEW.name}}": the chairman and the treasurer. The "{{ui:members.add}}" button is seen by whoever also has "{{ui:permission.MEMBERS_EDIT.name}}". An ordinary member does not see this list: they find their own data in "{{ui:nav.account}}".

## Every element

### The "{{ui:members.add}}" button

On the right in the header. Opens the [new member form](help:member-form).

### Search

The search field hints: "{{ui:members.searchPlaceholder}}". Under it: "{{ui:members.searchHint}}". The system understands these queries.

- By name: any part of the surname or first name, in any letter case.
- By phone: the full number written in any usual way (+380 50 123 45 67, 0501234567, 380501234567), or exactly its last 4 digits.
- By e-mail: the full address, in any letter case.

> [!important]
> Phones, e-mails and addresses are stored encrypted, so a part of an e-mail (for example just what comes before @) or digits from the middle of a phone find nothing. If a member is not found, try the surname or the last 4 digits of the phone.

### The "{{ui:members.showArchived}}" switch

Archived members are not in the list by default. The switch adds them. They are marked "{{ui:members.archived}}".

### The table

| Column | What is in it |
|---|---|
| {{ui:member.fields.name}} | Surname, first name, patronymic. It is a link to the card. |
| {{ui:member.fields.phones}} | All phones separated by commas. |
| {{ui:member.fields.email}} | The e-mail. |
| {{ui:member.fields.deliveryChannel}} | How the member receives invoices: by e-mail, in Telegram, in Viber or on paper. |
| {{ui:member.fields.roles}} | The member's roles. |
| {{ui:members.columnStatus}} | "{{ui:members.active}}" or "{{ui:members.archived}}". |

If there are no members at all, it says: "{{ui:members.empty}}". If the search found nothing: "{{ui:members.nothingFound}}". A page holds twenty members, with a page switch at the bottom. The search and the archived switch are kept in the page address.

## Worked example

The treasurer got a call from a person who does not give a surname but dictates the number 0501234567.

1. Open "{{ui:nav.members}}".
2. Type 0501234567 in the search, or just the last 4 digits.
3. Open the row that was found and see which plots the person owns.

Among the members there is the archived Honcharenko Mykola, the former owner of plot 9. To see him in the list, turn on "{{ui:members.showArchived}}".

## Screenshots

![The member list with search](shot:members)

## Related pages

- [New member](help:member-form)
- [Member card](help:member-card)
- [Roles and permissions](help:roles)
- [Plot card: owners](help:plot-card)
- [Import from Excel](help:imports)
