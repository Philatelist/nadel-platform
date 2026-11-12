---
id: account
title: "My account"
description: "What a plot owner sees in their account: own plots, meters, expected and actual charges, invoices, notifications, Telegram and the reconciliation act."
kind: screen
routes:
  - /account
permissions: []
section: member
order: 10
shots:
  - name: plot-card-member
    user: member
    path: /account
    open: { css: 'main a[href^="/plots/"]', text: 'Plot 7' }
  - name: account-page
    user: member
    path: /account
  - name: account-phone
    user: member
    path: /account
    viewport: phone
  - name: account-invoices
    user: member
    path: /account
    element: { heading: Invoices }
  - name: account-charges
    user: member
    path: /account
    element: { css: 'section[aria-labelledby="my-charges"]' }
  - name: account-act
    user: member
    path: /account
    steps:
      - { click: Reconciliation act }
---

## What the screen is for

{{ui:account.title}} is the personal page of everyone who signs in, and the home screen of a cooperative member: after signing in they land here, it is the first item of their menu, and the logo in the header leads back here. A plot owner sees everything about their plots in one place: what will be charged, what is already charged and paid, their meters and invoices. From here you can submit a meter reading, download an invoice, and get a reconciliation act. Personal data, Telegram and notifications on the device are on a separate screen, [My details](help:profile).

## Who sees it

Every signed-in user sees the account, and everyone sees only their own data. Other people's plots, amounts, owners and personal data are not here. Members are not shown the list of all plots. The setting "Who sees plot colours" applies to the plot map that will come later, and the amounts of other plots are never shown.

If you have no plot, the parts about plots do not appear. Then the account is nearly empty, and your data and roles stay on the [My details](help:profile) screen.

## Every element

The screen is made of separate blocks. On screen the debt and invoices come first, then the meters, then the plots; here they are described by topic.

### Installing the app

At the top of the account there may be a card "{{ui:pwa.installTitle}}" with the button {{ui:pwa.install}}, and on an iPhone in Safari a card "{{ui:pwa.iosTitle}}" saying "{{ui:pwa.iosSteps}}". It offers to add Nadel to the home screen. The button "{{ui:pwa.dismiss}}" hides the card on this device. When Nadel is already installed, the card is not shown. Step by step: [Install on your phone](help:member-install).

### My details

Under the install card there is a link "{{ui:account.details}}". It opens a separate screen, [My details](help:profile): your contacts, your preferred channel for invoices, Telegram and notifications on the device. Your name in the menu leads there too. Roles and rights are shown there to board members only.

### My plots

The {{ui:ownership.myPlots}} block has a card for each of your plots: the number (it leads to the [plot card](help:plot-card)), the street and your share. Below are the co-owners and the history of owners. If you have no plots, the system says "{{ui:ownership.noOwnedPlots}}".

Next to each plot there is the {{ui:reconciliationAct.open}} button. It opens the {{ui:reconciliationAct.formTitle}} form: choose the dates in the {{ui:reconciliationAct.from}} and {{ui:reconciliationAct.to}} fields and press {{ui:reconciliationAct.download}}. You get a PDF with the charges, payments and the debt or overpayment at the start and the end of the period. The {{ui:reconciliationAct.close}} button folds the form. More: [Documents for a member](help:member-documents).

### The card of my plot

The plot number in the {{ui:ownership.myPlots}} card is a link: press it and the plot card opens, with a "{{ui:plot.backToAccount}}" link. The card shows the plot data (area, street, debt status), the owners with the ownership history, your account with charges and payments, the expected charges by tariffs, the plot's meters and its invoices. You have no "{{ui:plot.fields.notes}}" field and no buttons that change data, and the system does not show the card of someone else's plot. You do not need an overall view of all plots: you open everything of yours from here.

![Card of plot 7 as its owner sees it](shot:plot-card-member)

### My meters

The {{ui:readings.mine.title}} block shows the meters of your plots. For each meter you see its name, the mark {{ui:meters.active}} or {{ui:meters.removed}}, the last reading with its date, and the history of readings, each with a status: {{ui:readings.history.status.APPROVED}}, {{ui:readings.history.status.PENDING}}, {{ui:readings.history.status.REJECTED}} or {{ui:readings.history.status.SUPERSEDED}}. A rejected reading shows its reason.

The {{ui:readings.mine.submit}} button is next to a working meter. How to use it: [How to submit a meter reading](help:member-readings). If your plots have no meters, the system says "{{ui:readings.mine.empty}}".

### Invoices

The {{ui:invoices.myTitle}} block first shows the latest invoice of each of your plots. For each one you see the month, the number, the version, the "{{ui:invoices.due}}" amount and the date it was made. The {{ui:invoices.download}} button downloads the PDF. Older months are hidden under "{{ui:invoices.myOlder}}". If there are no invoices yet, the system says "{{ui:invoices.myEmpty}}". How to read an invoice: [How to read my invoice](help:member-bill).

Under an invoice that has an amount to pay there are buttons: {{ui:invoices.pay.payInBank}} opens the transfer in the banking app, {{ui:invoices.pay.copyIban}} and {{ui:invoices.pay.copyPurpose}} copy those values so that you can paste them into the bank. After a press the button says "{{ui:invoices.pay.copied}}" for a moment. If copying fails, the system says "{{ui:invoices.pay.copyFailed}}" and you can select and copy the value by hand. There are no buttons when there is nothing to pay.

### Notifications and Telegram

The choice of how to receive invoices, the Telegram connection and the switch for notifications on the device are now on the [My details](help:profile) screen. Step by step: [How to connect Telegram](help:member-telegram), [Notifications on your phone](help:member-push).

### Announcements

The {{ui:announcements.title}} block comes right after the meters. It shows the board's announcements of the last 12 months, newest first: the date and the full text. A new announcement you have not opened has a "New" mark. The mark disappears when you tap the announcement or open it from the phone notification. If there were none, you see "There are no announcements from the board yet."

### My charges and payments

The {{ui:charges.myTitle}} block shows the account of each of your plots read-only: the debt by charge types and every charge. The {{ui:charges.details}} button next to a charge opens the calculation: the rate, units, readings and days. The payments of your plot are there too: the date, the receipt or bank document number, the amount, what exactly it paid, reversed payments with a mark, the prepayment and the debt after payments. You do not see who made a payment or the note about the payer.

### Expected charges

The {{ui:tariffs.expected.myTitle}} block shows, for each plot, what the system will charge this month by the current tariffs, and the amount in the "{{ui:tariffs.expected.total}}" line. If the amount depends on meter readings, the line says "{{ui:tariffs.expected.byReadings}}" instead of an amount; "{{ui:tariffs.expected.noMeter}}" means the plot has no meter, and without a meter nothing is charged for that resource. How the amount comes out: [Tariffs and the expected amount](help:how-tariffs-expected).

## Worked example

Olena owns plot 9. She opens her account after the board has closed October.

1. In the {{ui:invoices.myTitle}} block she sees the October invoice. Its "{{ui:invoices.due}}" is 413.26. She presses {{ui:invoices.download}} and pays by the QR code in her banking app.
2. A few days later the {{ui:charges.myTitle}} block shows her payment with the bank document number and zero debt.
3. She reads her electricity meter, presses {{ui:readings.mine.submit}} in the {{ui:readings.mine.title}} block and enters the value and the date. The system answers "{{ui:readings.mine.submitted}}". After the board approves it, the reading becomes {{ui:readings.history.status.APPROVED}}.
4. To show the state of payments for the year to a bank or relatives, she presses {{ui:reconciliationAct.open}}, sets the period and downloads the PDF.

## Screenshots

![The member's account](shot:account-page)

![The account on a phone](shot:account-phone)

![The invoices block](shot:account-invoices)

![The "My charges" block](shot:account-charges)

![The reconciliation act form](shot:account-act)

## Related pages

- [How to read my invoice](help:member-bill)
- [How to submit a meter reading](help:member-readings)
- [Documents for a member](help:member-documents)
- [How to connect Telegram](help:member-telegram)
- [Install on your phone](help:member-install)
- [Notifications on your phone](help:member-push)
- [My details](help:profile)
- [Plot card](help:plot-card)
- [Getting started as a member](help:member-getting-started)
- [Getting started](help:getting-started)
