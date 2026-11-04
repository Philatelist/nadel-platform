---
id: not-charged
title: "Не нараховано"
description: "Черга позицій, які система не змогла нарахувати: причина кожної, як усунути її і як повторити нарахування."
kind: screen
routes:
  - /charges/not-charged
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 70
shots:
  - name: charges-not-charged
    user: chairman
    path: /charges/not-charged
---

## Для чого цей екран

Іноді система не може нарахувати, бо їй чогось бракує: немає тарифу на частину днів, на ділянку діють два тарифи, невідомий показ лічильника. Усе таке потрапляє сюди, з причиною. Ви усуваєте причину й повторюєте нарахування. Тоді позиція зникає зі списку.

## Хто бачить

Список бачать голова й казначей (право «{{ui:permission.CHARGES_VIEW.name}}»). Кнопку повтору має той, хто може проводити нарахування (право «{{ui:permission.CHARGES_RUN.name}}»), тобто голова.

## Елементи екрана

Угорі заголовок «{{ui:billing.notCharged.heading}}» з поясненням «{{ui:billing.notCharged.description}}» і вкладки нарахувань. Якщо черга порожня, екран пише «{{ui:billing.notCharged.empty}}».

### Таблиця

| Колонка | Що показує |
|---|---|
| {{ui:billing.notCharged.columns.plot}} | Номер ділянки, а для втрат чи спільних споживачів вузол, лічильник чи споживач. |
| {{ui:billing.notCharged.columns.type}} | Вид нарахування або тип позиції: «{{ui:billing.notCharged.kind.TARIFF_MONTH}}», «{{ui:billing.notCharged.kind.INTERVAL}}», «{{ui:billing.notCharged.kind.LOSSES_NODE}}», «{{ui:billing.notCharged.kind.COMMON_CONSUMER}}». |
| {{ui:billing.notCharged.columns.period}} | За який місяць чи дні. |
| {{ui:billing.notCharged.columns.reason}} | Причина і, де можна, посилання на виправлення, наприклад «{{ui:billing.notCharged.tariffs}}». |
| {{ui:billing.notCharged.columns.actions}} | Кнопка «{{ui:billing.notCharged.retry}}» (лише для голови). |

### Причини і що робити

| Причина | Що зробити |
|---|---|
| {{ui:billing.notCharged.reason.NO_TARIFF_FOR_ALL_DAYS}} | Додайте версію тарифу, яка охоплює ці дні, на [картці тарифу](help:tariff-card). |
| {{ui:billing.notCharged.reason.CONFLICTING_TARIFFS}} | Змініть ділянки одного з двох тарифів так, щоб вони не перетиналися. |
| {{ui:billing.notCharged.reason.OUTSIDE_SCOPE}} | Додайте ділянку до тарифу або створіть тариф, який її охоплює. |
| {{ui:billing.notCharged.reason.EXCLUDED}} | Це рішення правління: ділянку свідомо виключено. Якщо виключення помилкове, приберіть його на картці тарифу. |
| {{ui:billing.notCharged.reason.UNKNOWN_NODE_VALUE}} | У лічильника вузла немає показу після кінця періоду. Внесіть показ на екрані [«Покази»](help:readings-round). |
| {{ui:billing.notCharged.reason.UNKNOWN_CONSUMER_VALUE}} | Те саме для лічильника спільного споживача. |

### Кнопка «{{ui:billing.notCharged.retry}}»

Пробує нарахувати одну позицію ще раз. Якщо вдалося, позиція зникає зі списку. Якщо ні, біля кнопки видно «{{ui:billing.notCharged.stillNotCharged}}», і причина лишається. Під час спроби кнопка пише «{{ui:billing.notCharged.retrying}}». Наступне нарахування місяця теж повторює записи тарифів і показів.

Позиції ніколи не блокують закриття місяця: вони лише з’являються в переліку перед закриттям.

## Приклад

У демо ділянка 25 має лічильник, встановлений 15.12.2025, до початку першої версії тарифу на електроенергію (01.01.2026). Його інтервал не можна нарахувати: у списку є рядок із причиною «{{ui:billing.notCharged.reason.NO_TARIFF_FOR_ALL_DAYS}}».

Крім того, у вузла «Квартал Б» невідоме значення: у лічильника ділянки 20 немає показу пізніше за 75 днів тому. Рядок стоїть у черзі втрат.

1. Відкрийте «{{ui:billing.tabs.notCharged}}».
2. Подивіться причину біля рядка ділянки 25.
3. Додайте версію тарифу, що діє з 15.12.2025, або залиште як є, якщо ці дні справді не підлягають оплаті.
4. Натисніть «{{ui:billing.notCharged.retry}}».

## Знімки екрана

![Черга «Не нараховано» з причинами й кнопкою повтору](shot:charges-not-charged)

## Пов’язані сторінки

- [Нарахування: місяці](help:charges)
- [Картка тарифу](help:tariff-card)
- [Покази: обхід електрика](help:readings-round)
- [Як система рахує нарахування й закриття місяців](help:how-charges-months)
