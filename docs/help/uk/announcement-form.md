---
id: announcement-form
title: "Нове оголошення"
description: "Як написати оголошення членам кооперативу: текст до 2000 символів, кому (усім або за вулицями), канали, попередній перегляд і список тих, кому треба повідомити особисто."
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
      - { fill: { label: Текст оголошення, value: 'Збори членів товариства відбудуться в неділю о 12:00 біля правління.' } }
      - { click: Попередній перегляд }
---

## Для чого цей екран

Тут правління пише оголошення й надсилає його членам кооперативу через Telegram і пошту. Перш ніж надіслати, система показує, скільки членів отримають оголошення і кого треба сповістити особисто, бо в них немає жодного каналу.

## Хто бачить

Екран бачать ті, хто має «{{ui:permission.ANNOUNCEMENTS_SEND.name}}» (за замовчуванням лише голова).

## Елементи екрана

### Поля

| Поле | Що вказати |
|---|---|
| {{ui:announcements.form.text}} | Звичайний текст, до 2000 символів. Лічильник «{{ui:announcements.form.counter}}» стоїть під полем. Коли символів забагато, видно «{{ui:announcements.form.tooLong}}», а сервер відповість «{{ui:problem.ANNOUNCEMENT_TOO_LONG}}». |
| {{ui:announcements.form.audience}} | «{{ui:announcements.form.audienceAll}}» або «{{ui:announcements.form.audienceStreets}}». Для вулиць позначте потрібні в списку «{{ui:announcements.form.streets}}» (вулиці активних ділянок). Якщо не позначено жодної, видно «{{ui:announcements.form.streetsRequired}}». Якщо вулиць у кооперативі ще немає, написано «{{ui:announcements.form.noStreets}}». |
| {{ui:announcements.form.channels}} | «{{ui:announcements.form.channelTelegram}}», «{{ui:announcements.form.channelEmail}}» або обидва. Без жодного — «{{ui:announcements.form.channelRequired}}». |

Telegram працює, лише якщо в [налаштуваннях](help:settings) підключено бота кооперативу. Інакше система скаже «{{ui:problem.TELEGRAM_NOT_CONFIGURED}}».

### Кнопка «{{ui:announcements.form.preview}}»

Нічого не надсилає. Показує «{{ui:announcements.form.previewTitle}}»: скільки членів отримають оголошення (за каналами: «{{ui:announcements.form.previewCounts}}») і список «{{ui:announcements.form.paperTitle}}». Ці члени не мають жодного з обраних каналів: їх треба сповістити самим. Надрукуйте цей список і повідомте кожного. Якщо такого списку немає, видно «{{ui:announcements.form.paperNone}}».

### Кнопка «{{ui:announcements.form.send}}»

Підказка поряд — «{{ui:announcements.form.sendHint}}», тобто спершу натисніть «{{ui:announcements.form.preview}}». Оголошення стає в чергу, ви повертаєтеся до списку оголошень. Хід розсилки видно на екрані [Розсилка](help:deliveries).

> [!important]
> Надіслане оголошення не можна відкликати чи змінити. Перед надсиланням перевірте текст, вулиці й канали у попередньому перегляді.

## Приклад

Голова пише оголошення про збори.

1. Текст: «Збори членів товариства відбудуться в неділю о 12:00 біля правління.»
2. «{{ui:announcements.form.audience}}»: «{{ui:announcements.form.audienceAll}}».
3. «{{ui:announcements.form.channels}}»: «Telegram» і «Електронна пошта».
4. «{{ui:announcements.form.preview}}»: екран показує, скільки членів отримає оголошення в Telegram і поштою, і список тих, хто лишається на папері.
5. Голова друкує цей список, щоб обдзвонити тих, хто в ньому. Потім натискає «{{ui:announcements.form.send}}».

## Знімки екрана

![Нове оголошення з попереднім переглядом: кому піде й хто лишається на папері](shot:announcement-form)

## Пов’язані сторінки

- [Оголошення](help:announcements)
- [Картка оголошення](help:announcement-card)
- [Розсилка](help:deliveries)
- [Налаштування: розсилка](help:settings)
