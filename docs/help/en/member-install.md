---
id: member-install
title: "Install on your phone"
description: "How to add Nadel to the home screen of a phone or computer as an app: Android, desktop, iPhone, updates and what happens without internet."
kind: topic
routes: []
permissions:
  - member
section: member
order: 60
shots:
  - name: member-install-ios
    user: member
    path: /account
    viewport: phone-ios
    element: { heading: Install on iPhone }
---

Nadel can be installed like an ordinary app: it gets its own icon on the home screen, opens full screen and can show notifications. You do not download anything from an app store: it is the same page you open in the browser.

A secure connection is needed: the address of the system must start with `https://`. The board gives you exactly such an address. If the browser does not offer installation, check this first.

## Android, Chrome

1. Open [My account](help:account). When the browser allows installation, there is a card "{{ui:pwa.installTitle}}" at the top with the button {{ui:pwa.install}}.
2. Press {{ui:pwa.install}} and confirm in the phone's window. The phone draws that window itself, so there is no screenshot of it here.
3. The Nadel icon appears on the home screen. Open the system from it from now on.

If the card is not there, open the browser menu (three dots) and choose the item that installs the app, for example "Install app" or "Add to Home screen".

## Desktop, Chrome or Edge

1. Open [My account](help:account) and press {{ui:pwa.install}} in the card "{{ui:pwa.installTitle}}".
2. Or press the install icon at the end of the address bar and confirm.
3. Nadel opens in its own window, and its icon is among your programs.

## iPhone and iPad, Safari

On an iPhone the system can be added to the home screen only from the Safari browser. Chrome and other browsers on an iPhone do not have this item.

1. Open the system in Safari and go to [My account](help:account). There is a card "{{ui:pwa.iosTitle}}" saying: "{{ui:pwa.iosSteps}}".
2. Press the Share button at the bottom of the screen (a square with an arrow pointing up).
3. Scroll the list and choose "Add to Home Screen".
4. Press "Add". The icon appears on the home screen.

![The card with the iPhone steps in My account](shot:member-install-ios)

> [!important]
> Notifications on an iPhone work only in the app added to the home screen, and only on iOS 16.4 or newer. A link opened in Safari does not show notifications. More: [Notifications on your phone](help:member-push).

## I do not want this card

The button "{{ui:pwa.dismiss}}" hides the card on this device. It does not come back. You can still install the app later from the browser menu. When Nadel is already installed, the card is not shown.

## Updates

When a new version is out, a bar "{{ui:pwa.updateAvailable}}" with the button "{{ui:pwa.update}}" appears at the top of the screen. Press it, and the page reloads in the new version. The update never happens by itself, so it does not interrupt you.

If a form with unsaved changes is open, the button does not act and the bar says: "{{ui:pwa.updateBlockedDirty}}" Save or close the form and press the button again. During sign-in the bar says: "{{ui:pwa.updateBlockedSignIn}}"

## Without internet

When there is no connection, a bar appears at the top: "{{ui:pwa.offline}}" The app itself opens, but it does not show your data without internet: invoices, amounts and readings are not stored on the device, so that nobody else can see them. When the connection is back, the bar disappears and the data appears by itself. Nothing you enter without a connection is sent: repeat the action when the internet is back. The help opens without a connection too; a screenshot you have not looked at before shows only its caption until the connection is back.

## Related pages

- [Notifications on your phone](help:member-push)
- [My account](help:account)
- [Getting started for members](help:member-getting-started)
