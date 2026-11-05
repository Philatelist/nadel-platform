---
id: announcement-form
title: "New announcement"
description: "How to write an announcement to cooperative members: text up to 2000 characters, the audience (everyone or by street), channels, a preview, and the list of those who must be told personally."
kind: screen
routes:
  - /announcements/new
permissions:
  - ANNOUNCEMENTS_SEND
section: documents-delivery
order: 40
shots:
  - name: announcement-form
    user: chairman
    path: /announcements/new
    steps:
      - { fill: { label: Announcement text, value: 'The general meeting is on Sunday at 12:00 at the board office.' } }
      - { click: Preview }
---

## What the screen is for

Here the board writes an announcement and sends it to the cooperative's members through Telegram and e-mail. Before sending, the system shows how many members will receive the announcement and whom you must tell personally, because they have no channel.

## Who sees it

The screen is seen by those who have "{{ui:permission.ANNOUNCEMENTS_SEND.name}}" (by default only the chairman).

## Every element

### Fields

| Field | What to enter |
|---|---|
| {{ui:announcements.form.text}} | Plain text, up to 2000 characters. The counter "{{ui:announcements.form.counter}}" stands under the field. When there are too many characters, you see "{{ui:announcements.form.tooLong}}", and the server answers "{{ui:problem.ANNOUNCEMENT_TOO_LONG}}". |
| {{ui:announcements.form.audience}} | "{{ui:announcements.form.audienceAll}}" or "{{ui:announcements.form.audienceStreets}}". For streets mark the ones you need in the list "{{ui:announcements.form.streets}}" (streets of active plots). If none is marked, you see "{{ui:announcements.form.streetsRequired}}". If the cooperative has no streets yet, it says "{{ui:announcements.form.noStreets}}". |
| {{ui:announcements.form.channels}} | "{{ui:announcements.form.channelTelegram}}", "{{ui:announcements.form.channelEmail}}" or both. With none: "{{ui:announcements.form.channelRequired}}". |

Telegram works only if the cooperative's bot is connected in the [settings](help:settings). Otherwise the system says "{{ui:problem.TELEGRAM_NOT_CONFIGURED}}".

### The button "{{ui:announcements.form.preview}}"

It sends nothing. It shows "{{ui:announcements.form.previewTitle}}": how many members will receive the announcement (by channel: "{{ui:announcements.form.previewCounts}}") and the list "{{ui:announcements.form.paperTitle}}". These members have none of the chosen channels: you must notify them yourselves. Print this list and tell each of them. If there is no such list, you see "{{ui:announcements.form.paperNone}}".

### The button "{{ui:announcements.form.send}}"

The hint next to it is "{{ui:announcements.form.sendHint}}", that is, first press "{{ui:announcements.form.preview}}". The announcement is queued, and you return to the announcement list. The progress of sending is seen on the [Delivery](help:deliveries) screen.

> [!important]
> A sent announcement cannot be recalled or changed. Before sending, check the text, the streets and the channels in the preview.

## Worked example

The chairman writes an announcement about a meeting.

1. Text: "The general meeting is on Sunday at 12:00 at the board office."
2. "{{ui:announcements.form.audience}}": "{{ui:announcements.form.audienceAll}}".
3. "{{ui:announcements.form.channels}}": "Telegram" and "E-mail".
4. "{{ui:announcements.form.preview}}": the screen shows how many members will receive the announcement in Telegram and by e-mail, and the list of those who stay on paper.
5. The chairman prints this list to phone the people in it. Then he presses "{{ui:announcements.form.send}}".

## Screenshots

![A new announcement with a preview: who gets it and who stays on paper](shot:announcement-form)

## Related pages

- [Announcements](help:announcements)
- [Announcement card](help:announcement-card)
- [Delivery](help:deliveries)
- [Settings: delivery](help:settings)
