---
id: meters
title: "Лічильники"
description: "Реєстр лічильників кооперативу: фільтри, колонки, види лічильників і як зареєструвати новий лічильник."
kind: screen
routes:
  - /meters
permissions:
  - METERS_VIEW
section: meters
order: 10
shots:
  - name: meters
    user: chairman
    path: /meters
---

## Для чого цей екран

Це реєстр усіх лічильників кооперативу. Біля кожного значок ресурсу: блискавка для електроенергії, крапля для води. Звідси голова реєструє новий лічильник, а відкривши лічильник, бачить його [картку](help:meter-card) з історією показів.

## Хто бачить

Реєстр бачать голова й казначей: це ті, хто має право «{{ui:permission.METERS_VIEW.name}}». Реєструвати лічильники може лише той, хто має обидва права, «{{ui:permission.METERS_VIEW.name}}» і «{{ui:permission.METERS_MANAGE.name}}», тобто голова. Електрик із правом «{{ui:permission.READINGS_ENTER.name}}» вносить покази на екрані [«Покази»](help:readings-round).

## Елементи екрана

### Кнопка

{{ui:meters.add}} відкриває діалог «{{ui:meters.registerTitle}}» (лише для голови).

### Види лічильників

| Вид | Що це |
|---|---|
| {{ui:meters.kind.PLOT}} | Належить ділянці. На ділянці не більше одного діючого лічильника кожного ресурсу. |
| {{ui:meters.kind.SUBSTATION}} | Лічильник на трансформаторі кооперативу, корінь [дерева лічильників](help:meter-tree). |
| {{ui:meters.kind.QUARTER}} | Лічильник групи ділянок: вулиці чи кварталу. |
| {{ui:meters.kind.COMMON_CONSUMER}} | Насос свердловини, вуличне освітлення, контора. |

Кількість підстанцій, квартальних лічильників і свердловин не обмежена.

### Фільтри

| Фільтр | Що робить |
|---|---|
| {{ui:meters.filterResource}} | «{{ui:meters.allResources}}», «{{ui:meters.resource.ELECTRICITY}}» або «{{ui:meters.resource.WATER}}». |
| {{ui:meters.filterKind}} | «{{ui:meters.allKinds}}» чи один із видів вище. |
| {{ui:meters.filterStreet}} | Пошук за вулицею; список оновлюється сам. |
| {{ui:meters.showRemoved}} | Додає до списку зняті лічильники. |

Вибір фільтрів зберігається в адресі сторінки, тож посиланням можна поділитися. Якщо за фільтрами нічого немає, екран пише «{{ui:meters.nothingFound}}», а якщо лічильників немає зовсім: «{{ui:meters.empty}}».

### Колонки

{{ui:meters.columns.plot}}, {{ui:meters.columns.name}} (посилання на картку), {{ui:meters.columns.serial}}, {{ui:meters.columns.kind}}, {{ui:meters.columns.resource}}, {{ui:meters.columns.lastReading}} (значення й дата) і {{ui:meters.columns.status}}: «{{ui:meters.active}}» або «{{ui:meters.removed}}».

### Діалог «{{ui:meters.registerTitle}}»

| Поле | Що вказати |
|---|---|
| {{ui:meters.kindField}} | Вид лічильника. |
| {{ui:meters.resourceField}} | Електроенергія чи вода; для підстанцій, кварталів і споживачів лише електроенергія. |
| {{ui:meters.plotField}} | Для лічильника ділянки: знайдіть її за номером. Кнопка «{{ui:meters.changePlot}}» дає обрати іншу. Без ділянки: «{{ui:meters.plotRequired}}». |
| {{ui:meters.name}} | Для неділянкових лічильників, наприклад «Свердловина №1». |
| {{ui:meters.serial}} | Обов’язково. |
| {{ui:meters.installedOn}} | Дата встановлення. |
| {{ui:meters.initialReading}} | Початковий показ, не більше трьох знаків після коми: «{{ui:meters.valueHint}}». |
| {{ui:meters.note}} | Необов’язково. |

Кнопки: «{{ui:meters.save}}» і «{{ui:meters.cancel}}».

### Повідомлення про помилки

| Що показано | Що робити |
|---|---|
| {{ui:problem.METER_ACTIVE_EXISTS}} | Не додавайте другий: замініть чинний на його [картці](help:meter-card). |
| {{ui:meters.valueInvalid}} | Виправте початковий показ. |
| {{ui:meters.dateRequired}} | Оберіть дату встановлення. |

## Приклад

У демо 38 лічильників: підстанція «Підстанція №1», квартали «Квартал А» та «Квартал Б», «Свердловина №1» з насосом, «Освітлення вул. Вишнева», лічильники ділянок і чотири водомірі.

1. Відкрийте «{{ui:nav.meters}}» і виберіть ресурс «{{ui:meters.resource.WATER}}», щоб побачити водомірі.
2. Скиньте фільтр і поставте «{{ui:meters.showRemoved}}»: з’явиться лічильник ЕЛ-9, знятий 30 днів тому.
3. Натисніть ЕЛ-9/2, щоб відкрити [картку](help:meter-card) з історією заміни.

## Знімки екрана

![Реєстр лічильників](shot:meters)

## Пов’язані сторінки

- [Картка лічильника](help:meter-card)
- [Дерево лічильників](help:meter-tree)
- [Покази: обхід електрика](help:readings-round)
- [Як система рахує лічильники й дерево](help:how-meters-tree)
