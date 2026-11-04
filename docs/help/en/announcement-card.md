---
id: announcement-card
title: "Announcement card"
description: "One sent announcement: the text, the audience, channels, the result and the delivery to each member with error reasons."
kind: screen
routes:
  - /announcements/:id
permissions:
  - ANNOUNCEMENTS_SEND
section: documents-delivery
order: 50
shots:
  - name: announcement-card
    user: chairman
    path: /announcements
    open: { css: 'main a[href^="/announcements/"]:not([href$="/new"])' }
---

## What the screen is for

The card shows one announcement: what was sent, to whom and how the delivery ended. You see here exactly to whom a message did not arrive.

## Who sees it

The card is seen by those who have "{{ui:permission.ANNOUNCEMENTS_SEND.name}}" (by default the chairman).

## Every element

The button "{{ui:announcements.detail.back}}" returns to the list.

### Announcement data

A table with the rows "{{ui:announcements.detail.text}}", "{{ui:announcements.columns.date}}", "{{ui:announcements.columns.author}}", "{{ui:announcements.detail.audience}}" ("{{ui:announcements.allMembers}}" or a list of streets), "{{ui:announcements.detail.channels}}" and "{{ui:announcements.detail.counts}}" (for example, "Sent: 18, errors: 1, queued: 0, on paper: 6").

### Delivery

The section "{{ui:announcements.detail.deliveries}}" lists the message to each member: plot, member, channel, status, error reason, number of attempts and time. The statuses and reasons are the same as on the [Delivery](help:deliveries) screen. You can send a message again on the "Delivery" screen with the button "{{ui:deliveries.retry}}", once you have fixed the reason. If the announcement is not found, you see "{{ui:announcements.detail.notFound}}".

## Worked example

The chairman opens the announcement about the meeting. One row has the status "{{ui:deliveries.status.FAILED}}" and the reason "{{ui:deliveries.reason.TELEGRAM_BLOCKED}}": the member blocked the bot. The chairman tells them personally, and the member still has e-mail as a fallback channel.

## Screenshots

![An announcement card with the text and the delivery to each member](shot:announcement-card)

## Related pages

- [Announcements](help:announcements)
- [New announcement](help:announcement-form)
- [Delivery](help:deliveries)
