---
id: cashbook
title: "Каса і банк"
description: "Два рахунки кооперативу, каса й банк: залишки, вхідний залишок, журнал руху коштів, здача готівки в банк, інші надходження та сторнування записів."
kind: screen
routes:
  - /cashbook
permissions:
  - CASHBOOK_VIEW
section: payments-cash
order: 40
shots:
  - name: cashbook
    user: chairman
    path: /cashbook
  - name: cashbook-phone
    user: chairman
    path: /cashbook
    viewport: phone
---

## Для чого цей екран

Система знає не лише, скільки члени винні й платять, а й де ці гроші: у касі казначея чи на банківському рахунку, і на що їх витрачено. Цей екран показує обидва рахунки, їхні залишки та журнал руху коштів. Борги членів він не змінює: він показує, куди пішли їхні гроші.

У кооперативі рівно два рахунки: «{{ui:cashbook.account.CASH}}» (готівка в казначея) і «{{ui:cashbook.account.BANK}}». Другої каси чи другого рахунку в банку немає.

## Хто бачить

Екран бачать ті, хто має право «{{ui:permission.CASHBOOK_VIEW.name}}»: голова й казначей. Кнопки внесення записів бачать ті, хто має «{{ui:permission.CASHBOOK_ENTER.name}}» (казначей і голова). Вхідні залишки, оформлення різниць і сторнування записів доступні лише тому, хто має «{{ui:permission.CASHBOOK_APPROVE.name}}»: за замовчуванням це голова. Без права внесення видно лише залишки й журнали.

Член кооперативу цього пункту меню не бачить, а за прямою адресою отримує повідомлення, що доступу немає. Кожне з трьох прав можна дати власній ролі на сторінці [Ролі](help:roles).

## Елементи екрана

### Кнопки в заголовку

| Кнопка | Що робить |
|---|---|
| {{ui:expenses.title}} | Відкриває [список витрат](help:expenses). |
| {{ui:cashbook.count.action}} | Відкриває [перерахунок каси](help:cash-count). Потрібне право внесення. |
| {{ui:cashbook.handover.action}} | Вікно здачі готівки в банк (нижче). Потрібне право внесення. |
| {{ui:cashbook.otherIncome.action}} | Вікно іншого приходу (нижче). Потрібне право внесення. |
| {{ui:expenses.add}} | Відкриває [форму витрати](help:expense-form). Потрібне право внесення. |

На телефоні картки йдуть одна під одною, і все працює так само.

### Картка рахунку

Кожен рахунок має свою картку.

- **Залишок.** Великий рядок «{{ui:cashbook.balance}}» показує залишок на сьогодні. Під ним написано, який був вхідний залишок і від якої дати.
- **Вхідний залишок.** Поки в рахунка його немає, замість суми стоїть «{{ui:cashbook.enterOpening}}». Голова вносить його один раз: суму й дату «{{ui:cashbook.openingForm.date}}» (залишок на початок цього дня) і натискає «{{ui:cashbook.openingForm.save}}». Після збереження змінити його не можна: помилку виправляє перерахунок каси (для готівки) або банківська різниця (для банку). Казначей без права затвердження бачить підказку, але не кнопку.
- **Останній перерахунок** каси: дата й результат («{{ui:cashbook.count.lastCountMatches}}» або «{{ui:cashbook.count.lastCountDifference}}»). Посилання веде до [перерахунку](help:cash-count). Якщо різниця ще не оформлена, видно попередження «{{ui:cashbook.count.unresolved}}».
- **Місяці звірки з банком** (лише в картці банку): список місяців з позначкою «{{ui:cashbook.bank.status.NOT_RECONCILED}}», «{{ui:cashbook.bank.status.RECONCILED}}» або «{{ui:cashbook.bank.status.NEEDS_RECHECK}}». Натисніть місяць, щоб відкрити [звірку з банком](help:bank-reconciliation).
- **Попередження.** Якщо каса пішла в мінус, стоїть «{{ui:cashbook.negative.CASH}}». Для банку це «{{ui:cashbook.negative.BANK}}»: овердрафт — справа банку, система лише попереджає.

Доки вхідного залишку немає, витрати, здача в банк, інший прихід, перерахунки й звірки для цього рахунку відхиляються з повідомленням «{{ui:problem.OPENING_BALANCE_MISSING}}». Платежі членів записуються й тоді. Платежі за дати до вхідного залишку лишаються в журналі з позначкою «{{ui:cashbook.movements.beforeOpening}}» і в залишок не входять.

Залишок на день — це вхідний залишок плюс усі надходження й мінус усі витрати від дня вхідного залишку до цього дня включно.

### Журнал руху коштів

Під кожною карткою стоїть журнал «{{ui:cashbook.movements.title}}». Фільтри «{{ui:cashbook.movements.from}}» і «{{ui:cashbook.movements.to}}» задають період; без них показано поточний місяць до сьогодні. Над таблицею видно «{{ui:cashbook.movements.openingBalance}}» і «{{ui:cashbook.movements.closingBalance}}».

Колонки: {{ui:cashbook.movements.columns.date}}, {{ui:cashbook.movements.columns.kind}}, {{ui:cashbook.movements.columns.details}} (номер квитанції, документа, стаття, причина), {{ui:cashbook.movements.columns.amount}}, {{ui:cashbook.movements.columns.createdBy}} і {{ui:cashbook.movements.columns.actions}}.

Види записів: «{{ui:cashbook.kind.MEMBER_PAYMENT}}», «{{ui:cashbook.kind.EXPENSE}}», «{{ui:cashbook.kind.HANDOVER_OUT}}», «{{ui:cashbook.kind.OTHER_INCOME}}», «{{ui:cashbook.kind.COUNT_DIFFERENCE}}» і «{{ui:cashbook.kind.BANK_DIFFERENCE}}». Платіж члена з’являється тут сам, у момент збереження платежу. Сторнований запис і його сторнування лишаються в журналі обидва: сторнування має позначку «{{ui:cashbook.movements.reversal}}», а сторнований запис — «{{ui:payments.reversed}}».

У колонці дій:

- біля витрати стоїть посилання «{{ui:cashbook.reverse.expense}}»: витрату сторнують на її картці;
- кнопка «{{ui:cashbook.reverse.action}}» (лише з правом затвердження) є біля здачі в банк, іншого приходу, різниці перерахунку й банківської різниці;
- платіж члена сторнують на [картці платежу](help:payment-card), а не тут. У журналі його сторнування з’явиться само.

Сторнування — це новий рядок протилежної суми з датою того дня, коли ви сторнуєте. Сам запис не видаляється. Запис сторнується лише раз, а потрібна причина (до 500 символів, інакше «{{ui:cashbook.reverse.handover.required}}»). Сторнування приходу готівки, після якого каса пішла б у мінус, відхиляється.

> [!important]
> Запис із датою в уже закритому обліковому місяці лишається зі своєю датою, але враховується в найближчому відкритому місяці, як і платежі. Звіти за днями беруть дату запису, звіти за обліковими місяцями — місяць обліку.

### Вікно «{{ui:cashbook.handover.title}}»

Кнопка «{{ui:cashbook.handover.action}}». Готівка йде з каси на банківський рахунок, і в журналах з’являються два рядки: витрата в касі та надходження в банку тієї самої дати й суми.

| Поле | Що вказати |
|---|---|
| {{ui:cashbook.handover.date}} | Не пізніша за сьогодні: інакше «{{ui:cashbook.handover.dateFuture}}». |
| {{ui:cashbook.handover.amount}} | Більша за 0,00: інакше «{{ui:cashbook.handover.amountInvalid}}». |
| {{ui:cashbook.handover.documentNumber}} | Обов’язково, наприклад «КВ-123» (помилка: «{{ui:cashbook.handover.documentRequired}}»). |

Кнопка «{{ui:cashbook.handover.save}}» записує здачу, «{{ui:cashbook.handover.cancel}}» закриває вікно. Здача, після якої каса пішла б у мінус, відхиляється повідомленням «{{ui:problem.CASH_INSUFFICIENT}}».

### Вікно «{{ui:cashbook.otherIncome.title}}»

Надходження не від членів: відсотки банку, грант, продаж металобрухту. Поля: «{{ui:cashbook.otherIncome.account}}», «{{ui:cashbook.otherIncome.date}}», «{{ui:cashbook.otherIncome.amount}}», «{{ui:cashbook.otherIncome.category}}» і «{{ui:cashbook.otherIncome.description}}» (до 500 символів). Кнопка «{{ui:cashbook.otherIncome.save}}». Якщо список статей порожній, вікно пише «{{ui:cashbook.otherIncome.noCategories}}»: статті додає голова в [налаштуваннях](help:settings).

## Приклад

Припустімо, що в касі 9 000,00 ₴, а на банківському рахунку 48 200,00 ₴ (такі вхідні залишки в демо-кооперативі).

1. Казначей вносить готівкою платіж 500,00 ₴. У журналі каси з’являється рядок «{{ui:cashbook.kind.MEMBER_PAYMENT}}» на +500,00, і залишок каси стає 9 500,00 ₴.
2. Він натискає «{{ui:cashbook.handover.action}}», вказує 3 000,00 і номер «КВ-123». У касі виходить рядок −3 000,00, у банку +3 000,00. Каса: 6 500,00 ₴, банк: 51 200,00 ₴.
3. Він помилився на цифру. Голова натискає «{{ui:cashbook.reverse.action}}» біля здачі й пише причину. Обидва рядки сторновано, залишки повернулися до 9 500,00 ₴ і 48 200,00 ₴.

## Знімки екрана

![Каса і банк: дві картки із залишками та журнали](shot:cashbook)

![Каса і банк на телефоні](shot:cashbook-phone)

## Пов’язані сторінки

- [Витрати](help:expenses)
- [Перерахунок каси](help:cash-count)
- [Звірка з банком](help:bank-reconciliation)
- [Платежі](help:payments)
- [Звіти каси](help:reports)
- [Як система рахує касу, банк і звірку](help:how-cash-and-bank)
