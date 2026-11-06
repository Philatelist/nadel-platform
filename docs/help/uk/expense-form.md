---
id: expense-form
title: "Внести витрату"
description: "Як записати витрату кооперативу: рахунок, сума, стаття, кому сплачено, фото чека з телефона, документи та що робити з фото HEIC з iPhone."
kind: screen
routes:
  - /cashbook/expenses/new
permissions:
  - CASHBOOK_ENTER
section: payments-cash
order: 60
shots:
  - name: expense-form
    user: chairman
    path: /cashbook/expenses/new
---

## Для чого цей екран

Тут записують гроші, які кооператив витратив: оплату електроенергії постачальнику, матеріали, вивіз сміття. До витрати можна прикласти фото чека або рахунок. Після збереження витрата зменшує залишок каси чи банку.

## Хто бачить

Форму бачать ті, хто має право «{{ui:permission.CASHBOOK_ENTER.name}}»: казначей і голова. Після збереження витрату не редагують. Сторнувати її може лише той, хто має «{{ui:permission.CASHBOOK_APPROVE.name}}» (голова), на [картці витрати](help:expense-card).

## Елементи екрана

Форму відкривають кнопкою «{{ui:expenses.add}}» на екранах [Каса і банк](help:cashbook) і [Витрати](help:expenses). Заголовок — «{{ui:expenses.form.title}}». Якщо ви прийшли зі сторнованої витрати, угорі стоїть «{{ui:expenses.form.prefilled}}», а поля й файли вже заповнені.

### Поля

| Поле | Що вказати |
|---|---|
| {{ui:expenses.form.account}} | «{{ui:cashbook.account.CASH}}» або «{{ui:cashbook.account.BANK}}». |
| {{ui:expenses.form.date}} | Не пізніша за сьогодні: інакше «{{ui:expenses.form.dateFuture}}». |
| {{ui:expenses.form.amount}} | Більша за 0,00: інакше «{{ui:expenses.form.amountInvalid}}». |
| {{ui:expenses.form.category}} | Обов’язково, зі списку статей кооперативу. Якщо список порожній, форма пише «{{ui:expenses.form.noCategories}}»: статті додає голова в [налаштуваннях](help:settings). Інакше «{{ui:expenses.form.categoryRequired}}». |
| {{ui:expenses.form.counterparty}} | Необов’язково, до 200 символів. |
| {{ui:expenses.form.description}} | Необов’язково, до 500 символів. |
| {{ui:expenses.form.documentNumber}} і {{ui:expenses.form.documentDate}} | Необов’язково: номер і дата рахунка чи чека постачальника. |
| {{ui:expenses.form.attachments}} | Від 0 до 5 файлів (докладніше нижче). |

Поля «{{ui:expenses.form.counterparty}}» і «{{ui:expenses.form.description}}» можуть містити ім’я приватної особи, тому це персональні дані. Вони зберігаються захищено й видимі лише тим, хто має право «{{ui:permission.CASHBOOK_VIEW.name}}».

Кнопка «{{ui:expenses.form.submit}}» записує витрату.

### Файли: чеки й рахунки

Підсказка під кнопками: «{{ui:expenses.attachments.hint}}».

- «{{ui:expenses.attachments.pick}}» додає файл з пристрою.
- «{{ui:expenses.attachments.camera}}» (на телефоні) відкриває камеру: знімок іде у витрату як вкладення.
- Файли, додані до збереження, видно мініатюрами. Кнопка «{{ui:expenses.attachments.remove}}» прибирає файл. Той самий файл (з тими самими назвою, розміром і вмістом) двічі додати не можна: форма пише «{{ui:expenses.attachments.duplicate}}».
- З фото під час завантаження видаляються дані місця зйомки й камери (GPS). Тип файлу система визначає за вмістом, а не за назвою.
- Повідомлення про помилки: «{{ui:expenses.attachments.tooLarge}}», «{{ui:expenses.attachments.tooMany}}» і «{{ui:expenses.attachments.typeNotAllowed}}». Текстовий файл, перейменований на receipt.jpg, буде відхилено.

> [!important]
> Фото HEIC з iPhone приймаються лише в Safari. Під кнопками про це нагадує підказка «{{ui:expenses.attachments.heicHint}}». Якщо інший браузер не відкриває такий файл, форма пише: «{{ui:expenses.attachments.heicFailed}}» Тобто додайте фото через Safari або один раз змініть налаштування телефона: «Налаштування → Камера → Формати → Найсумісніший». Після цього iPhone знімає в JPEG, і фото відкриється де завгодно. Поки фото перетворюється, видно «{{ui:expenses.attachments.converting}}».

### Збереження й помилки

Для готівкової витрати в касі має вистачати грошей, а в рахунка має бути вхідний залишок. Якщо не так, форма покаже одне з повідомлень.

| Що показано | Що робити |
|---|---|
| {{ui:problem.CASH_INSUFFICIENT}} | Менша сума або спершу внесіть гроші в касу. Готівкова витрата без грошей у касі не записується. |
| {{ui:problem.OPENING_BALANCE_MISSING}} | Голова вносить вхідний залишок на екрані [Каса і банк](help:cashbook). |
| {{ui:problem.BEFORE_OPENING_BALANCE}} | Вкажіть дату не раніше за вхідний залишок. |

## Приклад

Казначей купив у магазині кабель і клеми для ремонту ліхтаря і заплатив готівкою.

1. На екрані «Каса і банк» натисніть «{{ui:expenses.add}}».
2. Рахунок: «{{ui:cashbook.account.CASH}}», сума 860,00, стаття «Матеріали». Кому сплачено: Будмаркет «Демо». Призначення: «Кабель і клеми для ремонту ліхтаря». Номер документа: ЧК-1187.
3. Натисніть «{{ui:expenses.attachments.camera}}» і сфотографуйте чек.
4. Натисніть «{{ui:expenses.form.submit}}». Залишок каси зменшився на 860,00 ₴, а картка витрати має фото чека.

## Знімки екрана

![Форма нової витрати](shot:expense-form)

## Пов’язані сторінки

- [Витрати](help:expenses)
- [Картка витрати](help:expense-card)
- [Каса і банк](help:cashbook)
- [Налаштування: статті витрат і доходів](help:settings)
