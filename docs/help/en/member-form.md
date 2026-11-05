---
id: member-form
title: "New member and editing"
description: "How to add a cooperative member or correct their data: name, phones, e-mail, address, invoice delivery channel and notes."
kind: screen
routes:
  - /members/new
permissions:
  - MEMBERS_EDIT
section: plots-members
order: 50
shots:
  - name: member-form
    user: chairman
    path: /members/new
---

## What the screen is for

Here a new cooperative member is recorded. The same form opens on the [member card](help:member-card) with the "{{ui:memberForm.edit}}" button when the data must be corrected.

## Who sees it

The form is seen by whoever has the permission "{{ui:permission.MEMBERS_EDIT.name}}": the chairman. The "{{ui:member.fields.notes}}" field appears in it only for those who also have "{{ui:permission.MEMBERS_VIEW.name}}". The title: "{{ui:members.addTitle}}".

## Every element

Above the form is the link "{{ui:members.back}}". The fields come in this order.

| Field | What to enter |
|---|---|
| {{ui:member.fields.lastName}} | Required, up to 100 characters. |
| {{ui:member.fields.firstName}} | Required, up to 100 characters. |
| {{ui:member.fields.middleName}} | Optional. |
| {{ui:member.fields.email}} | Optional. The system also finds the person by it when they sign in, so the address must match the one they sign in with. One address cannot belong to two active members. |
| {{ui:member.fields.phones}} | Up to ten phones. The {{ui:memberForm.addPhone}} button adds another row, and the delete button next to a row removes it. A phone in the format 0XXXXXXXXX is turned by the system into +380XXXXXXXXX. |
| {{ui:member.fields.postalAddress}} | Optional. The address for paper invoices. |
| {{ui:member.fields.deliveryChannel}} | How invoices are delivered: "{{ui:channel.EMAIL}}", "{{ui:channel.TELEGRAM}}", "{{ui:channel.VIBER}}" or "{{ui:channel.PAPER}}". The chosen channel needs a contact. |
| {{ui:member.fields.telegramUsername}} | Required if Telegram is chosen. |
| {{ui:member.fields.viberPhone}} | Required if Viber is chosen. |
| {{ui:member.fields.notes}} | Up to 2000 characters. Service information: how best to get in touch, agreements, contacts of relatives. Seen only by those who have "{{ui:permission.MEMBERS_VIEW.name}}". |

The {{ui:memberForm.save}} button saves the member and opens their card. The {{ui:memberForm.cancel}} button goes back.

> [!important]
> The system has no separate fields for tax numbers, passport data or dates of birth: the cooperative does not need them for its accounts. Phones, e-mail, postal address, Telegram, Viber and notes are stored encrypted, so a copy of the database does not reveal them. The system decrypts them for those who may see them. Names stay readable.

### Error messages

| What is shown | What to do |
|---|---|
| {{ui:validation.REQUIRED_lastName}} | Enter the surname. |
| {{ui:validation.REQUIRED_firstName}} | Enter the first name. |
| {{ui:validation.PHONE_INVALID}} | Fix the number in the indicated row. |
| {{ui:validation.CHANNEL_CONTACT_MISSING_email}} | Enter the e-mail or choose another channel. |
| {{ui:validation.CHANNEL_CONTACT_MISSING_telegramUsername}} | Enter the nickname or choose another channel. |
| {{ui:validation.CHANNEL_CONTACT_MISSING_viberPhone}} | Enter the Viber phone or choose another channel. |
| {{ui:problem.EMAIL_TAKEN}} | Another member already has this e-mail. Check whether the person is already recorded. |

About Viber the system warns: "{{ui:notifications.viberUnsupported}}".

## Worked example

A new owner, Andriienko Maria Petrivna, has joined the cooperative.

1. In the member list press {{ui:members.add}}.
2. Enter the surname Andriienko, the first name Maria, the patronymic Petrivna.
3. Enter the phone 0671234567 and the e-mail. After saving the phone becomes +380671234567.
4. Choose the delivery channel "{{ui:channel.EMAIL}}". The system will not save the form if the e-mail is not entered.
5. Press {{ui:memberForm.save}}. Maria's card opens. She has no plots yet: the owner is assigned on the [plot card](help:plot-card).

## Screenshots

![The new member form](shot:member-form)

## Related pages

- [Members](help:members)
- [Member card](help:member-card)
- [Plot card: changing the owner](help:plot-card)
- [Roles and permissions](help:roles)
