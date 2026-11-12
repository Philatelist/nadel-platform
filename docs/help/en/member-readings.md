---
id: member-readings
title: "How to submit a meter reading"
description: "How a plot owner submits a reading of their own meter from the account, what happens next and why a reading can be rejected."
kind: topic
routes: []
permissions:
  - member
section: member
order: 30
shots:
  - name: member-readings-panel
    user: member
    path: /account
    element: { heading: My meters }
  - name: member-readings-dialog
    user: member
    path: /account
    viewport: phone
    steps:
      - { click: Submit a reading }
---

You can submit a meter reading yourself, without waiting for the electrician. You need to be signed in and to have a plot with a working meter.

![My meters in the account](shot:member-readings-panel)

## How to submit a reading

1. Open your [account](help:account) and find the {{ui:readings.mine.title}} block.
2. Next to the right meter press {{ui:readings.mine.submit}}. The button is not there for a meter that has been removed.
3. Enter the date when you read the meter. Today is there by default.
4. Enter the meter value as it is written on the meter. The value may have up to three decimals.
5. If you wish, add a photo of the meter: press {{ui:readings.photo.add}}. On a phone the camera opens, on a computer a file picker. A HEIC photo from an iPhone is converted to JPEG only where the browser can open it (Safari on iPhone or Mac); in another browser the system says "{{ui:readings.photo.typeNotAllowed}}", so add a JPEG or PNG photo. You see a thumbnail above the button. The {{ui:readings.photo.replace}} button changes the photo and {{ui:readings.photo.remove}} removes it.
6. Press {{ui:readings.mine.submit}}. The system answers: "{{ui:readings.mine.submitted}}".

Nothing else is needed.

## The photo of a reading

A photo helps the board quickly make sure the number is right. Only photos (JPEG, PNG) up to 5 MB are allowed; otherwise the system says "{{ui:readings.photo.typeNotAllowed}}" or "{{ui:readings.photo.tooLarge}}". While the photo is being processed, the button says "{{ui:readings.photo.processing}}".

If the board turned on the rule "{{ui:settings.readings.photoRequired}}" in the settings, the photo is required: without it the reading is not sent and the system says: "{{ui:problem.READING_PHOTO_REQUIRED}}" Add the photo and press {{ui:readings.mine.submit}} again. A submitted reading with a photo shows as a thumbnail in the meter history: press it to open the photo full screen. A photo cannot be deleted after submission, it stays with the reading.

Note: while the photo rule is on, the Telegram bot does not accept a reading because it takes no photo. The bot asks you to submit the reading with a photo in the app.

## What happens next

The reading is not counted at once. The window says: "{{ui:readings.mine.submitText}}" Until the board answers, the reading has the status "{{ui:readings.history.status.PENDING}}" and is not in any calculation.

| Status in the list | What it means |
|---|---|
| {{ui:readings.history.status.PENDING}} | The board has not checked the reading yet |
| {{ui:readings.history.status.APPROVED}} | The reading is accepted. The system charges electricity by it |
| {{ui:readings.history.status.REJECTED}} | The board rejected the reading and gave a reason, shown under the entry |
| {{ui:readings.history.status.SUPERSEDED}} | This reading is replaced by a newer or corrected one. It stays in the history |

Under an entry you also see how much was used between this reading and the previous one.

## Why a reading can be rejected

- The value is lower than the previous one. A meter reading cannot go down.
- The date is in the future or outside the time the meter worked.
- The value is far above what is usual for your plot. The system lets it through with a warning, and the board checks it.

If you made a mistake, just submit the correct reading again: the board rejects the wrong one and accepts the right one.

## Worked example

Meter EL-9 on plot 9 had the last reading 3,705.600 from 03.10. You read 3,800.000 and submit it on 31.10. A day later the board accepts it. The system counts the consumption: 3,800.000 − 3,705.600 = 94.400 kWh, and multiplies it by the tariff rate. How exactly: [Readings and consumption](help:how-readings-consumption) and [Tariffs and the expected amount](help:how-tariffs-expected).

This is the window on a phone with the photo step:

![The reading window with the photo button on a phone](shot:member-readings-dialog)

You can also submit a reading in the Telegram bot: [How to connect Telegram](help:member-telegram).

## Related pages

- [My account](help:account)
- [Readings and consumption](help:how-readings-consumption)
- [How to read my invoice](help:member-bill)
