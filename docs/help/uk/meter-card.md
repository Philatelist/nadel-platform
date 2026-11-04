---
id: meter-card
title: "Картка лічильника"
description: "Лічильник з історією показів: редагування, заміна, прокрутка через нуль, зняття, видалення та виправлення показів."
kind: screen
routes:
  - /meters/:id
permissions:
  - METERS_VIEW
section: meters
order: 20
shots:
  - name: meter-card
    user: chairman
    path: /meters
    open: { css: 'main a[href^="/meters/"]', text: 'ЕЛ-9/2' }
  - name: reading-recalculation-step
    user: chairman
    path: /meters
    open: { css: 'main a[href^="/meters/"]', text: 'ЕЛ-9/2' }
    steps:
      # Safe: every reading of this demo meter (installation point, 112.4 and 190.7) is an end of a charged interval
      # (R__demo_data.sql, demo_interval -> charge + meter_value_use), so "Save" answers with the recalculation preview
      # and nothing is stored (and 195 is out of order against the next reading if the oldest row is the first one).
      - { click: Виправити }
      - { fill: { label: 'Показ, кВт·год', value: '195' } }
      - { click: Зберегти виправлення }
    element: { css: 'dialog[open]' }
---

## Для чого цей екран

Тут один лічильник: його дані, зв’язок із попереднім чи наступним лічильником у разі заміни та історія всіх показів. Тут голова редагує лічильник, замінює, знімає, видаляє і виправляє покази.

## Хто бачить

Картку бачать голова й казначей (право «{{ui:permission.METERS_VIEW.name}}»). Кнопки зміни лічильника має той, хто має ще й «{{ui:permission.METERS_MANAGE.name}}», тобто голова. Виправити чи видалити показ можна з правом «{{ui:permission.READINGS_ENTER.name}}». Але якщо показ уже нараховано, це робить лише той, хто може перераховувати («{{ui:permission.RECALCULATION_RUN.name}}»). Електрик без цього права бачить: «{{ui:readings.usedNeedsChairman}}», і нічого не змінюється.

## Елементи екрана

Угорі посилання «{{ui:meters.back}}», назва «Лічильник …» і позначка «{{ui:meters.active}}» чи «{{ui:meters.removed}}».

### Дані лічильника

Вид, ресурс, ділянка (посилання на картку ділянки), серійний номер, назва, дата встановлення, початковий показ, а для знятого ще дата зняття й кінцевий показ. Також останній показ і примітка.

Якщо лічильник замінено, видно зв’язок: «{{ui:meters.chainReplaced}}» (у нового) і «{{ui:meters.chainReplacedBy}}» (у старого). Обидва лічильники зберігають історію.

### Кнопки

| Кнопка | Що робить |
|---|---|
| {{ui:meters.edit}} | Діалог «{{ui:meters.editTitle}}»: змінити серійний номер, назву, дату встановлення чи примітку. |
| {{ui:meters.replace}} | Діалог «{{ui:meters.replaceTitle}}», див. нижче. Лише для діючого лічильника. |
| {{ui:meters.remove}} | Діалог «{{ui:meters.removeTitle}}»: дата зняття й кінцевий показ. Лічильник перестає працювати з цієї дати, покази зберігаються. |
| {{ui:meters.delete}} | Питання «{{ui:meters.deleteTitle}}». Видалити можна лише лічильник без показів, створений помилково. Цю дію не можна скасувати. |

### Заміна лічильника

Одна дія: дата заміни, кінцевий показ старого, серійний номер і початковий показ нового. Старий знімається в цей день, а новий встановлюється того ж дня. Поля: {{ui:meters.replacedOn}}, {{ui:meters.oldFinalReading}}, {{ui:meters.newSerial}}, {{ui:meters.newInitialReading}}. Кнопка «{{ui:meters.replaceSubmit}}». Без серійного номера: «{{ui:meters.serialRequired}}».

Лічильник прокрутився через нуль (наприклад, з 99 999 на 00 012)? Це оформлюється як заміна лічильника самим собою. Кнопка «{{ui:meters.wrapAroundAction}}» сама підставляє той самий серійний номер, кінцевий показ перед прокруткою й 0 як початковий. Підказка в діалозі: «{{ui:meters.wrapAroundHint}}»

### Блок «{{ui:meters.historyTitle}}»

Таблиця показів: {{ui:readings.history.columns.date}}, {{ui:readings.history.columns.value}}, {{ui:readings.history.columns.status}}, {{ui:readings.history.columns.source}}, споживання за інтервал і дії. Стан показу: «{{ui:readings.history.status.APPROVED}}», «{{ui:readings.history.status.PENDING}}», «{{ui:readings.history.status.REJECTED}}» (з причиною відхилення) чи «{{ui:readings.history.status.SUPERSEDED}}». Джерело: «{{ui:readings.history.source.ENTERED}}», «{{ui:readings.history.source.IMPORTED}}» чи «{{ui:readings.history.source.MEMBER}}». Показ за ту саму дату замінює попередній; старий лишається в історії з позначкою «замінено». Порожня історія: «{{ui:readings.history.empty}}».

Для кожного показу є дії:

- «{{ui:readings.history.correct}}» відкриває діалог «{{ui:readings.history.correctTitle}}», кнопка «{{ui:readings.history.correctSubmit}}»;
- «{{ui:readings.history.delete}}» просить підтвердження «{{ui:readings.history.deleteTitle}}»: показ видаляється з історії, а споживання за інтервалами перераховується.

Поки за показом немає нарахування, його виправлення проходить просто. Після нарахування:

- голова бачить крок перегляду «{{ui:readings.recalc.title}}» з причиною: що саме зміниться в нарахуваннях, і після проведення «{{ui:readings.recalc.corrected}}»;
- електрик без права перераховувати бачить «{{ui:readings.usedNeedsChairman}}».

Помилки показу: «{{ui:readings.errors.READING_OUT_OF_ORDER}}», «{{ui:readings.errors.READING_DATE_IN_FUTURE}}», «{{ui:readings.errors.READING_DATE_OUTSIDE_METER}}», «{{ui:readings.errors.METER_VALUE_INVALID}}».

## Приклад

Ділянка 9 у демо: старий лічильник ЕЛ-9 знято 30 днів тому з кінцевим показом 3 340, а новий ЕЛ-9/2 встановлено того ж дня з нуля.

1. Відкрийте ЕЛ-9/2 з [реєстру](help:meters). У блоці даних видно «Замінив лічильник ЕЛ-9…».
2. У історії знайдіть показ і натисніть «{{ui:readings.history.correct}}». Змініть значення на 195 і натисніть «{{ui:readings.history.correctSubmit}}».
3. Обидва покази цього лічильника вже нараховано, тому з’являється крок перегляду перерахунку. Нічого не збережено, доки ви не введете причину й не натиснете «{{ui:recalculation.step.book}}».

Заміна розбиває споживання на окремі інтервали, і кожен нараховується за тарифом 4,32 ₴ за кВт·год. У демо: старий лічильник від останнього показу 3 250 до кінцевого 3 340 дає 90 кВт·год, тобто 90 × 4,32 = 388,80 ₴. Новий лічильник від 0 до показу 112,4 дає 112,4 × 4,32 = 485,57 ₴, а від 112,4 до 190,7 дає 78,3 кВт·год, тобто 78,3 × 4,32 = 338,26 ₴.

## Знімки екрана

![Картка лічильника з історією показів](shot:meter-card)

![Крок перегляду після виправлення нарахованого показу](shot:reading-recalculation-step)

## Пов’язані сторінки

- [Лічильники](help:meters)
- [Покази: обхід електрика](help:readings-round)
- [Покази на підтвердження](help:readings-approvals)
- [Новий перерахунок](help:recalculation-new)
- [Як система рахує покази й споживання](help:how-readings-consumption)
