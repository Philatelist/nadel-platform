---
id: audit
title: "Журнал змін"
description: "Хто, коли й що змінив у системі: фільтри за видом запису та датою, старі й нові значення та приховані персональні дані."
kind: screen
routes:
  - /audit
permissions:
  - AUDIT_VIEW
section: settings
order: 30
shots:
  - name: audit
    user: chairman
    path: /audit
---

## Для чого цей екран

Кожна дія правління записується в журнал: хто, коли й що змінив, зі старими й новими значеннями. Журнал допомагає з’ясувати, звідки взялася зміна, і не покладатися на пам’ять. Записи журналу не змінюються й не видаляються. Дані, внесені скриптами демонстрації, у журналі не показуються: лише дії людей у системі.

## Хто бачить

Журнал читає той, хто має дозвіл «{{ui:permission.AUDIT_VIEW.name}}»: голова й казначей. Старі й нові значення телефонів, пошти, адреси, Telegram, Viber і приміток члена бачить лише той, хто має ще й «{{ui:permission.MEMBERS_VIEW.name}}». Решта бачить, що поле змінилося, а замість значень стоїть слово «{{ui:audit.hiddenValue}}». Інші поля, наприклад ім’я, показано як завжди. Пароль пошти й токен бота Telegram не показує ніхто: у журналі стоїть лише «{{ui:audit.secretChanged}}».

## Елементи екрана

### Фільтри

- «{{ui:audit.filters.entityType}}»: «{{ui:audit.filters.all}}» або один вид запису: {{ui:audit.entityType.MEMBER}}, {{ui:audit.entityType.ROLE}}, {{ui:audit.entityType.PLOT}}, {{ui:audit.entityType.SETTINGS}}, {{ui:audit.entityType.OWNERSHIP}}, {{ui:audit.entityType.CHARGE_TYPE}}, {{ui:audit.entityType.TARIFF}}, {{ui:audit.entityType.METER}}, {{ui:audit.entityType.METER_READING}}, {{ui:audit.entityType.CHARGE}}, {{ui:audit.entityType.LOSSES_RUN}}, {{ui:audit.entityType.COMMON_CONSUMER_RUN}}, {{ui:audit.entityType.RECALCULATION}} і {{ui:audit.entityType.PAYMENT}}.
- «{{ui:audit.filters.from}}» і «{{ui:audit.filters.to}}»: межі за датою. Якщо дата неправильна, система покаже про це біля поля.
- Позначки «{{ui:audit.filters.onlyRecord}}» й «{{ui:audit.filters.onlyAuthor}}» з’являються, коли журнал відфільтровано по одному запису або одному авторові. Хрестик на позначці («{{ui:audit.filters.remove}}») скасовує такий фільтр. Відфільтрований журнал по одному члену відкриває кнопка «{{ui:members.history}}» на [картці члена](help:member-card).

Фільтри зберігаються в адресі сторінки, тож посиланням на відфільтрований журнал можна поділитися. Коли записів ще немає, написано: «{{ui:audit.empty}}», а коли фільтр нічого не знайшов: «{{ui:audit.nothingFound}}». На сторінці п’ятдесят записів, внизу є перемикач сторінок.

### Колонки

| Колонка | Що в ній |
|---|---|
| {{ui:audit.columns.time}} | Дата й година дії. |
| {{ui:audit.columns.author}} | Хто виконав дію. Натиснення на ім’я залишає в журналі лише записи цього автора. Дії, які виконала сама система, підписано «{{ui:audit.system}}». |
| {{ui:audit.columns.action}} | Що зроблено, наприклад «{{ui:audit.action.OWNERSHIP_CHANGED}}», «{{ui:audit.action.PLOT_UPDATED}}», «{{ui:audit.action.PAYMENT_REVERSED}}» або «{{ui:audit.action.SETTINGS_UPDATED}}». |
| {{ui:audit.columns.record}} | Вид запису й сам запис. Для членів, ділянок, тарифів, лічильників, перерахунків та інших це посилання на їхню картку. Якщо запис видалено, написано «{{ui:audit.unlabelled}}». |
| {{ui:audit.columns.changes}} | Старе й нове значення кожного змінного поля через стрілку. Показано лише поля, які справді змінилися. Для ролі видно додані («+») і прибрані («−») дозволи. |

Журнал фіксує майже всі дії, які змінюють дані: додавання й зміну членів, ролей, ділянок, власників, налаштувань, імпорт, види нарахувань і тарифи, лічильники й покази, нарахування, сторно, закриття місяця, перерахунки, платежі, звіти, рахунки, довідки, розсилку, касу й банк.

## Приклад

Голова хоче дізнатися, хто міняв власника ділянки 9.

1. Відкрийте «{{ui:nav.audit}}» і в «{{ui:audit.filters.entityType}}» оберіть «{{ui:audit.entityType.OWNERSHIP}}».
2. Знайдіть запис з дією «{{ui:audit.action.OWNERSHIP_CHANGED}}», записом «Ділянка 9» і змінами: власників, дату «{{ui:audit.fields.effectiveFrom}}», підставу.
3. Натисніть на ім’я автора: у журналі лишаться тільки його дії.
4. Натисніть на «Ділянка 9» у колонці запису, щоб відкрити [картку ділянки](help:plot-card).

Нехай голова створив власну роль, яка читає журнал, але не має дозволу «{{ui:permission.MEMBERS_VIEW.name}}». Така людина побачить, що в члена змінилося поле «{{ui:member.fields.phones}}», але замість номерів прочитає «{{ui:audit.hiddenValue}}». Голова бачить і старий, і новий номер.

## Знімки екрана

![Журнал змін із фільтрами й записами](shot:audit)

## Пов’язані сторінки

- [Картка члена](help:member-card)
- [Картка ділянки](help:plot-card)
- [Ролі](help:roles)
- [Налаштування кооперативу](help:settings)
