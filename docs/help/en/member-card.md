---
id: member-card
title: "Member card"
description: "Contacts, roles, owned plots and notes of one cooperative member; editing, roles, archiving and change history."
kind: screen
routes:
  - /members/:id
permissions:
  - MEMBERS_VIEW
section: plots-members
order: 60
shots:
  - name: member-card
    user: chairman
    path: /members
    open: { css: 'main a[href^="/members/"]:not([href$="/new"])' }
  - name: member-card-roles
    user: chairman
    path: /members
    open: { css: 'main a[href^="/members/"]:not([href$="/new"])' }
    steps:
      - { click: Edit roles }
  - name: member-card-access
    user: chairman
    path: /members
    open: { css: 'main a[href^="/members/"]:not([href$="/new"])' }
    element: { heading: System access }
---

## What the screen is for

The card shows one person: contacts, the way they receive invoices, roles, the plots they own or used to own, and service notes. Here the member is edited, given roles and archived.

## Who sees it

The card is seen by whoever has the permission "{{ui:permission.MEMBERS_VIEW.name}}". The buttons depend on permissions:

- "{{ui:memberForm.edit}}" and "{{ui:memberForm.archive}}" or "{{ui:memberForm.restore}}": "{{ui:permission.MEMBERS_EDIT.name}}";
- "{{ui:rolesPage.memberRoles}}": "{{ui:permission.ROLES_MANAGE.name}}";
- "{{ui:members.history}}": "{{ui:permission.AUDIT_VIEW.name}}";
- the buttons of the "{{ui:memberAccess.title}}" block: "{{ui:permission.MEMBERS_INVITE.name}}".

Notes are seen only by those who have "{{ui:permission.MEMBERS_VIEW.name}}". The "{{ui:memberAccess.title}}" block is seen by those who have "{{ui:permission.MEMBERS_VIEW.name}}"; the member does not see it on their own card. Every member sees their own data in "{{ui:nav.account}}".

## Every element

At the top are the link "{{ui:members.back}}" and then the member's name. For an archived member the mark "{{ui:members.archived}}" stands next to it.

### Buttons in the header

- **{{ui:members.history}}** opens the [change log](help:audit) filtered to this member.
- **{{ui:rolesPage.memberRoles}}** replaces the card with a list of roles with tick boxes. Tick the ones you need and press {{ui:rolesPage.save}}, or {{ui:rolesPage.cancel}}. Permissions add up: when there are several roles, the person has the permissions of all.
- **{{ui:memberForm.edit}}** opens the [form](help:member-form) with the current data. After saving the system says "{{ui:memberForm.saved}}".
- **{{ui:memberForm.archive}}** removes the member from the list but keeps all the history. The system asks: "{{ui:memberForm.archiveTitle}}", and explains: "{{ui:memberForm.archiveText}}" The {{ui:memberForm.restore}} button brings them back: "{{ui:memberForm.restoreTitle}}", "{{ui:memberForm.restoreText}}"

> [!important]
> A member is not deleted but archived. An owner of a plot cannot be archived until the plot passes to another owner: the system says "{{ui:problem.MEMBER_OWNS_PLOT}}". The last chairman cannot be archived or stripped of the role: "{{ui:problem.LAST_CHAIRMAN}}".

### Member data

The table shows: {{ui:member.fields.name}}, {{ui:member.fields.phones}}, {{ui:member.fields.email}}, {{ui:member.fields.postalAddress}}, {{ui:member.fields.deliveryChannel}}, the Telegram nickname and the Viber phone (when they exist), the Telegram state, {{ui:member.fields.notes}} and {{ui:member.fields.roles}}. For Telegram the card shows only "{{ui:telegramLink.memberLinked}}" or "{{ui:telegramLink.memberNotLinked}}", without data of the member's own account. Connecting and disconnecting go to the change log.

### The "{{ui:memberAccess.title}}" block

Shows whether the person can sign in to the system. There are four states:

- "{{ui:memberAccess.status.NOT_INVITED}}": no invitation has been sent yet;
- "Invited <date>, waiting for a password": the letter was sent, the person has not set a password yet;
- "Has access, last sign-in <date>" or "Has access, has not signed in yet": the password is set;
- "{{ui:memberAccess.status.DISABLED}}": signing in is switched off.

The buttons are seen by whoever has the permission "{{ui:permission.MEMBERS_INVITE.name}}":

- **{{ui:memberAccess.invite}}** sends the letter "{{ui:setPassword.title}}" to the e-mail on the card, with a link to the [set-password page](help:set-password). The letter comes from the cooperative's mailbox; the link is valid for 72 hours and works once. If the member has connected Telegram, the link also arrives from the bot. The system says: "{{ui:memberAccess.invited}}"
- **{{ui:memberAccess.resend}}** sends a new link; the previous one stops working. The system says: "{{ui:memberAccess.resent}}"
- **{{ui:memberAccess.disable}}** removes the sign-in, but the card and the data stay. The system asks to confirm: "{{ui:memberAccess.disableTitle}}". The person can be invited again afterwards.

When a button is unavailable, the reason is written beside it: "{{ui:memberAccess.blocked.EMAIL_MISSING}}" (the e-mail is the login) or "{{ui:memberAccess.blocked.MAILBOX_NOT_CONFIGURED}}" (see the [cooperative settings](help:settings)). When the letter could not be sent, the system says the invitation was not sent and suggests trying again.

Other rules:

- an e-mail that a member of another cooperative already uses is refused;
- archiving a member switches their access off, and restoring does not switch it on: invite the person again;
- nobody can switch off their own access: their own card has no "{{ui:memberAccess.disable}}" button;
- the access of the last chairman who has access cannot be switched off, and that chairman cannot be archived either: the system says "{{ui:memberAccess.lastChairman}}";
- when the e-mail of a member who has access is changed, a new link is sent to the new address.

Invitations, resends and switching access off are written to the [change log](help:audit).

### The "{{ui:ownership.plotsTitle}}" panel

Shows two lists: "{{ui:ownership.ownsNow}}" (plot, share, the date from which) and "{{ui:ownership.ownedBefore}}" (plot and period). The plot number leads to the [plot card](help:plot-card) if you can view plots. If the person owns nothing, it says: "{{ui:ownership.noPlotsOwned}}" or "{{ui:ownership.noPlotsBefore}}".

## Worked example

The chairman opens the card of the member Bondarenko Andrii Mykolaiovych.

1. "{{ui:ownership.ownsNow}}" shows two plots: 7 (share 1/2 since 15.04.2019) and 15 (the whole plot since 25.03.2021).
2. The "{{ui:member.fields.roles}}" field shows only "{{ui:roles.MEMBER}}". The chairman presses {{ui:rolesPage.memberRoles}}, ticks one more role and saves: the permissions of the roles add up.
3. The chairman tries to press {{ui:memberForm.archive}}, but the system refuses: Andrii owns plots. First the owners are changed on the [plot card](help:plot-card), then the member can be archived.

Another example: Polishchuk Oksana, who keeps the member list, has, besides the base role, a custom role "Секретар" (Secretary) with permissions to view and edit members, plots and ownership. In her card the "{{ui:member.fields.roles}}" field shows both roles.

## Screenshots

![Member card: contacts, roles, plots](shot:member-card)

![Choosing roles on the member card](shot:member-card-roles)

![The system access block on the member card](shot:member-card-access)

## Related pages

- [Members](help:members)
- [New member and editing](help:member-form)
- [Roles and permissions](help:roles)
- [Plot card](help:plot-card)
- [Change log](help:audit)
- [Set a password](help:set-password)
