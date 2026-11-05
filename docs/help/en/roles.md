---
id: roles
title: "Roles and permissions"
description: "How to set who can do what in the cooperative: base and custom roles, the full list of permissions, creating, changing and deleting roles."
kind: screen
routes:
  - /roles
permissions:
  - ROLES_MANAGE
section: settings
order: 20
shots:
  - name: roles
    user: chairman
    path: /roles
  - name: roles-new
    user: chairman
    path: /roles
    steps:
      - { click: Add role }
---

## What the screen is for

Here the chairman sets who can do what. A role is a set of permissions, and a permission is a separate action, for example "enter payments". A member is given one or several roles and sees in the system only what their roles allow.

## Who sees it

The screen is seen by whoever has the permission "{{ui:permission.ROLES_MANAGE.name}}". By default this is the chairman. Roles are given to members on the [member card](help:member-card) with the "{{ui:rolesPage.memberRoles}}" button.

## Every element

### The list of roles

Every role is a row with a name, the mark "{{ui:rolesPage.baseRole}}" or "{{ui:rolesPage.customRole}}", a description and the number of members with this role. On the right are the {{ui:rolesPage.edit}} button and, for custom roles, {{ui:rolesPage.delete}}. If there are no roles yet, it says: "{{ui:rolesPage.empty}}".

- **Base roles** are "{{ui:roles.CHAIRMAN}}", "{{ui:roles.TREASURER}}" and "{{ui:roles.MEMBER}}". They cannot be deleted. They can be renamed: the name and description change for all languages. The permissions of the treasurer and the member can be changed, those of the chairman cannot.
- **Custom roles** are created with the "{{ui:rolesPage.add}}" button. They have a name, a description and any set of permissions. A person can have several roles; their permissions are then added together.

### The "{{ui:rolesPage.add}}" button and the role form

The "{{ui:rolesPage.newTitle}}" form (for an existing role, its name) has these fields.

- "{{ui:rolesPage.name}}". For a base role there is a hint about the standard name under the field. The {{ui:rolesPage.restoreName}} button cancels the renaming. If another role already has this name, the system warns: "{{ui:rolesPage.nameClash}}".
- "{{ui:rolesPage.description}}". For base roles the {{ui:rolesPage.restoreDescription}} button returns the standard description.
- "{{ui:rolesPage.permissions}}". A list of tick boxes: next to each permission are its name and an explanation. Tick the ones you need. For the chairman all boxes are always ticked, and the system says: "{{ui:rolesPage.chairmanNote}}"
- The {{ui:rolesPage.save}} and {{ui:rolesPage.cancel}} buttons. After saving: "{{ui:rolesPage.saved}}". Everything is recorded in the [change log](help:audit).

### The "{{ui:rolesPage.delete}}" button

The system asks "{{ui:rolesPage.deleteTitle}}" and waits for confirmation. After deleting: "{{ui:rolesPage.deleted}}".

### Error messages

| What is shown | What to do |
|---|---|
| {{ui:problem.ROLE_NAME_TAKEN}} | Choose another name. |
| {{ui:problem.ROLE_IN_USE}} | First remove the role from all members on their cards. |
| {{ui:problem.BASE_ROLE_IMMUTABLE}} | The chairman's permissions cannot be changed. Adjust the other roles instead. |
| {{ui:problem.LAST_CHAIRMAN}} | Give the chairman role to another member before taking it from the last one. |

### Permissions

Here are all the permissions that can be given to a role. The right column shows who has them by default. The chairman always has all permissions. Custom roles and the role "{{ui:roles.MEMBER}}" have nothing until you tick it.

| Permission | What it gives | By default |
|---|---|---|
| {{ui:permission.MEMBERS_VIEW.name}} | See all members, their contacts and notes | chairman, treasurer |
| {{ui:permission.MEMBERS_EDIT.name}} | Add, change, archive and restore members | chairman |
| {{ui:permission.MEMBERS_INVITE.name}} | Invite members to sign in, resend an invitation and switch access off | chairman |
| {{ui:permission.ROLES_MANAGE.name}} | Create, change and delete custom roles, change the permissions of base roles, give roles | chairman |
| {{ui:permission.AUDIT_VIEW.name}} | Read the change log | chairman, treasurer |
| {{ui:permission.PLOTS_VIEW.name}} | See all plots with details | chairman, treasurer |
| {{ui:permission.PLOTS_EDIT.name}} | Add, change and archive plots | chairman |
| {{ui:permission.OWNERSHIP_EDIT.name}} | Add, change and correct owners | chairman |
| {{ui:permission.IMPORT_RUN.name}} | Load data from Excel | chairman |
| {{ui:permission.SETTINGS_MANAGE.name}} | Change the settings of the whole cooperative | chairman |
| {{ui:permission.TARIFFS_VIEW.name}} | See charge types, tariffs and versions | chairman, treasurer |
| {{ui:permission.TARIFFS_MANAGE.name}} | Create and change charge types, tariffs and versions | chairman |
| {{ui:permission.METERS_VIEW.name}} | See the meter register and tree and their readings | chairman, treasurer |
| {{ui:permission.METERS_MANAGE.name}} | Register, change, replace and remove meters; build the tree; connect plots to water | chairman |
| {{ui:permission.READINGS_ENTER.name}} | Enter, correct and delete readings, for example on a round | chairman |
| {{ui:permission.READINGS_APPROVE.name}} | Approve or reject readings passed by members | chairman |
| {{ui:permission.CHARGES_VIEW.name}} | See the account of every plot: charges, debt by type, the calculation of every charge | chairman, treasurer |
| {{ui:permission.CHARGES_RUN.name}} | Enter opening balances and run charges | chairman |
| {{ui:permission.CHARGES_REVERSE.name}} | Reverse a charge with a reason | chairman |
| {{ui:permission.PERIODS_CLOSE.name}} | Close charge months | chairman |
| {{ui:permission.RECALCULATION_RUN.name}} | View and run recalculation of past charges | chairman |
| {{ui:permission.REPORTS_VIEW.name}} | Open and download reports, build files from templates (the debtors report also needs "{{ui:permission.MEMBERS_VIEW.name}}") | chairman, treasurer |
| {{ui:permission.REPORT_TEMPLATES_MANAGE.name}} | Upload, replace, download and archive report templates | chairman |
| {{ui:permission.PAYMENTS_VIEW.name}} | See payments, their allocation and the payments report | chairman, treasurer |
| {{ui:permission.PAYMENTS_ENTER.name}} | Enter cash and bank payments, split them between plots, reallocate, allocate prepayment | chairman, treasurer |
| {{ui:permission.PAYMENTS_REVERSE.name}} | Reverse a payment with a reason | chairman |
| {{ui:permission.INVOICES_ISSUE.name}} | Build, reissue, print and send invoices with a QR code | chairman, treasurer |
| {{ui:permission.CERTIFICATES_ISSUE.name}} | Issue and annul certificates of the state of settlements | chairman |
| {{ui:permission.ANNOUNCEMENTS_SEND.name}} | Send announcements by e-mail and in Telegram and print them | chairman |
| {{ui:permission.CASHBOOK_VIEW.name}} | See the cash desk and the bank, journals, expenses, cash counts, reconciliations and cash reports | chairman, treasurer |
| {{ui:permission.CASHBOOK_ENTER.name}} | Enter expenses, bank handovers, other income, cash counts, statement figures | chairman, treasurer |
| {{ui:permission.CASHBOOK_APPROVE.name}} | Enter opening balances, book differences, reverse cash entries, keep expense and income categories | chairman |

> [!important]
> A change of permissions takes effect immediately after saving: if you remove a permission, the person loses both the button and the access to the data. The check is done by the system, not only by the screen, so hiding a button is not enough. A change of a role's permissions is written to the change log, which shows the added ("+") and removed ("−") permissions.

## Worked example

The cooperative has an electrician who only takes readings.

1. Open "{{ui:nav.roles}}" and press {{ui:rolesPage.add}}.
2. In the "{{ui:rolesPage.name}}" field enter "Electrician", in "{{ui:rolesPage.description}}" briefly what the role is for.
3. In "{{ui:rolesPage.permissions}}" tick only "{{ui:permission.METERS_VIEW.name}}" and "{{ui:permission.READINGS_ENTER.name}}". Press {{ui:rolesPage.save}}.
4. Open the [member card](help:member-card), press {{ui:rolesPage.memberRoles}} and add the new role to "{{ui:roles.MEMBER}}".
5. The member signs in again: in the menu they see "{{ui:nav.readings}}" and "{{ui:nav.meters}}", and the other sections are not shown. The row of this role in the list shows "Members: 1".

In the demo cooperative this is the role "Електрик" (Electrician; held by Tkachenko Serhii Ivanovych, currently without permissions) and also the custom role "Секретар" (Secretary) with permissions to view and edit members, plots and ownership.

## Screenshots

![The list of the cooperative's roles](shot:roles)

![The new role form with the list of permissions](shot:roles-new)

## Related pages

- [Member card: giving roles](help:member-card)
- [Members](help:members)
- [Change log](help:audit)
- [Getting started](help:getting-started)
