---
id: recalculations
title: "Перерахунки: журнал"
description: "Журнал виправлень минулих нарахувань: коли, чому, хто провів, на яку суму змінилося, і як відкрити окремий перерахунок."
kind: screen
routes:
  - /charges/recalculations
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 100
shots:
  - name: recalculations
    user: chairman
    path: /charges/recalculations
---

## Для чого цей екран

Іноді минулі нарахування треба виправити: ставку тарифу ввели з помилкою, показ лічильника виправили, площа ділянки змінилася. Це робить перерахунок. Журнал тут показує всі проведені перерахунки: коли, з якої причини, хто провів і наскільки змінилися суми. Журнал лише для перегляду: перерахунок ніколи не змінюється й не скасовується, а помилку виправляє наступний перерахунок.

## Хто бачить

Журнал бачать голова й казначей (право «{{ui:permission.CHARGES_VIEW.name}}»). Кнопка нового перерахунку є лише в того, хто має право «{{ui:permission.RECALCULATION_RUN.name}}», тобто голови.

## Елементи екрана

Угорі заголовок «{{ui:recalculation.heading}}» з поясненням «{{ui:recalculation.description}}» і вкладки нарахувань. Якщо перерахунків ще не було: «{{ui:recalculation.empty}}».

### Кнопка

{{ui:recalculation.newButton}} відкриває [форму нового перерахунку](help:recalculation-new). Кнопка є лише в голови.

### Таблиця

| Колонка | Що показує |
|---|---|
| {{ui:recalculation.columns.time}} | Дата й час проведення. |
| {{ui:recalculation.columns.cause}} | Підстава: «{{ui:recalculation.causeKind.MANUAL}}», «{{ui:recalculation.causeKind.TARIFF_VERSION}}» чи «{{ui:recalculation.causeKind.READING}}», а також що саме сталося (додано, виправлено чи видалено версію тарифу, внесено, виправлено чи видалено показ). |
| {{ui:recalculation.columns.author}} | Хто провів; «{{ui:recalculation.system}}», якщо перерахунок виконала система. |
| {{ui:recalculation.columns.reason}} | Причина, яку вказала людина. |
| {{ui:recalculation.columns.total}} | Різниця в сумі й кількість ділянок. |
| {{ui:recalculation.columns.actions}} | Кнопка «{{ui:recalculation.open}}»: відкриває [картку перерахунку](help:recalculation-card). |

Список розбито на сторінки; внизу кнопки «{{ui:pagination.previous}}» і «{{ui:pagination.next}}».

### Підказка на інших екранах

Екрани, що змінюють дані, які впливають на минулі нарахування (площа ділянки, склад тарифу, підключення до води, переміщення лічильника в дереві), нічого не перераховують самі. Вони нагадують: «{{ui:recalculation.hint.text}}» Підказку бачить лише той, хто може перераховувати.

## Приклад

У демо є один проведений перерахунок: ділянка 2, ставка членського внеску була 60 ₴ за сотку на рік замість 50.

1. Відкрийте «{{ui:billing.tabs.recalculations}}».
2. Знайдіть єдиний рядок і подивіться його підставу, причину й різницю.
3. Натисніть «{{ui:recalculation.open}}», щоб побачити, що саме змінилося: див. [Картку перерахунку](help:recalculation-card).

Ділянка 6 соток: старий внесок 60 × 6 / 12 = 30,00 ₴ на місяць, новий 50 × 6 / 12 = 25,00 ₴, різниця −5,00 ₴ за кожен місяць.

## Знімки екрана

![Журнал перерахунків](shot:recalculations)

## Пов’язані сторінки

- [Новий перерахунок](help:recalculation-new)
- [Картка перерахунку](help:recalculation-card)
- [Нарахування: місяці](help:charges)
- [Як система рахує перерахунок](help:how-recalculation)
