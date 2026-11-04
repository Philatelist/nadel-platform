---
id: expenses
title: "Витрати"
description: "Список витрат кооперативу з фільтрами за періодом, рахунком і статтею та позначкою про прикладені файли чеків."
kind: screen
routes:
  - /cashbook/expenses
permissions:
  - CASHBOOK_VIEW
section: payments-cash
order: 50
shots:
  - name: expenses
    user: chairman
    path: /cashbook/expenses
---

## Для чого цей екран

Тут перелічено всі витрати кооперативу: за що й кому заплачено, з якого рахунку, на яку суму. Звідси відкривають картку витрати з чеками й вносять нову витрату.

## Хто бачить

Список бачать ті, хто має право «{{ui:permission.CASHBOOK_VIEW.name}}»: голова й казначей. Кнопку «{{ui:expenses.add}}» бачить той, хто має ще й «{{ui:permission.CASHBOOK_ENTER.name}}». Член кооперативу цього екрана не бачить.

## Елементи екрана

### Кнопки в заголовку

- «{{ui:expenses.back}}» повертає на екран [Каса і банк](help:cashbook).
- «{{ui:expenses.add}}» відкриває [форму нової витрати](help:expense-form).

### Фільтри

| Фільтр | Що робить |
|---|---|
| {{ui:expenses.list.from}} і {{ui:expenses.list.to}} | Показують витрати за датою витрати. |
| {{ui:expenses.list.account}} | «{{ui:cashbook.account.CASH}}», «{{ui:cashbook.account.BANK}}» або «{{ui:expenses.list.all}}». |
| {{ui:expenses.list.category}} | Одна стаття витрат або «{{ui:expenses.list.all}}». |

Коли за цими умовами витрат немає, екран пише «{{ui:expenses.list.empty}}».

### Таблиця

Колонки: {{ui:expenses.list.columns.date}}, {{ui:expenses.list.columns.account}}, {{ui:expenses.list.columns.category}}, {{ui:expenses.list.columns.counterparty}}, {{ui:expenses.list.columns.amount}} і {{ui:expenses.list.columns.files}} (скільки чеків прикладено). Дата — це посилання на [картку витрати](help:expense-card). Сторновану витрату позначено «{{ui:expenses.list.reversed}}».

Витрату не редагують: помилку виправляє сторнування на картці.

Поле «{{ui:expenses.list.columns.counterparty}}» може містити ім’я приватної особи, тому це персональні дані: їх бачать лише ті, хто має право «{{ui:permission.CASHBOOK_VIEW.name}}».

## Приклад

Демо-кооператив має витрати в чотирьох статтях: «Електроенергія постачальнику» 12 480,00 ₴ з банку, «Матеріали» 860,00 ₴ готівкою (кабель і клеми для ліхтаря, прикладено фото чека), «Вивіз сміття» 1 200,00 ₴ і «Канцелярія та зв’язок» 145,50 ₴.

1. Відкрийте «Витрати».
2. У фільтрі «{{ui:expenses.list.account}}» оберіть «{{ui:cashbook.account.CASH}}»: лишаться три готівкові витрати.
3. Натисніть дату витрати на 860,00 ₴: відкриється картка з фото чека.

## Знімки екрана

![Список витрат з фільтрами](shot:expenses)

## Пов’язані сторінки

- [Внести витрату](help:expense-form)
- [Картка витрати](help:expense-card)
- [Каса і банк](help:cashbook)
- [Звіти каси](help:reports)
