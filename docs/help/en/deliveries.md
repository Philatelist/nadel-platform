---
id: deliveries
title: "Delivery"
description: "What was sent to whom in a month: invoices, payment notifications and announcements by channel, statuses, error reasons, sending again and the Printed mark."
kind: screen
routes:
  - /deliveries
permissions:
  - INVOICES_ISSUE
section: documents-delivery
order: 20
shots:
  - name: deliveries
    user: treasurer
    path: /deliveries
---

## What the screen is for

The system sends invoices, payment notifications and board announcements to the cooperative's members through two channels that belong to the cooperative itself: e-mail from your mailbox and Telegram through the cooperative's own bot. This screen shows what was sent in a month and how each sending ended. The treasurer sees to whom an invoice arrived and who needs paper.

## Who sees it

The screen is seen by those who have "{{ui:permission.INVOICES_ISSUE.name}}": the chairman and the treasurer. The link to an announcement from the rows is seen by those who also have "{{ui:permission.ANNOUNCEMENTS_SEND.name}}". The channels are set up by the chairman on the [Settings](help:settings) page, in the section "Delivery".

## Every element

### The summary for the month

At the top there is the table "{{ui:deliveries.counts.title}}" by channel: {{ui:deliveries.counts.queued}}, {{ui:deliveries.counts.sent}}, {{ui:deliveries.counts.failed}} and {{ui:deliveries.counts.toPrint}}.

### Filters

| Filter | What it does |
|---|---|
| {{ui:deliveries.filters.month}} | The month the delivery is shown for. The current one by default. |
| {{ui:deliveries.filters.kind}} | "{{ui:deliveries.kind.INVOICE}}", "{{ui:deliveries.kind.PAYMENT}}", "{{ui:deliveries.kind.REVERSAL}}", "{{ui:deliveries.kind.ANNOUNCEMENT}}" or "{{ui:deliveries.filters.all}}". |
| {{ui:deliveries.filters.status}} | The state of a sending (below). |
| {{ui:deliveries.filters.channel}} | Telegram, e-mail, paper. |

When nothing was sent in the month you see "{{ui:deliveries.empty}}", and if the filters leave nothing, "{{ui:deliveries.nothingFound}}".

### The table

Columns: {{ui:deliveries.columns.plot}}, {{ui:deliveries.columns.member}}, {{ui:deliveries.columns.channel}}, {{ui:deliveries.columns.status}}, {{ui:deliveries.columns.reason}}, {{ui:deliveries.columns.attempts}}, {{ui:deliveries.columns.sentAt}} and {{ui:deliveries.columns.actions}}.

| Status | What it means |
|---|---|
| {{ui:deliveries.status.QUEUED}} | Waits for its turn or the next attempt. |
| {{ui:deliveries.status.SENT}} | The mail server or Telegram accepted the message. This does not yet mean the person read it. |
| {{ui:deliveries.status.FAILED}} | It did not work. The "{{ui:deliveries.columns.reason}}" column says why. |
| {{ui:deliveries.status.TO_PRINT}} / {{ui:deliveries.status.PRINTED}} | An invoice for paper, until the board presses "{{ui:deliveries.printDone}}". |

For announcement rows, if you have the right to send announcements, a link to the [announcement card](help:announcement-card) is added.

For a member who chose Viber, the row has the note "{{ui:deliveries.note.VIBER_UNSUPPORTED}}": the invoice goes to them on paper.

### Error reasons and what to do

| Reason | What to do |
|---|---|
| {{ui:deliveries.reason.SMTP_REJECTED}} | The mailbox refused the letter (a limit, speed, the address not accepted). The system will try twice more. If it does not pass, reduce "Letters per second" in the settings, wait until the next day and send again. |
| {{ui:deliveries.reason.SMTP_AUTH_FAILED}} | Correct the app password in the "Delivery" settings and send a test letter. After that press "{{ui:deliveries.retry}}". |
| {{ui:deliveries.reason.CONNECTION_FAILED}} | No connection to the mail server or Telegram. This is temporary, the system repeats. Check the server address and port. |
| {{ui:deliveries.reason.TELEGRAM_BLOCKED}} | The member blocked the bot. The connection is cancelled, invoices go through the fallback channel until they connect Telegram again. |
| {{ui:deliveries.reason.CHANNEL_NOT_CONFIGURED}} | There is no mailbox or bot (or Telegram rejected the token). Set the channel up in "Delivery". |
| {{ui:deliveries.reason.NO_CONTACT}} | The member has no e-mail address or Telegram chat. Contact them personally or enter the address in the member card. |
| {{ui:deliveries.reason.SMTP_HOST_NOT_ALLOWED}} | The system connects only to mail services on the internet on ports 25, 465 or 587. Enter another server. |

> [!important]
> Gmail accepts only an app password from programs, not the ordinary account password. If mail does not work, first turn on two-step verification in the Google account, create an app password and paste it in the "Delivery" settings.

### Buttons

- "{{ui:deliveries.retry}}" in a row with the status "{{ui:deliveries.status.SENT}}" or "{{ui:deliveries.status.FAILED}}". After fixing the reason, send the message again. For paper and for messages still in the queue it is not there.
- "{{ui:deliveries.printDone}}" removes the "to print" mark from all paper invoices of the chosen month. The system asks for confirmation: "{{ui:deliveries.printDoneTitle}}" (the number of invoices is stated in the window) and afterwards says how many were marked ("{{ui:deliveries.printDoneResult}}").

"{{ui:deliveries.status.SENT}}" and "{{ui:deliveries.status.PRINTED}}" do not change any amounts. They are only a record of delivery.

### Who gets which channel

The decision depends on the member's preferred way (visible in the member card and changed in their account) and on what is set up.

| Preferred way | Situation | Receives |
|---|---|---|
| Telegram | connected, the bot is set up | in Telegram |
| Telegram | not connected or no bot | by e-mail if there is an address and a mailbox is set up; otherwise on paper |
| E-mail | there is an address, the mailbox is set up | by e-mail |
| E-mail | no address or no mailbox set up | on paper |
| Paper | — | on paper (a PDF to print) |
| Viber | — | on paper, with a note |

The invoice of a plot is received by each of its current owners through their own channel. A member with several plots gets a separate message for each.

### Payment notifications

When "Notify about payments" is on in the settings, after a payment is saved each current owner who receives messages in Telegram or by e-mail gets, for example, "Received 500.00 ₴ for plot 9 (28.10.2026). Debt: 1,234.56 ₴" (or "Prepayment: …" if there is no debt). For cash, the receipt PDF is added. After a reversal comes "The payment of 28.10.2026 for 500.00 ₴ is reversed. Debt: …". Owners on paper do not get such messages. With the switch off, there are no messages at all.

## Worked example

The treasurer sent the invoices for the month, and for plot 9 the row has the status "{{ui:deliveries.status.FAILED}}" with the reason "{{ui:deliveries.reason.NO_CONTACT}}".

1. Open "Delivery", choose the month and the filter "{{ui:deliveries.filters.status}}" → "{{ui:deliveries.status.FAILED}}".
2. Open the member card and enter an e-mail address.
3. Return to the delivery and press "{{ui:deliveries.retry}}" by this row. The status changes to "{{ui:deliveries.status.QUEUED}}", then to "{{ui:deliveries.status.SENT}}".
4. After printing the paper invoices press "{{ui:deliveries.printDone}}" and confirm.

## Screenshots

![Delivery for a month: summary by channel, filters and a list with error reasons](shot:deliveries)

## Related pages

- [Announcements](help:announcements)
- [Charges of a month: invoices and sending](help:charges-month)
- [Settings: delivery](help:settings)
- [How invoice delivery works](help:how-delivery)
