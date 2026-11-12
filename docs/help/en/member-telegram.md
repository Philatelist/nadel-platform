---
id: member-telegram
title: "How to connect Telegram"
description: "How a plot owner connects the cooperative's Telegram bot to get invoices, see the debt and submit a reading, and how to disconnect it."
kind: topic
routes: []
permissions:
  - member
section: member
order: 50
shots:
  - name: member-telegram-panel
    user: member
    path: /account
    element: { heading: Notifications }
---

Telegram lets invoices come to you straight in the messenger. A bot cannot write first to a person by phone number or nickname, so once you must open the bot by your personal link. There are two ways.

The connect button is there only when your cooperative has a bot set up. If it is missing, ask the board.

![The "Notifications" block in the account](shot:member-telegram-panel)

## Way 1. From the account

1. Open your [account](help:account), the {{ui:notifications.title}} block, and below it the {{ui:telegramLink.title}} block.
2. Press {{ui:telegramLink.connect}}.
3. The cooperative's bot opens with a one-time code. {{ui:telegramLink.hint}}
4. The bot greets you by name and shows menu buttons.
5. Go back to the account. It now says "{{ui:telegramLink.connected}}" and has the {{ui:telegramLink.disconnect}} button.

## Way 2. From a paper invoice

Every paper copy of an invoice has a small QR code "Receive invoices in Telegram" with a personal code of exactly your copy. It works for 90 days.

1. Point your phone camera at the QR code.
2. Open the bot.
3. Press "Start".

Co-owners who get paper each have their own copy with their own code.

## If it did not work

The code works only once. If the link is used, expired or unknown, the bot answers "The link is not valid, get a new one in your account or from the board". Just press {{ui:telegramLink.connect}} again.

If this Telegram is already connected to another member, the bot asks whether it is you. The connection moves only after the answer "Yes, it is me".

## How to choose what you receive

In the {{ui:notifications.title}} block, in the "{{ui:notifications.channelLegend}}" field, choose Telegram, e-mail or paper and press {{ui:notifications.save}}. E-mail is available when your card has your address. Paper is always available. If the preferred channel is not available, the system sends the invoice another way, for example by e-mail or on paper. Viber cannot be chosen yet: the system says "{{ui:notifications.viberUnsupported}}".

## What the bot can do

The bot shows only your own plots. The menu has buttons.

| Button | What it does |
|---|---|
| "Debt" | For each of your plots: the debt by charge types, the total and the prepayment, as in the account |
| "Invoice" | The invoice for today: a PDF and a "Pay" button. If you have several plots, choose a plot first |
| "Reading" | Choose a plot, then a meter, and send the number. The reading goes to the board for approval, dated today. If the board requires a photo of the reading, the bot does not accept it and asks you to submit it with a photo in the app |
| "Disconnect" | Stop receiving invoices in Telegram. The command `/stop` does the same |

When the board approves or rejects a reading, the bot tells you. The commands `/start` and `/help` show the menu and a short help. To any other text the bot answers with the help. Do not send more than 20 messages a minute: the bot will ask you to wait.

If the bot is opened in a chat that is not connected yet, it shows only the help and an invitation to connect, with no amounts.

## How to disconnect Telegram

In the account press {{ui:telegramLink.disconnect}} and confirm in the "{{ui:telegramLink.disconnectTitle}}" window. {{ui:telegramLink.disconnectMessage}}

## Which channel the invoice comes by

The decision depends on your preferred way.

| Preferred way | Situation | You receive |
|---|---|---|
| Telegram | Connected and the bot is set up | In Telegram |
| Telegram | Not connected or no bot | By e-mail if there is an address and a mailbox is set up, otherwise on paper |
| E-mail | There is an address and a mailbox is set up | By e-mail |
| E-mail | No address or no mailbox set up | On paper |
| Paper | — | On paper |

If a plot has co-owners, each of them gets the invoice by their own channel. An owner of several plots gets a separate message for each.

## Related pages

- [My account](help:account)
- [Documents for a member](help:member-documents)
- [How to read my invoice](help:member-bill)
