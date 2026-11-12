---
id: profile
title: "Мої дані"
description: "Особисті дані члена кооперативу, спосіб отримання рахунків, Telegram, сповіщення на пристрої і, для правління, ролі та права."
kind: screen
routes:
  - /account/profile
permissions: []
section: member
order: 15
shots:
  - name: profile-page
    user: member
    path: /account/profile
  - name: profile-phone
    user: member
    path: /account/profile
    viewport: phone
---

## Для чого цей екран

{{ui:account.details}} збирає те, що стосується вас особисто, але чим ви користуєтеся нечасто: контакти, спосіб отримання рахунків, Telegram, сповіщення на цьому пристрої та підказка, як встановити Наділ на телефон. Щоденні речі (борг, рахунки, лічильники) лишаються в [кабінеті](help:account).

## Як відкрити

Натисніть своє ім’я в меню (на телефоні воно першим рядком у відкритому меню) або посилання «{{ui:account.details}}» угорі кабінету. Посилання «{{ui:account.backToAccount}}» повертає в кабінет.

## Хто бачить

Кожен, хто увійшов у систему, і лише свої дані. Блок із ролями й правами бачать лише учасники правління, тобто ті, хто має хоча б одне право. Звичайному члену кооперативу цього блока немає.

## Елементи екрана

- Картка {{ui:account.details}}: ім’я, телефони, пошта, поштова адреса, бажаний канал для рахунків, чи підключено Telegram, ролі. Змінити дані може лише правління: напишіть або скажіть йому.
- Блок {{ui:notifications.title}}: у полі «{{ui:notifications.channelLegend}}» оберіть електронну пошту, Telegram або папір і натисніть {{ui:notifications.save}}. Нижче, якщо у кооперативі є бот, кнопка {{ui:telegramLink.connect}} (покроково: [Як підключити Telegram](help:member-telegram)) і перемикач «{{ui:notifications.push.title}}» (докладно: [Сповіщення на телефоні](help:member-push)).
- Посилання «{{ui:pwa.howToInstall}}» під блоком сповіщень веде до [інструкції з встановлення](help:member-install). Його немає, якщо Наділ уже встановлено як застосунок.
- Для правління: блоки {{ui:account.roles}} з ім’ям кооперативу ({{ui:account.cooperative}}) і {{ui:account.permissions}} з коротким поясненням кожного права.

## Приклад

Олена відкрила кабінет на телефоні й хоче, щоб рахунки приходили в Telegram.

1. Вона відкриває меню й натискає своє ім’я.
2. На екрані «{{ui:account.details}}» у блоці «{{ui:notifications.title}}» натискає {{ui:telegramLink.connect}} і відкриває бота.
3. Повернувшись, вона обирає Telegram у полі «{{ui:notifications.channelLegend}}» й натискає {{ui:notifications.save}}. Система відповідає «{{ui:notifications.saved}}».

## Знімки екрана

![Мої дані](shot:profile-page)

![Мої дані на телефоні](shot:profile-phone)

## Пов’язані сторінки

- [Мій кабінет](help:account)
- [Як підключити Telegram](help:member-telegram)
- [Сповіщення на телефоні](help:member-push)
- [Встановити на телефон](help:member-install)
