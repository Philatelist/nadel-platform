---
id: member-form
title: "Новий член і редагування"
description: "Як додати члена кооперативу або виправити його дані: ім’я, телефони, пошту, адресу, спосіб доставки рахунків і примітки."
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

## Для чого цей екран

Тут записують нового члена кооперативу. Та сама форма відкривається на [картці члена](help:member-card) кнопкою «{{ui:memberForm.edit}}», коли дані треба виправити.

## Хто бачить

Форму бачить той, хто має дозвіл «{{ui:permission.MEMBERS_EDIT.name}}»: голова. Поле «{{ui:member.fields.notes}}» у ній з’являється лише в тих, хто має ще й «{{ui:permission.MEMBERS_VIEW.name}}». Заголовок: «{{ui:members.addTitle}}».

## Елементи екрана

Над формою посилання «{{ui:members.back}}». Поля йдуть у такому порядку.

| Поле | Що вказати |
|---|---|
| {{ui:member.fields.lastName}} | Обов’язкове, до 100 символів. |
| {{ui:member.fields.firstName}} | Обов’язкове, до 100 символів. |
| {{ui:member.fields.middleName}} | Необов’язкове. |
| {{ui:member.fields.email}} | Необов’язкове. Також за нею система знаходить людину під час входу, тому адреса має збігатися з тією, під якою вона входить. Одна адреса не може належати двом активним членам. |
| {{ui:member.fields.phones}} | До десяти телефонів. Кнопка {{ui:memberForm.addPhone}} додає ще один рядок, а кнопка видалення біля рядка прибирає його. Телефон у форматі 0XXXXXXXXX система сама перетворить на +380XXXXXXXXX. |
| {{ui:member.fields.postalAddress}} | Необов’язкове. Адреса для паперових рахунків. |
| {{ui:member.fields.deliveryChannel}} | Спосіб доставки рахунків: «{{ui:channel.EMAIL}}», «{{ui:channel.TELEGRAM}}», «{{ui:channel.VIBER}}» або «{{ui:channel.PAPER}}». Для обраного способу потрібен контакт. |
| {{ui:member.fields.telegramUsername}} | Потрібен, якщо обрано Telegram. |
| {{ui:member.fields.viberPhone}} | Потрібен, якщо обрано Viber. |
| {{ui:member.fields.notes}} | До 2000 символів. Службова інформація: як краще зв’язатися, домовленості, контакти родичів. Бачать лише ті, хто має «{{ui:permission.MEMBERS_VIEW.name}}». |

Кнопка {{ui:memberForm.save}} записує члена й відкриває його картку. Кнопка {{ui:memberForm.cancel}} повертає назад.

> [!important]
> Окремих полів для РНОКПП, паспортних даних і дат народження в системі немає: кооперативу для обліку вони не потрібні. Телефони, пошта, поштова адреса, Telegram, Viber і примітки зберігаються зашифрованими, тож копія бази їх не розкриє. Система розшифровує їх для тих, кому дозволено їх бачити. Імена лишаються читабельними.

### Повідомлення про помилки

| Що показано | Що робити |
|---|---|
| {{ui:validation.REQUIRED_lastName}} | Впишіть прізвище. |
| {{ui:validation.REQUIRED_firstName}} | Впишіть ім’я. |
| {{ui:validation.PHONE_INVALID}} | Виправте номер у вказаному рядку. |
| {{ui:validation.CHANNEL_CONTACT_MISSING_email}} | Впишіть пошту або оберіть інший спосіб доставки. |
| {{ui:validation.CHANNEL_CONTACT_MISSING_telegramUsername}} | Впишіть нікнейм або оберіть інший спосіб. |
| {{ui:validation.CHANNEL_CONTACT_MISSING_viberPhone}} | Впишіть телефон у Viber або оберіть інший спосіб. |
| {{ui:problem.EMAIL_TAKEN}} | Така пошта вже є в іншого члена. Перевірте, чи людину вже не записано. |

Про Viber система попереджає: «{{ui:notifications.viberUnsupported}}».

## Приклад

До кооперативу вступила нова власниця Андрієнко Марія Петрівна.

1. У списку членів натисніть {{ui:members.add}}.
2. Впишіть прізвище Андрієнко, ім’я Марія, по батькові Петрівна.
3. Вкажіть телефон 0671234567 і пошту. Після збереження телефон стане +380671234567.
4. Оберіть спосіб доставки «{{ui:channel.EMAIL}}». Система не збереже форму, якщо пошту не вписано.
5. Натисніть {{ui:memberForm.save}}. Відкриється картка Марії. Ділянок у неї поки немає: власника призначають на [картці ділянки](help:plot-card).

## Знімки екрана

![Форма нового члена кооперативу](shot:member-form)

## Пов’язані сторінки

- [Члени кооперативу](help:members)
- [Картка члена](help:member-card)
- [Картка ділянки: зміна власника](help:plot-card)
- [Ролі](help:roles)
