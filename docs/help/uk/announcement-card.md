---
id: announcement-card
title: "Картка оголошення"
description: "Одне надіслане оголошення: текст, кому, канали, результат і доставка кожному члену з причинами помилок."
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

## Для чого цей екран

Картка показує одне оголошення: що було надіслано, кому і чим закінчилася доставка. Тут видно, кому саме повідомлення не дійшло.

## Хто бачить

Картку бачать ті, хто має «{{ui:permission.ANNOUNCEMENTS_SEND.name}}» (за замовчуванням голова).

## Елементи екрана

Кнопка «{{ui:announcements.detail.back}}» повертає до списку.

### Дані оголошення

Таблиця з рядками «{{ui:announcements.detail.text}}», «{{ui:announcements.columns.date}}», «{{ui:announcements.columns.author}}», «{{ui:announcements.detail.audience}}» («{{ui:announcements.allMembers}}» або перелік вулиць), «{{ui:announcements.detail.channels}}» і «{{ui:announcements.detail.counts}}» (наприклад, «Надіслано: 18, помилок: 1, у черзі: 0, на папері: 6»).

### Доставка

Розділ «{{ui:announcements.detail.deliveries}}» перелічує повідомлення кожному члену: ділянка, член, канал, стан, причина помилки, кількість спроб і час. Стани й причини ті самі, що на екрані [Розсилка](help:deliveries). Повторно надіслати повідомлення можна на екрані «Розсилка» кнопкою «{{ui:deliveries.retry}}», коли ви виправите причину. Якщо оголошення не знайдено, видно «{{ui:announcements.detail.notFound}}».

## Приклад

Голова відкриває оголошення про збори. Один рядок має стан «{{ui:deliveries.status.FAILED}}» і причину «{{ui:deliveries.reason.TELEGRAM_BLOCKED}}»: член заблокував бота. Голова повідомляє його особисто, а в нього лишається підключення до пошти як запасний канал.

## Знімки екрана

![Картка оголошення з текстом і доставкою кожному](shot:announcement-card)

## Пов’язані сторінки

- [Оголошення](help:announcements)
- [Нове оголошення](help:announcement-form)
- [Розсилка](help:deliveries)
