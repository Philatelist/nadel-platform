---
id: settings
title: "Cooperative settings"
description: "All cooperative settings: plot colours, debt threshold, losses, prepayment, common consumers, requisites, mail and Telegram, water, expense categories."
kind: screen
routes:
  - /settings
permissions:
  - SETTINGS_MANAGE
  - METERS_MANAGE
section: settings
order: 10
shots:
  - name: settings
    user: chairman
    path: /settings
  - name: settings-thresholds
    user: chairman
    path: /settings
    element: { heading: Red status threshold }
  - name: settings-delivery
    user: chairman
    path: /settings
    element: { heading: Delivery }
---

## What the screen is for

Here the chairman sets the rules by which the cooperative works in the system: who sees plot colours, when a plot is red, how losses and common consumers are charged, whether a prepayment goes to new charges by itself, which requisites are printed on documents, which mailbox and which bot send the messages, which water is connected to whom, and which categories the cash book uses. Every panel is saved separately with its own button.

## Who sees it

The screen shows the panels you have a permission for.

| Panel | Permission |
|---|---|
| Plot colours, threshold, losses, payments, common consumers, requisites, delivery | "{{ui:permission.SETTINGS_MANAGE.name}}" |
| "{{ui:water.sourcesTitle}}" | "{{ui:permission.PLOTS_VIEW.name}}"; the buttons in it need "{{ui:permission.METERS_MANAGE.name}}" |
| "{{ui:expenseCategories.title}}" | "{{ui:permission.CASHBOOK_APPROVE.name}}" |
| The link "{{ui:settings.meterTreeLink}}" | "{{ui:permission.METERS_MANAGE.name}}" |

By default the chairman has all of this. The treasurer does not see the settings screen. The menu item "{{ui:nav.settings}}" appears if there is a permission for settings or for managing meters.

Every change of settings is written to the [change log](help:audit). If saving succeeded, "{{ui:settings.saved}}" appears above the button.

## Every element

### Who sees plot colours

A panel with the choice "{{ui:settings.visibilityLegend}}".

- "{{ui:settings.visibility.OWN_ONLY.label}}" (the default). {{ui:settings.visibility.OWN_ONLY.description}}
- "{{ui:settings.visibility.ALL_PLOTS.label}}". {{ui:settings.visibility.ALL_PLOTS.description}}

The {{ui:settings.save}} button is active only when the choice has changed.

> [!important]
> Neighbours know whose plot is whose, so the colour effectively shows the debtor. Turn on "{{ui:settings.visibility.ALL_PLOTS.label}}" only by a decision of the general meeting. Under the choice the system reminds you: "{{ui:settings.visibilityHint}}". Amounts, owners and personal data of other people's plots are never shown to a member under any setting.

### The "{{ui:settings.threshold.title}}" panel

A plot turns red when its debt reaches a certain amount or its oldest debt has lasted a certain number of days. Any other debt is yellow. The fields:

- "{{ui:settings.threshold.redAmount}}". An amount with kopecks, for example 1500.00.
- "{{ui:settings.threshold.redDays}}". A whole number of days.

The {{ui:settings.threshold.save}} button. Errors: "{{ui:settings.threshold.amountInvalid}}" and "{{ui:settings.threshold.daysInvalid}}". The new condition applies at once to the plot colours in the list and on the map.

### The "{{ui:settings.losses.title}}" panel

Losses of a node of the meter tree (see [Meter tree](help:meter-tree)). There are two modes in the "{{ui:settings.losses.mode}}" field:

- "{{ui:settings.losses.FIXED_PERCENT}}": the percentage from the "{{ui:settings.losses.percent}}" field is added to the consumption charge of every plot;
- "{{ui:settings.losses.PROPORTIONAL}}": the node's loss calculation divides the losses between the plots of the node by itself. The percentage does not apply in this mode, the field is disabled, and the system explains: "{{ui:settings.losses.proportionalHint}}"

The {{ui:settings.losses.save}} button. The percentage must be from 0 to 100, for example 5 or 5.25, otherwise the system answers: "{{ui:settings.losses.percentInvalid}}". More about the calculation: [Losses](help:losses).

### The "{{ui:settings.payments.title}}" panel

The switch "{{ui:settings.payments.autoAllocatePrepayment}}" (on by default). {{ui:settings.payments.autoAllocatePrepaymentHint}} The {{ui:settings.payments.save}} button.

> [!important]
> When the switch is on, the free part of a payment goes straight to the oldest debts of the plot and does not stay as a prepayment. A prepayment stays only when the plot has no open debts. You can see this in the [payment form](help:payments-enter).

### The "{{ui:settings.commonConsumers.title}}" panel

How the consumption of every common meter (the well pump, street lighting) is divided between plots. {{ui:settings.commonConsumers.text}}

- The choice "{{ui:settings.commonConsumers.defaultLegend}}": "{{ui:billing.common.split.CONNECTED_PLOTS}}", "{{ui:billing.common.split.EQUAL_PER_PLOT}}", "{{ui:billing.common.split.SERVICE_USERS}}" or "{{ui:billing.common.split.NOT_CHARGED}}". The {{ui:settings.commonConsumers.saveDefault}} button.
- Below, for every common meter, the "{{ui:settings.commonConsumers.consumerSplit}}" field: keep the default or choose its own way. If "{{ui:billing.common.split.SERVICE_USERS}}" is chosen, the "{{ui:settings.commonConsumers.serviceUsers}}" list appears: add the plots that use this consumer with the search "{{ui:settings.commonConsumers.pickPlot}}". The {{ui:settings.commonConsumers.save}} button. If there are no common consumer meters yet, it says: "{{ui:settings.commonConsumers.noConsumers}}".

More: [Common consumers](help:common-consumers).

### The "{{ui:settings.requisites.title}}" panel

{{ui:settings.requisites.text}} The fields: "{{ui:settings.requisites.officialName}}", "{{ui:settings.requisites.legalAddress}}", "{{ui:settings.requisites.bankName}}", "{{ui:settings.requisites.iban}}". The "{{ui:settings.requisites.edrpou}}" code is read-only. The {{ui:settings.requisites.save}} button. If the IBAN is wrong, the system says: "{{ui:settings.requisites.errors.ibanInvalid}}".

Requisites are printed in invoices, certificates and reports. Until the IBAN and the registration code are filled in, invoices cannot be built: "{{ui:problem.REQUISITES_MISSING}}"

### The "{{ui:deliverySettings.title}}" panel

{{ui:deliverySettings.text}} Next to each channel is a mark "{{ui:deliverySettings.configured}}" or "{{ui:deliverySettings.notConfigured}}". Until a channel is set up, nothing is sent through it. Who gets an invoice by which channel and how to read the result: see [Delivery](help:deliveries).

#### E-mail

The simplest way is to create a free mailbox for the cooperative (Gmail or Ukr.net) and use an app password: this is a separate password for programs. It is created in the mailbox settings and does not open the mailbox itself. Most services do not accept the ordinary mail password for such programs.

| Service | What to do in the mailbox | Server | Port and encryption | User and password |
|---|---|---|---|---|
| Gmail | Turn on two-step verification, then create an "app password" | smtp.gmail.com | 587, STARTTLS | the full mailbox address; a 16-character app password |
| Ukr.net | In the mailbox settings allow access from mail programs and create a password for them | smtp.ukr.net | 465, SSL | the full mailbox address; the password for programs |

The values are typical: check them in the help of your mail service, they may change.

The fields: "{{ui:deliverySettings.email.host}}", "{{ui:deliverySettings.email.port}}", "{{ui:deliverySettings.email.security}}" (STARTTLS, SSL or "{{ui:deliverySettings.email.securityNone}}"), "{{ui:deliverySettings.email.user}}", "{{ui:deliverySettings.email.password}}", "{{ui:deliverySettings.email.fromAddress}}" and "{{ui:deliverySettings.email.fromName}}" (for example "СТ Надія": this is how members see the sender).

- The {{ui:deliverySettings.email.save}} button saves the data. After saving the password is never shown again: the field says "{{ui:deliverySettings.email.passwordSaved}}". To keep the password unchanged, leave the field empty. The change log shows only that the password was changed.
- The {{ui:deliverySettings.email.sendTest}} button sends a test letter to your own address from your member card (without one the button says there is nowhere to send: "{{ui:problem.NO_OWN_EMAIL}}"). The button works with the saved data, so the system reminds you: "{{ui:deliverySettings.email.saveFirst}}" The result is "{{ui:deliverySettings.email.testAccepted}}" or a refusal by the server in plain words, for example "{{ui:deliveries.reason.SMTP_AUTH_FAILED}}".

> [!important]
> Gmail accepts only an app password from programs, not the ordinary account password. If the "{{ui:deliverySettings.email.password}}" field holds the ordinary password, the system says: "{{ui:deliverySettings.email.appPassword}}" First turn on two-step verification in the Google account (an app password cannot be created without it). Then create the password on the page myaccount.google.com/apppasswords (the screen has the link "{{ui:deliverySettings.email.appPasswordLink}}") and paste it into the "{{ui:deliverySettings.email.password}}" field. Gmail settings: server smtp.gmail.com, port 587, encryption STARTTLS, user is the full mailbox address, for example st.nadiya@gmail.com. The server's reply, in English, is under "{{ui:deliverySettings.email.details}}"; you can show it to whoever helps with the setup.

The system connects only to mail services on the internet and only on ports 25, 465 or 587. An address like localhost or an internal network address (192.168.…, 10.…) cannot be saved: "{{ui:problem.SMTP_HOST_NOT_ALLOWED}}"

#### Telegram bot

The chairman creates the bot in Telegram itself: it is free and takes a minute. Above the token field the screen has a short instruction.

1. In Telegram open @BotFather (the official bot for creating bots).
2. Send the command /newbot.
3. When asked for a name, write the name of the cooperative, for example "Надія — рахунки": members see it.
4. Choose a unique user name ending in bot, for example nadiya_rakhunky_bot.
5. BotFather replies with a message containing the token: a long string like 123456:ABC…. Copy it.
6. Paste the token into the "{{ui:deliverySettings.telegram.token}}" field and press {{ui:deliverySettings.telegram.save}}.
7. Press {{ui:deliverySettings.telegram.check}}. The system asks Telegram and shows the name of the bot or "{{ui:deliverySettings.telegram.TOKEN_INVALID}}". The button works with the saved token, so save it first.

The token is the key to the bot: do not give it to anyone. After saving the screen shows only the last 4 characters. If the token is changed, the cooperative's bot restarts in about a minute. If Telegram rejects the token (the bot was deleted or the token reissued), the bot stops until the token is replaced. When Telegram is not available, the system says: "{{ui:deliverySettings.telegram.TELEGRAM_UNAVAILABLE}}" How a member connects Telegram is described in [My account](help:account).

#### General parameters

- "{{ui:deliverySettings.options.emailRate}}". {{ui:deliverySettings.options.emailRateHint}} Free mailboxes limit the number of letters per day and block sending that is too fast. That is why the default is 1: 120 letters go out in a couple of minutes. Raise the number only for paid mail. If the cooperative is larger than the daily limit, some letters get the refusal "{{ui:deliveries.reason.SMTP_REJECTED}}": send them again the next day.
- The switch "{{ui:deliverySettings.options.notifyPayments}}" (on by default). {{ui:deliverySettings.options.notifyPaymentsHint}}
- The {{ui:deliverySettings.options.save}} button saves both the speed and the switch.

### The "{{ui:water.sourcesTitle}}" panel

{{ui:water.sourcesText}} For every source (the cooperative's water supply or a common consumer's meter) you see the connected plots with dates. The {{ui:water.connectPlots}} and {{ui:water.disconnectPlots}} buttons open a form: choose a date, find plots with the search "{{ui:water.pickPlot}}" and press {{ui:water.apply}}. The action is "all or nothing": if even one plot cannot be changed, none changes. A single plot is connected on its [card](help:plot-card).

### The "{{ui:expenseCategories.title}}" panel

{{ui:expenseCategories.text}} Two lists: "{{ui:expenseCategories.expense}}" and "{{ui:expenseCategories.income}}". Next to a category are the buttons {{ui:expenseCategories.rename}}, {{ui:expenseCategories.archive}} (or {{ui:expenseCategories.restore}} for an archived one), {{ui:expenseCategories.moveUp}} and {{ui:expenseCategories.moveDown}}, and at the bottom of the list {{ui:expenseCategories.add}}. In the form enter a name and press {{ui:expenseCategories.save}}. Categories are not deleted, only archived. Errors: "{{ui:expenseCategories.nameRequired}}" and "{{ui:problem.CATEGORY_NAME_TAKEN}}"

### The "{{ui:settings.meterTreeLink}}" link

{{ui:settings.meterTreeText}} The link opens the [meter tree](help:meter-tree).

## Worked example

The chairman sets up the cooperative "Nadiya" before the first sending of invoices.

1. In the "{{ui:settings.threshold.title}}" panel keeps 1000.00 ₴ and 90 days. A plot with a debt of 1,200.00 ₴ is now red, and a plot with a debt of 400.00 ₴ whose first debt is a week old is yellow.
2. In "{{ui:settings.requisites.title}}" enters the full name, the legal address, the bank and the IBAN. Without the IBAN invoices will not be built.
3. In "{{ui:deliverySettings.title}}" enters the data of the mailbox st.nadiya@gmail.com: server smtp.gmail.com, port 587, STARTTLS, the app password. Presses {{ui:deliverySettings.email.save}}, then {{ui:deliverySettings.email.sendTest}}. A letter arrives at the chairman's address, and the system says "{{ui:deliverySettings.email.testAccepted}}".
4. Creates the bot with @BotFather, pastes the token and presses {{ui:deliverySettings.telegram.save}} and {{ui:deliverySettings.telegram.check}}. The mark changes to "{{ui:deliverySettings.configured}}".
5. Leaves the switch in the "{{ui:settings.payments.title}}" panel on. Now, if a plot paid 1,000.00 ₴ against a debt of 750.00 ₴, the remaining 250.00 ₴ becomes a prepayment and goes to new charges by itself as soon as they are booked. If the switch were off, the treasurer would allocate the prepayment by hand.

Plot colours for members stay at "{{ui:settings.visibility.OWN_ONLY.label}}" until the general meeting decides otherwise.

## Screenshots

![Cooperative settings](shot:settings)

![The red status threshold panel](shot:settings-thresholds)

![The delivery panel: mail, Telegram bot, parameters](shot:settings-delivery)

## Related pages

- [Roles and permissions](help:roles)
- [Delivery](help:deliveries)
- [My account](help:account)
- [Enter a payment](help:payments-enter)
- [Losses](help:losses)
- [Common consumers](help:common-consumers)
- [Meter tree](help:meter-tree)
- [Charge types](help:charge-types)
- [Change log](help:audit)
