---
id: recalculation-card
title: "Картка перерахунку"
description: "Один проведений перерахунок: охоплення, суми за видами, рядки «було, має бути, різниця», створені записи й посилання на рахунок ділянки."
kind: screen
routes:
  - /charges/recalculations/:id
permissions:
  - CHARGES_VIEW
section: tariffs-charges
order: 120
shots:
  - name: recalculation-detail
    user: chairman
    path: /charges/recalculations
    open: { css: 'main a[href^="/charges/recalculations/"]:not([href$="/new"])' }
  - name: plot-account-adjustment
    user: chairman
    path: /plots
    open: { css: 'main a[href^="/plots/"]', text: '2' }
    steps:
      - { check: Лише коригування }
    element: { heading: Рахунок }
---

## Для чого цей екран

Тут докладно видно один проведений перерахунок: що перераховувалося, чому, хто провів і як змінилася кожна ділянка. Це лише перегляд. Перерахунок ніколи не змінюється й не скасовується, а помилку виправляє наступний [перерахунок](help:recalculation-new).

## Хто бачить

Картку бачать голова й казначей (право «{{ui:permission.CHARGES_VIEW.name}}»). Член кооперативу бачить лише коригування своїх ділянок у «Моїх нарахуваннях»: причину, але не сам перерахунок.

## Елементи екрана

Угорі посилання «{{ui:recalculation.detail.back}}». Якщо перерахунок не знайдено: «{{ui:recalculation.detail.notFound}}».

### Загальні відомості

Поля: {{ui:recalculation.detail.cause}}, {{ui:recalculation.detail.months}}, {{ui:recalculation.detail.plots}}, {{ui:recalculation.detail.reason}}, {{ui:recalculation.detail.bookedAt}}, {{ui:recalculation.detail.author}}. Блок «{{ui:recalculation.detail.scope}}» показує місяці й ділянки.

### Блок «{{ui:recalculation.detail.totals}}»

Різниця за кожним видом нарахувань і «{{ui:recalculation.detail.total}}».

### Рядки перерахунку

Для кожної ділянки окремий блок із заголовком «{{ui:recalculation.detail.plot}}» і посиланням «{{ui:recalculation.detail.plotAccount}}». Рядки згруповані за місяцями. Колонки:

| Колонка | Що показує |
|---|---|
| {{ui:recalculation.detail.columns.type}} | Вид нарахування. |
| {{ui:recalculation.detail.columns.period}} | За який період. |
| {{ui:recalculation.detail.columns.booked}} | Скільки було нараховано. |
| {{ui:recalculation.detail.columns.expected}} | Скільки мало бути. |
| {{ui:recalculation.detail.columns.difference}} | Різниця. |
| {{ui:recalculation.detail.columns.action}} | Дія: «{{ui:recalculation.action.REVERSE_AND_RECHARGE}}», «{{ui:recalculation.action.NEW}}», «{{ui:recalculation.action.REVERSE}}» чи коригування у відкритому місяці. |
| {{ui:recalculation.detail.columns.reasons}} | Причини зміни, наприклад «{{ui:recalculation.changeReason.TARIFF_VERSION}}», «{{ui:recalculation.changeReason.READING}}», «{{ui:recalculation.changeReason.WATER_CONNECTION}}», «{{ui:recalculation.changeReason.METER_TREE}}». |
| {{ui:recalculation.detail.columns.created}} | Створені записи: «{{ui:recalculation.detail.created.original}}», «{{ui:recalculation.detail.created.reversal}}», «{{ui:recalculation.detail.created.newCharge}}», «{{ui:recalculation.detail.created.adjustment}}»; кожен із посиланням на рахунок ділянки. |

Якщо рядків немає, пише «{{ui:recalculation.detail.noLines}}».

### Як це виглядає на рахунку ділянки

Коригування закритого місяця стоїть на [рахунку ділянки](help:plot-card) окремим рядком, наприклад «Коригування за 09.2026: −5,00 ₴», з причиною, посиланнями «{{ui:charges.adjustment.openOriginal}}» та «{{ui:charges.adjustment.openRecalculation}}» і міткою «{{ui:charges.kind.ADJUSTMENT}}». Прапорець «{{ui:charges.adjustment.only}}» залишає тільки такі рядки.

## Приклад

Перерахунок ділянки 2 у демо: ставка внеску 60 → 50 ₴ за сотку на рік.

1. У журналі натисніть «{{ui:recalculation.open}}».
2. У блоці «{{ui:recalculation.detail.plot}}» знайдіть два місяці. У закритому вересні: було 30,00, має бути 25,00, різниця −5,00, дія коригування в жовтні.
3. У відкритому жовтні: сторно −30,00 і нове нарахування 25,00.
4. Натисніть «{{ui:recalculation.detail.plotAccount}}», щоб побачити рядок «Коригування» на рахунку ділянки.

## Знімки екрана

![Картка перерахунку з рядками й посиланнями на записи](shot:recalculation-detail)

![Коригування вересня на рахунку ділянки 2](shot:plot-account-adjustment)

## Пов’язані сторінки

- [Перерахунки: журнал](help:recalculations)
- [Новий перерахунок](help:recalculation-new)
- [Картка ділянки](help:plot-card)
- [Як система рахує перерахунок](help:how-recalculation)
