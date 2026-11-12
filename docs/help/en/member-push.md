---
id: member-push
title: "Notifications on your phone"
description: "How to turn notifications on and off on this device, what they tell you, why they have no amounts and what to do when they are blocked."
kind: topic
routes: []
permissions:
  - member
section: member
order: 70
shots:
  - name: member-push-switch
    user: member
    path: /account/profile
    viewport: phone
    element: { heading: Notifications }
---

Notifications are short messages on the screen of a phone or computer, even when Nadel is not open. They work separately from Telegram and e-mail and are set for each device on its own.

## How to turn them on

1. Open [My details](help:profile), the {{ui:notifications.title}} block.
2. Turn on the switch "{{ui:notifications.push.title}}". Below it is the hint: "{{ui:notifications.push.hint}}"
3. The browser asks whether to allow notifications. Choose "Allow". The browser asks this itself, so there is no screenshot of it in this help.

The switch is shown only when notifications are set up in the system. If it is not there, ask the board.

![The Notifications block on a phone](shot:member-push-switch)

In this screenshot an explanation, "{{ui:notifications.push.unsupported}}", stands in place of the switch: the browser it was taken with does not support notifications. On your phone there is a switch in this place.

## How to turn them off

Turn the same switch off. The device stops receiving notifications at once. On another device, for example a computer, they stay on until you turn them off there too.

When you sign out of the system, notifications on this device turn off by themselves. After the next sign-in turn them on again. This way a borrowed phone or a shared computer does not receive your notifications.

## What they tell you

| Event | Notification text |
|---|---|
| An invoice is issued | "New invoice for October" (the month of the invoice stands in place of "October") |
| A payment is recorded | "Payment recorded" |
| A meter reading is accepted | "Meter reading accepted" |
| A meter reading is rejected | "Meter reading rejected" |
| The board publishes an announcement | "New announcement from the board" |

The board and the electrician who review readings get one more: "New readings to review". It comes at most once an hour.

Tap a notification and the matching Nadel screen opens.

## Why there are no amounts or names

A notification goes through the notification service of your browser (Google, Mozilla or Apple), and anyone can see it on a locked screen. So the system sends only a short general phrase. Amounts, names and plot numbers never get into a notification. See the details in your account after you sign in.

## If notifications are blocked

If you once pressed "Block", the browser does not ask again, and the switch is off and labelled: "{{ui:notifications.push.blocked}}"

1. Open the site settings in the browser (the icon next to the address, or the app settings in the phone).
2. Change the permission for notifications to "Allow".
3. Go back to [My details](help:profile) and turn the switch on again.

## iPhone

On an iPhone notifications work only in the app added to the home screen, and only on iOS 16.4 or newer. While Nadel is open just in Safari, the block says: "{{ui:notifications.push.iosNeedsInstall}}" Add the app first: [Install on your phone](help:member-install).

## Other messages

- "{{ui:notifications.push.unsupported}}" means the browser cannot show notifications. Try Chrome, Firefox, Edge or Safari.
- "{{ui:notifications.push.error}}" means a temporary error. Try again.

## Related pages

- [Install on your phone](help:member-install)
- [My account](help:account)
- [How to connect Telegram](help:member-telegram)
