---
id: announcements
title: "Announcements"
description: "The list of board announcements sent to cooperative members in Telegram and by e-mail: date, author, audience, channels and the delivery result."
kind: screen
routes:
  - /announcements
permissions:
  - ANNOUNCEMENTS_SEND
section: documents-delivery
order: 30
shots:
  - name: announcements
    user: chairman
    path: /announcements
---

## What the screen is for

All the announcements the board has sent to the cooperative's members are collected here: about a meeting, a power cut, payment deadlines. From here you can write a new announcement or open an old one to see to whom it arrived.

## Who sees it

The menu item "Announcements" and the screen are seen by those who have "{{ui:permission.ANNOUNCEMENTS_SEND.name}}". By default that is the chairman only. The right can be given to a custom role on the [Roles](help:roles) page.

## Every element

### The button "{{ui:announcements.newAnnouncement}}"

Opens the [new announcement form](help:announcement-form).

### The table

| Column | What is in it |
|---|---|
| {{ui:announcements.columns.date}} | When the announcement was sent. It is a link to the [announcement card](help:announcement-card). |
| {{ui:announcements.columns.author}} | Who wrote it. |
| {{ui:announcements.columns.audience}} | "{{ui:announcements.allMembers}}" or a list of streets. |
| {{ui:announcements.columns.channels}} | Telegram, e-mail or both. |
| {{ui:announcements.columns.counts}} | "{{ui:announcements.counts}}". |

If there were no announcements yet, you see "{{ui:announcements.empty}}".

The result in the column reads like this: "Sent": the mail server or Telegram accepted the message; "errors": it failed; "queued": still waiting; "on paper": members who have none of the chosen channels and must be told personally.

## Worked example

The chairman sent an announcement about a meeting to all members. In the list it has a row: "{{ui:announcements.allMembers}}", channels "Telegram, E-mail", result "Sent: 18, errors: 1, queued: 0, on paper: 6". This means: 18 members received the message, one failed, six have no channels and need a personal notice. The chairman opens the announcement to see who exactly.

## Screenshots

![The announcement list with the delivery result](shot:announcements)

## Related pages

- [New announcement](help:announcement-form)
- [Announcement card](help:announcement-card)
- [Delivery](help:deliveries)
