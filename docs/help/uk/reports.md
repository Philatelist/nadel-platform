---
id: reports
title: "Звіти"
description: "Список звітів кооперативу: нарахування, борги, боржники, електроенергія, вода, покази, земельний податок, виправлення, платежі та чотири звіти каси; хто які бачить."
kind: screen
routes:
  - /reports
permissions:
  - REPORTS_VIEW
  - CASHBOOK_VIEW
section: reports
order: 10
shots:
  - name: reports
    user: chairman
    path: /reports
---

## Для чого цей екран

Звіти збирають в одну таблицю те, що система вже знає: нарахування, платежі, борги, електроенергію, воду, виправлення, касу. Кожен звіт видно на екрані й можна завантажити як файл Excel. Тут вибирають, який звіт відкрити, а також шаблони, у які звіт можна вписати.

Звіт нічого не рахує «по-своєму»: він лише вибирає, групує й додає вже проведені суми. Тому будь-яку суму зі звіту можна звірити з рахунком ділянки чи екраном місяця нарахувань до копійки. Суми у звітах — це завжди ефективні суми: нарахування плюс коригування мінус сторно.

## Хто бачить

Звіти відкривають, завантажують і формують файли за шаблонами ті, хто має «{{ui:permission.REPORTS_VIEW.name}}»: голова й казначей. Чотири звіти каси потребують лише «{{ui:permission.CASHBOOK_VIEW.name}}». Наприклад, власна роль «Ревізійна комісія» з одним цим правом бачить рівно ці чотири звіти й жодного іншого.

| Звіт | Яке право потрібне |
|---|---|
| Більшість звітів | «{{ui:permission.REPORTS_VIEW.name}}» |
| «{{ui:reports.debtors.title}}» | ще й «{{ui:permission.MEMBERS_VIEW.name}}» (у звіті імена й телефони) |
| «{{ui:reports.payments.title}}» | ще й «{{ui:permission.PAYMENTS_VIEW.name}}» (примітку про платника бачить лише той, хто має «{{ui:permission.MEMBERS_VIEW.name}}») |
| «{{ui:reports.cash-book.title}}», «{{ui:reports.bank-movements.title}}», «{{ui:reports.income-expense.title}}», «{{ui:reports.expenses.title}}» | «{{ui:permission.CASHBOOK_VIEW.name}}» |
| Завантажувати, замінювати й архівувати шаблони | «{{ui:permission.REPORT_TEMPLATES_MANAGE.name}}», за замовчуванням лише голова |

Роль із «{{ui:permission.REPORTS_VIEW.name}}», але без «{{ui:permission.MEMBERS_VIEW.name}}» бачить усі звіти, крім «{{ui:reports.debtors.title}}». Член кооперативу й електрик пункту «{{ui:nav.reports}}» в меню не бачать, а за прямою адресою отримують повідомлення, що доступу немає.

## Елементи екрана

### Картки звітів

Угорі — картки звітів, які вам доступні. У картці назва звіту й одне речення про нього; клік відкриває [звіт](help:report). Якщо нічого не доступно, написано «{{ui:reports.empty}}».

Список звітів:

- «{{ui:reports.charges-by-month.title}}»: що нараховано в кожному місяці за видами нарахувань.
- «{{ui:reports.plot-balances.title}}»: борг кожної діючої ділянки на дату.
- «{{ui:reports.debtors.title}}»: ділянки з боргом із іменами й контактами власників.
- «{{ui:reports.electricity-tree.title}}»: електроенергія від підстанції до ділянок.
- «{{ui:reports.water.title}}»: підключені до води ділянки й нарахування за воду.
- «{{ui:reports.missing-readings.title}}»: лічильники без підтвердженого показу за період.
- «{{ui:reports.land-tax.title}}»: очікуваний і нарахований земельний податок за рік.
- «{{ui:reports.corrections.title}}»: усі сторно й коригування вибраних місяців.
- «{{ui:reports.payments.title}}»: кожен платіж з підсумками за джерелом.
- «{{ui:reports.cash-book.title}}», «{{ui:reports.bank-movements.title}}», «{{ui:reports.income-expense.title}}», «{{ui:reports.expenses.title}}»: чотири звіти каси.

Докладно про кожен звіт, його фільтри й колонки — на сторінці [Звіт](help:report).

### Шаблони

- Хто має «{{ui:permission.REPORTS_VIEW.name}}», але не може керувати шаблонами, бачить розділ «{{ui:reportTemplates.generate.listTitle}}» з готовими шаблонами: кнопка «{{ui:reportTemplates.generate.button}}» біля шаблону формує файл за обраний період.
- Голова бачить картку «{{ui:reportTemplates.cardTitle}}» — посилання на [сторінку шаблонів](help:report-templates), де їх завантажують і архівують.

### Корисні посилання з інших екранів

На місяці нарахувань посилання «{{ui:reports.monthReportLink}}» відкриває «{{ui:reports.charges-by-month.title}}» саме за цей місяць. На рахунку ділянки є посилання на звіт про борги за вулицею цієї ділянки.

> [!important]
> У звіті «{{ui:reports.charges-by-month.title}}» два аркуші: «{{ui:reports.charges-by-month.sheets.byBillingMonth}}» і «{{ui:reports.charges-by-month.sheets.byServicePeriod}}». Перший групує гроші за місяцем, у якому нарахування записано в книгу. Другий групує ті самі гроші за місяцем, за який надано послугу. Це різні речі: показ, знятий у жовтні, може нести електроенергію за серпень і вересень. У першому аркуші ці гроші стоять у жовтні, у другому — у серпні й вересні.

## Приклад

Голова готує звіт для загальних зборів про електроенергію.

1. Відкриває «{{ui:nav.reports}}» і натискає картку «{{ui:reports.electricity-tree.title}}».
2. На сторінці звіту обирає період і натискає «{{ui:reports.show}}».
3. Натискає «{{ui:reports.download}}» й отримує файл Excel.

Для звіту про суму нарахувань за серпень голова відкриває «{{ui:reports.charges-by-month.title}}» і дивиться на другий аркуш: у «{{ui:reports.charges-by-month.sheets.byServicePeriod}}» буде й електроенергія, показ якої зняли в жовтні, але яка стосується серпня.

## Знімки екрана

![Екран «Звіти»: картки звітів і шаблони](shot:reports)

## Пов’язані сторінки

- [Звіт: фільтри, перегляд і Excel](help:report)
- [Шаблони звітів](help:report-templates)
- [Каса і банк](help:cashbook)
- [Нарахування за місяць](help:charges-month)
- [Як працюють звіти](help:how-reports)
