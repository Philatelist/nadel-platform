---
id: member-card
title: "Картка члена"
description: "Контакти, ролі, ділянки у власності й примітки одного члена кооперативу; редагування, ролі, архівування й історія змін."
kind: screen
routes:
  - /members/:id
permissions:
  - MEMBERS_VIEW
section: plots-members
order: 60
shots:
  - name: member-card
    user: chairman
    path: /members
    open: { css: 'main a[href^="/members/"]:not([href$="/new"])' }
  - name: member-card-roles
    user: chairman
    path: /members
    open: { css: 'main a[href^="/members/"]:not([href$="/new"])' }
    steps:
      - { click: Змінити ролі }
  - name: member-card-access
    user: chairman
    path: /members
    open: { css: 'main a[href^="/members/"]:not([href$="/new"])' }
    element: { heading: Доступ до системи }
---

## Для чого цей екран

Картка показує одну людину: контакти, спосіб доставки рахунків, ролі, ділянки, якими вона володіє або володіла, і службові примітки. Тут члена редагують, призначають йому ролі й архівують.

## Хто бачить

Картку бачить той, хто має дозвіл «{{ui:permission.MEMBERS_VIEW.name}}». Кнопки залежать від дозволів:

- «{{ui:memberForm.edit}}» і «{{ui:memberForm.archive}}» або «{{ui:memberForm.restore}}»: «{{ui:permission.MEMBERS_EDIT.name}}»;
- «{{ui:rolesPage.memberRoles}}»: «{{ui:permission.ROLES_MANAGE.name}}»;
- «{{ui:members.history}}»: «{{ui:permission.AUDIT_VIEW.name}}»;
- кнопки блоку «{{ui:memberAccess.title}}»: «{{ui:permission.MEMBERS_INVITE.name}}».

Примітки бачать лише ті, хто має «{{ui:permission.MEMBERS_VIEW.name}}». Блок «{{ui:memberAccess.title}}» бачать ті, хто має «{{ui:permission.MEMBERS_VIEW.name}}»; сам член його на своїй картці не бачить. Свої дані кожен член бачить у «{{ui:nav.account}}».

## Елементи екрана

Угорі посилання «{{ui:members.back}}», далі ім’я члена. Для архівного члена поруч стоїть «{{ui:members.archived}}».

### Кнопки в заголовку

- **{{ui:members.history}}** відкриває [журнал змін](help:audit), відфільтрований по цьому члену.
- **{{ui:rolesPage.memberRoles}}** замінює картку на перелік ролей з позначками. Позначте потрібні й натисніть {{ui:rolesPage.save}}, або {{ui:rolesPage.cancel}}. Дозволи складаються: коли ролей кілька, людина має дозволи всіх.
- **{{ui:memberForm.edit}}** відкриває [форму](help:member-form) з поточними даними. Після збереження система пише «{{ui:memberForm.saved}}».
- **{{ui:memberForm.archive}}** прибирає члена зі списку, але зберігає всю історію. Система питає: «{{ui:memberForm.archiveTitle}}», і пояснює: «{{ui:memberForm.archiveText}}» Кнопка {{ui:memberForm.restore}} повертає його: «{{ui:memberForm.restoreTitle}}», «{{ui:memberForm.restoreText}}»

> [!important]
> Члена не видаляють, а архівують. Архівувати власника ділянки не можна, доки ділянка не перейде іншому власнику: система скаже «{{ui:problem.MEMBER_OWNS_PLOT}}». Останнього голову архівувати або зняти з нього роль не можна: «{{ui:problem.LAST_CHAIRMAN}}».

### Дані члена

Таблиця показує: {{ui:member.fields.name}}, {{ui:member.fields.phones}}, {{ui:member.fields.email}}, {{ui:member.fields.postalAddress}}, {{ui:member.fields.deliveryChannel}}, нікнейм у Telegram і телефон у Viber (коли вони є), стан Telegram, {{ui:member.fields.notes}} і {{ui:member.fields.roles}}. Для Telegram картка показує лише «{{ui:telegramLink.memberLinked}}» або «{{ui:telegramLink.memberNotLinked}}», без даних облікового запису самого члена. Підключення й відключення потрапляють у журнал змін.

### Блок «{{ui:memberAccess.title}}»

Показує, чи може людина входити в систему. Є чотири стани:

- «{{ui:memberAccess.status.NOT_INVITED}}»: запрошення ще не було;
- «Запрошено <дата>, чекає на пароль»: лист надіслано, людина ще не задала пароль;
- «Має доступ, останній вхід <дата>» або «Має доступ, ще не входив»: пароль задано;
- «{{ui:memberAccess.status.DISABLED}}»: вхід вимкнено.

Кнопки бачить той, хто має дозвіл «{{ui:permission.MEMBERS_INVITE.name}}»:

- **{{ui:memberAccess.invite}}** надсилає лист «{{ui:setPassword.title}}» на e-mail з картки: у ньому посилання на [сторінку задання пароля](help:set-password). Лист іде з поштової скриньки кооперативу, посилання діє 72 години й спрацьовує один раз. Якщо член підключив Telegram, посилання приходить також від бота. Система пише: «{{ui:memberAccess.invited}}»
- **{{ui:memberAccess.resend}}** надсилає нове посилання; попереднє перестає діяти. Система пише: «{{ui:memberAccess.resent}}»
- **{{ui:memberAccess.disable}}** забирає вхід, але картка й дані лишаються. Система питає підтвердження: «{{ui:memberAccess.disableTitle}}». Потім людину можна запросити знову.

Якщо кнопка недоступна, поруч написано причину: «{{ui:memberAccess.blocked.EMAIL_MISSING}}» (e-mail є логіном) або «{{ui:memberAccess.blocked.MAILBOX_NOT_CONFIGURED}}» (див. [налаштування кооперативу](help:settings)). Коли лист не вдалося надіслати, система пише, що запрошення не надіслано, і пропонує спробувати ще раз.

Інші правила:

- e-mail, який уже використовує член іншого кооперативу, система відхиляє;
- архівування члена вимикає його доступ, а відновлення доступ не вмикає: запросіть людину знову;
- власний доступ вимкнути не можна: на своїй картці кнопки «{{ui:memberAccess.disable}}» немає;
- доступ останнього голови, який має доступ, вимкнути не можна, і архівувати такого голову теж: система пише «{{ui:memberAccess.lastChairman}}»;
- коли змінюють e-mail члена, який має доступ, нове посилання надсилається на нову адресу.

Запрошення, повторне надсилання й вимкнення потрапляють у [журнал змін](help:audit).

### Панель «{{ui:ownership.plotsTitle}}»

Показує два списки: «{{ui:ownership.ownsNow}}» (ділянка, частка, дата з якої) і «{{ui:ownership.ownedBefore}}» (ділянка й період). Номер ділянки веде на [картку ділянки](help:plot-card), якщо ви можете переглядати ділянки. Якщо людина нічим не володіє, написано: «{{ui:ownership.noPlotsOwned}}» або «{{ui:ownership.noPlotsBefore}}».

## Приклад

Голова відкриває картку члена Бондаренка Андрія Миколайовича.

1. У «{{ui:ownership.ownsNow}}» видно дві ділянки: 7 (частка 1/2 з 15.04.2019) і 15 (уся ділянка з 25.03.2021).
2. У полі «{{ui:member.fields.roles}}» стоїть лише «{{ui:roles.MEMBER}}». Голова натискає {{ui:rolesPage.memberRoles}}, позначає ще одну роль і зберігає: дозволи ролей додаються.
3. Голова пробує натиснути {{ui:memberForm.archive}}, але система відмовляє: Андрій є власником ділянок. Спершу на [картці ділянки](help:plot-card) змінюють власників, потім члена можна архівувати.

Інший приклад: Поліщук Оксана, яка керує списком членів, має, крім базової ролі, власну роль «Секретар» із дозволами на перегляд і редагування членів та ділянок і власності. У її картці в полі «{{ui:member.fields.roles}}» видно обидві ролі.

## Знімки екрана

![Картка члена: контакти, ролі, ділянки](shot:member-card)

![Вибір ролей на картці члена](shot:member-card-roles)

![Блок доступу до системи на картці члена](shot:member-card-access)

## Пов’язані сторінки

- [Члени кооперативу](help:members)
- [Новий член і редагування](help:member-form)
- [Ролі](help:roles)
- [Картка ділянки](help:plot-card)
- [Журнал змін](help:audit)
- [Задайте пароль](help:set-password)
