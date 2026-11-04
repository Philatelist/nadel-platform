---
id: certificates
title: "Реєстр довідок"
description: "Реєстр виданих довідок про стан розрахунків по ділянці: номер, дата, борг, PDF і анулювання довідки з причиною."
kind: screen
routes:
  - /documents/certificates
permissions:
  - CHARGES_VIEW
section: documents-delivery
order: 10
shots:
  - name: certificates-register
    user: chairman
    path: /documents/certificates
---

## Для чого цей екран

Довідка про стан розрахунків — це документ, у якому написано, скільки ділянка винна кооперативу на певну дату або що заборгованості немає. Її просять, наприклад, під час продажу ділянки. Цей екран — реєстр усіх виданих довідок. Тут їх знаходять, відкривають PDF і анулюють помилкові.

Саму довідку видають на картці ділянки, на вкладці «Довідка», див. [Картка ділянки](help:plot-card).

## Хто бачить

Реєстр бачать ті, хто має «{{ui:permission.CHARGES_VIEW.name}}». Видавати довідки й анулювати їх можуть лише ті, хто має «{{ui:permission.CERTIFICATES_ISSUE.name}}»: за замовчуванням голова. Кнопка «{{ui:certificates.annul}}» є лише в них. Член кооперативу довідок не бачить.

## Елементи екрана

### Фільтр «{{ui:certificates.filters.status}}»

Показує «{{ui:certificates.filters.all}}», «{{ui:certificates.filters.ISSUED}}» або «{{ui:certificates.filters.ANNULLED}}». Коли за вибором довідок немає, написано «{{ui:certificates.nothingFound}}», а якщо їх немає взагалі, то «{{ui:certificates.empty}}».

### Таблиця

| Колонка | Що в ній |
|---|---|
| {{ui:certificates.columns.number}} | Номер на кшталт «Д-2026-1». Номери йдуть підряд у межах кооперативу й року, без пропусків і повторів. |
| {{ui:certificates.columns.plot}} | Номер ділянки; посилання веде на її картку. |
| {{ui:certificates.columns.asOf}} | Дата, станом на яку складено довідку. |
| {{ui:certificates.columns.debt}} | Загальний борг на цю дату. |
| {{ui:certificates.columns.issuedBy}} і {{ui:certificates.columns.issuedAt}} | Хто й коли видав. |
| {{ui:certificates.columns.status}} | «{{ui:certificates.status.ISSUED}}» або «{{ui:certificates.status.ANNULLED}}». |
| {{ui:certificates.columns.actions}} | «{{ui:certificates.download}}» (PDF) та «{{ui:certificates.annul}}». |

### Що в довідці

Довідка містить реквізити кооперативу, ділянку з площею та поточними власниками, борг за видами й загалом та аванс на дату. Ці цифри ті самі, що й у звіті «{{ui:reports.plot-balances.title}}». Якщо загальний борг 0,00, написано «Заборгованість відсутня», інакше — «Заборгованість становить …». Є ім’я й посада того, хто видав, дата видачі та місце для підпису й печатки. Довідку можна видати й для ділянки без власника, але не для спільної землі й не для ділянки в архіві. Документ завжди українською, бо це офіційний документ.

### Анулювання

Кнопка «{{ui:certificates.annul}}» відкриває вікно «{{ui:certificates.annulTitle}}». Вікно попереджає: «{{ui:certificates.annulText}}» Вкажіть «{{ui:certificates.annulReasonLabel}}» (обов’язково) і натисніть «{{ui:certificates.annulConfirm}}».

Анульована довідка лишається в реєстрі з причиною й іменем того, хто анулював («{{ui:certificates.annulledBy}}»). Її PDF має напис «АНУЛЬОВАНО». Вдруге анулювати не можна («{{ui:problem.CERTIFICATE_ALREADY_ANNULLED}}»), номер не використовується повторно.

> [!important]
> Видана довідка не змінюється. Помилку виправляють новою довідкою, а стару анулюють із причиною. Скасувати анулювання не можна.

## Приклад

Власник ділянки 9 просить довідку. Голова відкриває картку ділянки 9, вкладку «Довідка», обирає дату й натискає «{{ui:certificates.issue}}». Система показує «Довідку видано: Д-2026-1» й завантажує PDF. Через день з’ясувалося, що дату вказано неправильно.

1. Голова відкриває «Реєстр довідок» й знаходить довідку Д-2026-1.
2. Натискає «{{ui:certificates.annul}}», пише причину «Невірна дата» й підтверджує.
3. Довідка має стан «{{ui:certificates.status.ANNULLED}}». Голова видає нову довідку з правильною датою (її номер Д-2026-2).

## Знімки екрана

![Реєстр довідок із фільтром і кнопками PDF та «Анулювати»](shot:certificates-register)

## Пов’язані сторінки

- [Картка ділянки](help:plot-card)
- [Звіти](help:reports)
- [Акт звірки і довідка: як система рахує](help:how-reports)
