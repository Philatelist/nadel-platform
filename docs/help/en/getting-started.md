---
id: getting-started
title: "Getting started"
description: "For the board: what the system does, who sees what, how to sign in, where the menu is, how to change the language and theme, and how to use the help."
kind: topic
routes: []
permissions:
  - board
section: getting-started
order: 10
shots: []
---

## About the system

"{{ui:app.name}}" keeps the accounts of your cooperative: who the members are, which plots they own, what is charged and at which tariffs, how much electricity and water was used. Payments are entered at the cash desk and allocated to charges. Invoices, certificates and receipts are printed as PDF. Invoices also reach members by e-mail and in Telegram.

## Who sees what

Everyone signs in with their own account. What a person sees and can do is decided by their roles. A role is made of permissions: separate actions such as "enter payments". All permissions are described on the page [Roles and permissions](help:roles). A cooperative has three base roles.

| Role | What it can do |
|---|---|
| {{ui:roles.CHAIRMAN}} | Everything: members, roles, plots, owners, tariffs, meters, readings, charges, payments, the cash book, import, settings, the change log. The chairman's powers cannot be reduced. The cooperative always keeps at least one chairman. |
| {{ui:roles.TREASURER}} | Views members, plots, tariffs, meters, charges, reports and the change log. Enters payments, issues and sends invoices, keeps the cash book. Cannot reverse a payment, close a month or change the settings by default: the chairman does that. |
| {{ui:roles.MEMBER}} | Only their own data: their plots, the tariffs, charges and payments of those plots, their meters, passing readings. |

The chairman can create custom roles. For example, the "Electrician" role has just two permissions: "{{ui:permission.METERS_VIEW.name}}" and "{{ui:permission.READINGS_ENTER.name}}". A person with this role sees only "{{ui:nav.readings}}" and "{{ui:nav.meters}}" in the menu. A person can have several roles; their permissions are then added together.

## Signing in

Open the address of the system given by the board and press {{ui:auth.signIn}}. Signing in happens on a separate sign-in page: enter your e-mail and password. After signing in you land on the home page. Which one depends on your rights:

- someone who can see plots (has the permission "{{ui:permission.PLOTS_VIEW.name}}") lands on the plot list;
- an electrician with no plots of their own lands on readings or on the meter register;
- everyone else, including an ordinary member, lands on "{{ui:nav.account}}". It is their home screen; there is a separate page about it, [Getting started as a member](help:member-getting-started).

If signing in fails, the system says: "{{ui:auth.signInFailed}}". Try again. If it still does not work, contact the chairman.

The system finds you among the members by e-mail. If the member card has no e-mail or a different one, you will see "{{ui:access.notLinkedTitle}}". Ask the chairman to correct the e-mail in your [member card](help:member-card).

> [!important]
> The account used to sign in (e-mail and password) is created separately from the member card. There is no self-registration or invitation by e-mail yet: to get access, contact the chairman.

The {{ui:auth.signOut}} button in the header ends your session. If the session expires on its own, the system asks you to sign in again.

## The header and the menu

On the left of the header is the name of your cooperative, on the right your name. The name leads to "{{ui:nav.account}}", and the cooperative name with the logo leads to your home screen (the plot list, readings or "{{ui:nav.account}}", depending on your rights). Next to it are the language switch, the theme switch, the "?" button and the {{ui:auth.signOut}} button.

The menu on the left shows only the sections open to you. The chairman sees everything, the treasurer less. An ordinary member has neither "{{ui:nav.plots}}" nor "{{ui:help.title}}" in the menu: their menu starts with "{{ui:nav.account}}", and if they open the plot list by its address, it sends them to the account. A member opens their own plots from the account. What is not in the menu is not needed by you: the system does not show a section you have no access to. If you open such a page by a link, the system says: "{{ui:access.denied}}".

On a phone the menu hides behind the {{ui:nav.menu}} button.

## Language and theme

The language switch in the header changes between Ukrainian and English. The system remembers the choice in this browser. The interface always shows one language only: if English is selected, everything (names, hints, messages) is in English.

The theme has three options: {{ui:theme.system}}, {{ui:theme.light}} and {{ui:theme.dark}}. This choice is remembered too.

## The help

The "?" button in the header opens the help for the screen you are on. The help appears at the side, next to the screen: the screen stays usable, you can scroll and work with it while the panel is open. When you move to another screen, the panel shows the help for that screen, or closes if that screen has no help. On a phone the help takes the whole screen, and the "{{ui:help.back}}" button returns to the screen. The "{{ui:help.openFull}}" button opens the same page on its own. The "{{ui:help.close}}" button or the Esc key closes the panel.

The whole help opens from the "{{ui:help.title}}" item in the menu (it is there only for those who hold at least one permission; a member opens the help with the "?" button). Every help page has "← Back to …": it returns to the screen you opened it from. It has a table of contents by section and a "{{ui:help.search}}" field at the top: search by a word from the text or by a button name; one typo in the word does not matter. Each page explains one part of the system: what every button and field does, when to use it, what happens next and what to do if the system shows an error. There are examples with numbers and screenshots. On a help page you see a list of headings at the right: it helps you jump to the right place.

You see only the pages about the screens you can use. If you open an unavailable page by a link, the system says: "{{ui:help.hidden}}".

The help can also be read without signing in, at the same address.

## Related pages

- [Roles and permissions](help:roles)
- [Plots](help:plots)
- [Members](help:members)
- [Cooperative settings](help:settings)
- [Import from Excel](help:imports)
- [My account](help:account)
- [Enter a payment](help:payments-enter)
