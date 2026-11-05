---
id: recalculation-new
title: "Новий перерахунок"
description: "Як провести перерахунок минулих нарахувань: вибір місяців, видів і ділянок, перегляд змін, причина й проведення."
kind: screen
routes:
  - /charges/recalculations/new
permissions:
  - RECALCULATION_RUN
section: tariffs-charges
order: 110
shots:
  - name: recalculation-new
    user: chairman
    path: /charges/recalculations/new
  - name: recalculation-preview-empty
    user: chairman
    path: /charges/recalculations/new
    steps:
      - { click: Переглянути }
---

## Для чого цей екран

Тут голова перераховує минулі нарахування, коли змінилися дані, від яких вони залежать: ставка тарифу, показ лічильника, площа ділянки, склад тарифу, підключення до води чи дерево лічильників. Система порівнює, що було нараховано, з тим, що мало б бути за нинішніми даними, і показує різницю. Нічого не записується, доки ви не введете причину й не проведете.

## Хто бачить

Екран бачить той, хто має право «{{ui:permission.RECALCULATION_RUN.name}}»: за замовчуванням голова. Казначей [журнал](help:recalculations) бачить, а перераховувати не може.

Електрик, який може вносити покази, але не перераховувати, при виправленні вже нарахованого показу бачить: «{{ui:readings.usedNeedsChairman}}». Нічого при цьому не змінюється.

## Елементи екрана

Угорі посилання «{{ui:recalculation.form.back}}», заголовок «{{ui:recalculation.form.title}}» і пояснення.

### Крок 1: що перераховувати

| Поле | Що вказати |
|---|---|
| {{ui:recalculation.form.from}} | Перший місяць. Не пізніше за поточний. |
| {{ui:recalculation.form.to}} | Останній місяць. Не більше 24 місяців від початкового. |
| {{ui:recalculation.form.chargeTypes}} | «{{ui:recalculation.form.chargeTypesAll}}» або «{{ui:recalculation.form.chargeTypesChosen}}» з галочками видів. Хоча б один вид: «{{ui:recalculation.form.chargeTypesNone}}». |
| {{ui:recalculation.form.plots}} | «{{ui:recalculation.form.plotsAll}}» або «{{ui:recalculation.form.plotsChosen}}»: знайдіть ділянку за номером («{{ui:recalculation.form.plotsSearch}}») і додайте. Кнопка біля номера прибирає ділянку. Хоча б одна: «{{ui:recalculation.form.plotsNone}}». |

Кнопка «{{ui:recalculation.form.preview}}» рахує, що зміниться; нічого не записує. Помилки форми: «{{ui:recalculation.form.monthsRequired}}», «{{ui:recalculation.form.monthsOrder}}», «{{ui:recalculation.form.monthInFuture}}», «{{ui:problem.RECALCULATION_RANGE_TOO_LONG}}».

### Крок 2: що зміниться

Блок «{{ui:recalculation.preview.title}}» показує охоплення (місяці та кількість ділянок зі змінами), різницю за видами («{{ui:recalculation.preview.totals}}») і рядки «було нараховано → має бути → різниця». Біля кожного рядка причина зміни, наприклад «{{ui:recalculation.changeReason.TARIFF_VERSION}}» чи «{{ui:recalculation.changeReason.AREA}}». Дія для рядка:

| Дія | Коли |
|---|---|
| {{ui:recalculation.action.REVERSE_AND_RECHARGE}} | Місяць відкритий: старе нарахування сторнується, створюється нове. |
| Коригування у відкритому місяці | Місяць закритий: у ньому нічого не змінюється, а в найближчому відкритому з’являється коригування на різницю з посиланням на вихідний місяць. |
| {{ui:recalculation.action.NEW}} | Раніше не було нараховано. |
| {{ui:recalculation.action.REVERSE}} | Нарахування більше не належить. |

Далі:

- поле «{{ui:recalculation.preview.reason}}»: обов’язкове, до 500 символів, лічильник «{{ui:recalculation.preview.reasonCounter}}»; підказка «{{ui:recalculation.preview.reasonHint}}»; без причини: «{{ui:recalculation.preview.reasonRequired}}»;
- кнопка «{{ui:recalculation.step.book}}» проводить саме той перегляд, який ви бачили;
- кнопка «{{ui:recalculation.step.back}}» повертає до вибору.

Якщо тим часом нарахування змінилися, перегляд оновлюється й пише «{{ui:recalculation.step.outdated}}»: підтвердіть ще раз. Коли нарахування вже відповідають даним, перегляд каже «{{ui:recalculation.preview.noChanges}}», і проводити нічого.

> [!important]
> Закритий місяць не змінюється ніколи. Виправлення потрапляє в найближчий відкритий місяць як коригування. Якщо борг зменшився, а його вже сплатили, надлишок стає передоплатою, а не «від’ємним боргом».

### Інші входи в перерахунок

Той самий крок перегляду відкривається без цієї форми, коли ви:

- виправляєте чи видаляєте версію тарифу, що вже використана, або додаєте нову версію заднім числом: на [картці тарифу](help:tariff-card);
- виправляєте чи видаляєте вже нарахований показ: на [картці лічильника](help:meter-card), заголовок «{{ui:readings.recalc.title}}».

У «{{ui:nav.approvals}}» кроку перерахунку немає. Якщо показ уже затверджено й нараховано, виправте його на картці лічильника.

## Приклад

З 1 вересня помилково введено 60 ₴/сотка/рік замість 50, ділянка 6 соток. Вересень закритий, жовтень відкритий.

| | Було | Після виправлення |
|---|---|---|
| Вересень (закритий) | 30,00 | Не змінюється; у жовтні коригування −5,00 «за вересень» |
| Жовтень (відкритий) | 30,00 | Сторно −30,00 і нове нарахування 25,00 |
| Разом | 60,00 | 50,00; якщо сплачено 60,00, передоплата 10,00 |

Кроки: виберіть місяці вересень і жовтень, вид «Членський внесок», ділянку 2, натисніть «{{ui:recalculation.form.preview}}», перевірте рядки, введіть причину «Помилкова ставка» і натисніть «{{ui:recalculation.step.book}}». Демо вже узгоджене, тому перегляд усіх ділянок відкритого місяця каже «{{ui:recalculation.preview.noChanges}}».

## Знімки екрана

![Форма нового перерахунку](shot:recalculation-new)

![Перегляд, коли змін немає](shot:recalculation-preview-empty)

## Пов’язані сторінки

- [Перерахунки: журнал](help:recalculations)
- [Картка перерахунку](help:recalculation-card)
- [Картка тарифу](help:tariff-card)
- [Картка лічильника](help:meter-card)
- [Як система рахує перерахунок](help:how-recalculation)
