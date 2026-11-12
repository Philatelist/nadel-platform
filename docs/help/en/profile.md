---
id: profile
title: "My details"
description: "A member's personal data, how invoices are received, Telegram, notifications on the device and, for the board, roles and rights."
kind: screen
routes:
  - /account/profile
permissions: []
section: member
order: 15
shots:
  - name: profile-page
    user: member
    path: /account/profile
  - name: profile-phone
    user: member
    path: /account/profile
    viewport: phone
---

## What the screen is for

{{ui:account.details}} gathers what is personal to you but not used every day: contacts, how you receive invoices, Telegram, notifications on this device and a hint on how to install Nadel on your phone. The everyday things (debt, invoices, meters) stay in the [account](help:account).

## How to open it

Press your name in the menu (on a phone it is the first row of the opened menu) or the "{{ui:account.details}}" link at the top of the account. The "{{ui:account.backToAccount}}" link returns to the account.

## Who sees it

Everyone who is signed in, and only their own data. The block with roles and rights is shown to board members only, that is, to those who hold at least one right. A plain member does not have this block.

## Every element

- The {{ui:account.details}} card: name, phones, e-mail, postal address, preferred channel for invoices, whether Telegram is connected. Only the board can change the data: write or tell them.
- The {{ui:notifications.title}} block: in the "{{ui:notifications.channelLegend}}" field choose e-mail, Telegram or paper and press {{ui:notifications.save}}. Below, if the cooperative has a bot, the {{ui:telegramLink.connect}} button (step by step: [How to connect Telegram](help:member-telegram)) and the switch "{{ui:notifications.push.title}}" (more: [Notifications on your phone](help:member-push)).
- The link "{{ui:pwa.howToInstall}}" under the notifications block leads to the [install instructions](help:member-install). It is not shown when Nadel already runs as an installed app.
- For the board: the {{ui:account.roles}} block with the cooperative name ({{ui:account.cooperative}}) and the {{ui:account.permissions}} block with a short explanation of each right.

## Worked example

Olena opened the account on her phone and wants invoices to arrive in Telegram.

1. She opens the menu and presses her name.
2. On the "{{ui:account.details}}" screen, in the "{{ui:notifications.title}}" block, she presses {{ui:telegramLink.connect}} and opens the bot.
3. Back on the screen she chooses Telegram in the "{{ui:notifications.channelLegend}}" field and presses {{ui:notifications.save}}. The system answers "{{ui:notifications.saved}}".

## Screenshots

![My details](shot:profile-page)

![My details on a phone](shot:profile-phone)

## Related pages

- [My account](help:account)
- [How to connect Telegram](help:member-telegram)
- [Notifications on your phone](help:member-push)
- [Install on your phone](help:member-install)
