---
id: expense-card
title: "Картка витрати"
description: "Одна витрата кооперативу: дані, прикладені чеки (перегляд і завантаження), місяць обліку, хто вніс, і сторнування з внесенням заново."
kind: screen
routes:
  - /cashbook/expenses/:id
permissions:
  - CASHBOOK_VIEW
section: payments-cash
order: 70
shots:
  - name: expense-card
    user: chairman
    path: /cashbook/expenses
    open: { css: 'main a[href^="/cashbook/expenses/"]:not([href$="/new"])', withFiles: true, rowText: 'Будмаркет' }
---

## Для чого цей екран

Картка показує одну витрату з усіма даними і файлами чека. Тут голова сторнує витрату, якщо її записано з помилкою.

## Хто бачить

Картку бачать ті, хто має «{{ui:permission.CASHBOOK_VIEW.name}}»: голова й казначей. Кнопку «{{ui:expenses.card.redo}}» бачить лише той, хто має «{{ui:permission.CASHBOOK_APPROVE.name}}» (голова). Казначей, який помилився, просить голову.

## Елементи екрана

### Дані витрати

Таблиця з полями: {{ui:expenses.card.account}}, {{ui:expenses.card.date}}, {{ui:expenses.card.amount}}, {{ui:expenses.card.category}}, {{ui:expenses.card.counterparty}}, {{ui:expenses.card.description}}, {{ui:expenses.card.document}} (номер і дата), {{ui:expenses.card.bookingMonth}} і {{ui:expenses.card.createdBy}}. Місяць обліку може відрізнятися від дати, якщо дата припала на закритий місяць: тоді витрата враховується в найближчому відкритому.

Кнопка «{{ui:expenses.back}}» повертає до каси.

### Файли

Розділ «{{ui:expenses.attachments.list}}». Якщо файлів немає, написано «{{ui:expenses.attachments.none}}».

- Фото показано мініатюрами. Клік відкриває фото на повний розмір просто на сторінці. Клавіша Esc закриває, стрілки перемикають між файлами; кнопки «{{ui:expenses.attachments.viewer.close}}», «{{ui:expenses.attachments.viewer.previous}}» і «{{ui:expenses.attachments.viewer.next}}» роблять те саме мишкою.
- PDF показано значком файла. Він відкривається в новій вкладці («{{ui:expenses.attachments.openPdf}}»).
- Кнопка «{{ui:expenses.attachments.download}}» завантажує файл. Завантажувати файли можуть ті, хто має «{{ui:permission.CASHBOOK_VIEW.name}}».
- Якщо файл не відкрився, видно «{{ui:expenses.attachments.viewer.failed}}».

### Сторнувати і внести заново

Кнопка «{{ui:expenses.card.redo}}» відкриває вікно «{{ui:expenses.reverse.title}}». Вкажіть причину (до 500 символів; інакше «{{ui:expenses.reverse.required}}») і натисніть «{{ui:expenses.reverse.submit}}». «{{ui:expenses.reverse.cancel}}» закриває вікно.

Що буде далі:

- Витрата отримує позначку «{{ui:expenses.card.reversed}}», з причиною й датою. Гроші повертаються в залишок каси чи банку.
- Одразу відкривається форма нової витрати з тими самими значеннями й файлами. Виправте помилку й запишіть її.
- Сторновану витрату вдруге не сторнують.
- Дія записується в журнал змін.

> [!important]
> Сторнована витрата не видаляється й лишається в списку. Якщо ви закрили форму нової витрати, не записавши її, повернення грошей у залишок уже відбулося. Внесіть витрату знову через «{{ui:expenses.add}}».

## Приклад

Казначей вніс 860,00 ₴ за матеріали не на ту статтю: потрібні були «Інше».

1. Голова відкриває картку цієї витрати.
2. Натискає «{{ui:expenses.card.redo}}», пише причину «Не та стаття» й підтверджує.
3. Відкривається форма з тими самими 860,00 ₴, документом ЧК-1187 і фото чека. Голова змінює статтю й натискає «{{ui:expenses.form.submit}}».
4. Старий запис має позначку «{{ui:expenses.card.reversed}}», новий запис — правильну статтю, а залишок каси лишився таким, як був.

## Знімки екрана

![Картка витрати з фото чека](shot:expense-card)

## Пов’язані сторінки

- [Витрати](help:expenses)
- [Внести витрату](help:expense-form)
- [Каса і банк](help:cashbook)
- [Журнал змін](help:audit)
