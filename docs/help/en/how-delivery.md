---
id: how-delivery
title: "Which channel, and how sending is retried"
description: "Which channel an invoice goes by and how the system retries sending, with worked examples."
kind: topic
routes: []
permissions:
  - INVOICES_ISSUE
section: how-it-calculates
order: 120
shots: []
---

Delivery calculates nothing and changes no amounts: it only delivers documents that are already built. How to work with the screens is on the page [Delivery](help:deliveries); here are the rules for choosing a channel and for retries, with examples.

- **The channel is decided for each owner separately** — For every _current_ owner of a plot, the owner's preferred way (Telegram, e-mail, paper or Viber) is taken and checked for being possible: Telegram - the member is connected and the bot exists; e-mail - there is an address and a mailbox is set up. If not, the fallback of the table on the page [Delivery](help:deliveries) applies: from Telegram to e-mail, then paper; from e-mail to paper; Viber is always paper.
- **One message per plot and owner** — A member who owns two plots gets two messages, each with its own PDF. Two people who own a plot together get one message each, each through their own channel.
- **The same is never sent twice** — For one invoice version, one member and one channel there is exactly one delivery record. Pressing "Send invoices" again adds nothing; a new invoice version is sent as a new message.

## Worked examples

October 2026 invoices; the cooperative's mailbox and bot are set up.

| No. | Situation | What happens |
|---|---|---|
| 1 | Plot 9, one owner Olena: prefers Telegram, connected | Olena gets the invoice in Telegram: the text, the PDF and the "Оплатити" (Pay) button |
| 2 | Plot 7 is co-owned. Ivan: prefers Telegram, has not connected yet, has an e-mail address. Maria: prefers paper | Ivan gets a letter with the PDF; Maria is on the "to print" list, her copy is printed with its own QR code for connecting Telegram |
| 3 | Peter: prefers Viber (not supported yet). Hanna: prefers Telegram, has not connected, no e-mail address | Both are "to print"; Peter has the note {{ui:deliveries.note.VIBER_UNSUPPORTED}} on the Deliveries screen. When Hanna connects Telegram, the next invoices go there |
| 4 | A member owns plots 9 and 10, prefers e-mail | Two letters: one for plot 9, one for plot 10, each with its own PDF |
| 5 | The invoices are sent already and the treasurer presses "Send invoices" again | Nothing new: the system says everything has been sent |

## Sending attempts

Sending runs in the background at a limited pace: at most one message a second to one member, up to 20 messages a second per bot in Telegram, and e-mail at your {{ui:audit.fields.emailRatePerSecond}}. Every message has **three attempts**: now, after 1 minute and after 5 minutes. After the third failure the status is {{ui:deliveries.status.FAILED}} with the reason of the last attempt.

| Reason | Retried? | What it means |
|---|---|---|
| The mail server refused the letter | yes, up to three attempts | the mailbox did not accept the letter: the daily limit, too fast, an invalid recipient address |
| Could not connect | yes, up to three attempts | no connection to the mail server or Telegram |
| Wrong mailbox login or password | no, {{ui:deliveries.status.FAILED}} at once | the password does not fit; a retry changes nothing until it is corrected |
| The bot is blocked | no | the member blocked the bot; the connection is cancelled |
| No contact | no | no e-mail address or connected chat |
| The channel is not configured | no | no mailbox or bot, or Telegram rejects the token |

If Telegram asks to wait (too many requests), the message is simply moved to the time Telegram names, and that pause does _not_ use up an attempt. A message that became {{ui:deliveries.status.FAILED}} does not disappear: it stays on the Deliveries screen, and after the cause is fixed it is sent with {{ui:deliveries.retry}}.
