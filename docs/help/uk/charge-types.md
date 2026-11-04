---
id: charge-types
title: "Види нарахувань"
description: "Список видів нарахувань кооперативу: як додати свій вид, перейменувати, змінити порядок, вимкнути чи ввімкнути (наприклад, воду) і архівувати."
kind: screen
routes:
  - /charge-types
permissions:
  - TARIFFS_VIEW
section: tariffs-charges
order: 40
shots:
  - name: charge-types
    user: chairman
    path: /charge-types
---

## Для чого цей екран

Вид нарахування це «за що» кооператив бере гроші: членський внесок, електроенергія, земельний податок, вода, спільні споживачі. До кожного виду належать свої [тарифи](help:tariffs). Порядок видів у списку має значення: коли платіж розподіляється на борги одного дня, система йде за цим порядком.

## Хто бачить

Список бачать голова й казначей: це ті, хто має право «{{ui:permission.TARIFFS_VIEW.name}}». Додавати, змінювати, переміщувати й архівувати може лише той, хто має право «{{ui:permission.TARIFFS_MANAGE.name}}», тобто голова.

## Елементи екрана

### Угорі

| Елемент | Що робить |
|---|---|
| {{ui:chargeTypes.back}} | Повертає до списку тарифів. |
| {{ui:chargeTypes.add}} | Відкриває діалог «{{ui:chargeTypes.dialog.addTitle}}». Лише для голови. |

У кооперативі від початку є види: {{ui:chargeTypes.defaultNames.MEMBERSHIP}}, {{ui:chargeTypes.defaultNames.ELECTRICITY}}, {{ui:chargeTypes.defaultNames.LAND_TAX}}, {{ui:chargeTypes.defaultNames.WATER}} і {{ui:chargeTypes.defaultNames.COMMON_CONSUMERS}}. Можна додати свої, наприклад «Ремонт дороги». Якщо видів немає, екран пише «{{ui:chargeTypes.empty}}».

> [!important]
> Вид «{{ui:chargeTypes.defaultNames.WATER}}» може бути вимкнений, доки в кооперативі немає водомірів. Тоді його немає у списку видів у формі нового тарифу, і тариф за воду створити не вийде. Спершу ввімкніть його тут: натисніть «{{ui:chargeTypes.edit}}» біля виду «{{ui:chargeTypes.defaultNames.WATER}}», поставте позначку «{{ui:chargeTypes.dialog.active}}» і збережіть. Потім створіть [тариф](help:tariff-form): фіксована сума на ділянку, платять лише ділянки, підключені до води. Електроенергію насоса свердловини нараховують не цим видом, а видом «{{ui:chargeTypes.defaultNames.COMMON_CONSUMERS}}».

### Рядок виду

У рядку видно назву, позначку «{{ui:chargeTypes.active}}» чи «{{ui:chargeTypes.inactive}}» та опис. Для голови є ще кнопки:

| Кнопка | Що робить |
|---|---|
| {{ui:chargeTypes.moveUp}} | Піднімає вид у списку. Недоступна для першого. |
| {{ui:chargeTypes.moveDown}} | Опускає вид. Недоступна для останнього. |
| {{ui:chargeTypes.edit}} | Відкриває діалог «{{ui:chargeTypes.dialog.editTitle}}». |
| {{ui:chargeTypes.archive}} | Просить підтвердження «{{ui:chargeTypes.archiveTitle}}». |

### Діалог виду

| Поле | Що вказати |
|---|---|
| {{ui:chargeTypes.dialog.name}} | Обов’язкове. Стандартний вид можна перейменувати; тоді показується ваша назва. |
| {{ui:chargeTypes.dialog.description}} | Необов’язково. |
| {{ui:chargeTypes.dialog.active}} | Лише під час зміни. Підказка під полем: «{{ui:chargeTypes.dialog.activeHint}}» |

Кнопки: «{{ui:chargeTypes.dialog.save}}» і «{{ui:chargeTypes.dialog.cancel}}».

### Архівування

Підтвердження «{{ui:chargeTypes.archiveConfirm}}» ховає вид зі списків: нових тарифів у нього більше не буде. Минулі нарахування й старі тарифи лишаються. Якщо вид просто тимчасово не потрібен, краще вимкнути його знятою позначкою «{{ui:chargeTypes.dialog.active}}»: так вид лишається в списку й його можна ввімкнути знову.

### Повідомлення про помилки

| Що показано | Що робити |
|---|---|
| {{ui:problem.CHARGE_TYPE_NAME_TAKEN}} | Оберіть іншу назву. |
| {{ui:problem.CHARGE_TYPE_ORDER_STALE}} | Список змінив хтось інший. Він уже оновлений, повторіть переміщення. |
| {{ui:problem.CHARGE_TYPE_ARCHIVED}} | Вид в архіві, його вже не можна змінити. |

## Приклад

Кооператив вирішив збирати гроші на ремонт дороги.

1. Натисніть «{{ui:chargeTypes.add}}».
2. Назвіть вид «Ремонт дороги», опишіть «Цільовий збір на ремонт головної дороги» і збережіть.
3. Піднімайте його кнопкою «{{ui:chargeTypes.moveUp}}», доки він не стане там, де хочете: що вищий вид, то раніше його борг гаситиметься платежем одного дня.
4. Створіть для нього [тариф](help:tariff-form): разова сума 1 200,00 ₴, сплатити до 01.12.2026.

## Знімки екрана

![Список видів нарахувань](shot:charge-types)

## Пов’язані сторінки

- [Тарифи](help:tariffs)
- [Новий тариф](help:tariff-form)
- [Налаштування кооперативу](help:settings)
- [Як система розподіляє платежі](help:how-payments-allocate)
