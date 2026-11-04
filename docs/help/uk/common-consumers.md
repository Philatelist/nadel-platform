---
id: common-consumers
title: "Спільні споживачі"
description: "Як розподілити споживання насоса свердловини, вуличного освітлення й інших спільних лічильників між ділянками: способи поділу, перегляд, проведення й сторно."
kind: screen
routes:
  - /charges/common
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 90
shots:
  - name: charges-common
    user: chairman
    path: /charges/common
---

## Для чого цей екран

Насоси свердловин, вуличне освітлення й контора споживають електрику для всіх. Їхнє споживання нараховується окремим видом «{{ui:chargeTypes.defaultNames.COMMON_CONSUMERS}}» зі своїм тарифом за кВт·год і окремим рядком у рахунку. Тут голова переглядає розподіл за період і проводить його.

## Хто бачить

Екран бачать голова й казначей (право «{{ui:permission.CHARGES_VIEW.name}}»). Переглянути й провести може той, хто має «{{ui:permission.CHARGES_RUN.name}}», сторнувати запуск той, хто має «{{ui:permission.CHARGES_REVERSE.name}}». Без права проводити екран пише: «{{ui:billing.rangeRun.noAction}}»

## Елементи екрана

Заголовок «{{ui:billing.common.heading}}» і пояснення «{{ui:billing.common.description}}».

### Способи поділу

Спосіб поділу вибирають для кожного споживача в [налаштуваннях](help:settings), блок «{{ui:settings.commonConsumers.title}}». Споживач без власного вибору діє за замовчуванням кооперативу.

| Спосіб | Кому нараховується |
|---|---|
| {{ui:billing.common.split.CONNECTED_PLOTS}} | Ділянкам, підключеним до цієї свердловини, за днями підключення. Непідключена ділянка не платить. |
| {{ui:billing.common.split.EQUAL_PER_PLOT}} | Усім активним ділянкам, з лічильником чи без. |
| {{ui:billing.common.split.SERVICE_USERS}} | Ділянкам зі списку в налаштуваннях споживача (наприклад, освітлення). |
| {{ui:billing.common.split.NOT_CHARGED}} | Нікому: входить у членський внесок, показується у звітах. |

### Період і перегляд

Робота така сама, як на сторінці [Втрати](help:losses).

| Елемент | Що робить |
|---|---|
| {{ui:billing.rangeRun.from}} і {{ui:billing.rangeRun.to}} | Період; «По» не пізніше за сьогодні. |
| {{ui:billing.rangeRun.preview}} | Показує, що буде нараховано; нічого не записує. |
| {{ui:billing.rangeRun.book}} | Проводить те, що ви бачили: «{{ui:billing.rangeRun.done}}». Нарахування потрапляє в місяць дня «По» або в найраніший відкритий після нього. |
| {{ui:billing.common.excludeOverlap}} | Якщо частину споживачів за ці дні вже нараховано, проводить решту. |

### Таблиця «{{ui:billing.common.consumersCaption}}»

Колонки: {{ui:billing.common.columns.consumer}}, {{ui:billing.common.columns.consumption}}, {{ui:billing.common.columns.split}}, {{ui:billing.common.columns.plotDays}}, {{ui:billing.common.columns.plots}} і {{ui:billing.common.columns.amount}}. Результат споживача: «{{ui:billing.common.outcome.CHARGED}}», «{{ui:billing.common.outcome.NOT_CHARGED}}», «{{ui:billing.common.outcome.NO_PLOTS}}», «{{ui:billing.common.outcome.UNKNOWN}}» чи «{{ui:billing.common.outcome.ALREADY_CHARGED}}».

### Повідомлення й попередження

| Що показано | Що робити |
|---|---|
| {{ui:billing.common.noTariffNotice}} | Створіть [тариф](help:tariff-form) виду «{{ui:chargeTypes.defaultNames.COMMON_CONSUMERS}}», підстава «{{ui:tariffs.basis.PER_KWH}}». Кнопка «{{ui:billing.common.openTariffs}}» веде до тарифів. Без тарифу в таблиці «{{ui:billing.common.noTariff}}», і провести нічого не можна. |
| Невідоме значення | У лічильника споживача немає показу після кінця періоду. Споживач потрапляє у [«Не нараховано»](help:not-charged); коли з’являться покази, «Повторити» нарахує його окремо. |
| {{ui:problem.COMMON_CONSUMER_RANGE_OVERLAP}} | Споживача за ці дні вже нараховано. Перегляньте знову й проведіть інших. |
| Немає ділянок | Жодна ділянка не користується споживачем у цьому періоді, тож нікому нараховувати. |

### Минулі розрахунки

Блок «{{ui:billing.runHistory.title}}» працює, як на сторінці [Втрати](help:losses): «{{ui:billing.runHistory.details}}» і «{{ui:billing.runHistory.reverse}}» з обов’язковою причиною. Сторнувати можна, поки місяць запуску не закрито.

## Приклад

Насос свердловини спожив за період 60 кВт·год. До свердловини підключені три ділянки на весь період, ще одна ділянка не підключена. Спосіб поділу «{{ui:billing.common.split.CONNECTED_PLOTS}}»:

- кожна з трьох отримує 60 / 3 = 20 кВт·год, тобто 20 × 4,32 = 86,40 ₴;
- непідключена ділянка не платить нічого.

Якщо частину місяця одна з ділянок не була підключена, діляться ділянко-дні. Насос 60 кВт·год, ділянки П1 і П2 підключені весь місяць, а П3 лише з 16.10: це 30 + 30 + 15 = 75 ділянко-днів. Частки: П1 24, П2 24, П3 12 кВт·год.

Освітлення 30 кВт·год на три ділянки зі списку: по 10 кВт·год, тобто 43,20 ₴ кожній.

У демо проведено запуск: насос серед ділянок, підключених до нього, а освітлення порівну на всі ділянки, за тарифом 4,32 ₴ за кВт·год.

## Знімки екрана

![Вкладка «Спільні споживачі»](shot:charges-common)

## Пов’язані сторінки

- [Нарахування: місяці](help:charges)
- [Втрати](help:losses)
- [Налаштування кооперативу](help:settings)
- [Картка ділянки: підключення до води](help:plot-card)
- [Як система рахує втрати й спільних споживачів](help:how-losses-common)
- [Вода й підключення](help:how-water-connection)
