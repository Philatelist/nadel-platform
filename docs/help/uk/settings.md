---
id: settings
title: "Налаштування кооперативу"
description: "Усі налаштування кооперативу: кольори ділянок, поріг боргу, втрати, аванс, спільні споживачі, реквізити, пошта й Telegram, вода, статті витрат."
kind: screen
routes:
  - /settings
permissions:
  - SETTINGS_MANAGE
  - METERS_MANAGE
section: settings
order: 10
shots:
  - name: settings
    user: chairman
    path: /settings
  - name: settings-thresholds
    user: chairman
    path: /settings
    element: { heading: Поріг червоного статусу }
  - name: settings-delivery
    user: chairman
    path: /settings
    element: { heading: Розсилка }
---

## Для чого цей екран

Тут голова налаштовує правила, за якими працює кооператив у системі: хто бачить кольори ділянок, коли ділянка червона, як нараховуються втрати та спільні споживачі, чи йде аванс на нові нарахування сам, які реквізити друкуються в документах, з якої скриньки й через якого бота йдуть листи, яка вода в кого підключена, за якими статтями ведеться каса. Кожна панель зберігається окремо своєю кнопкою.

## Хто бачить

Екран показує ті панелі, на які у вас є дозвіл.

| Панель | Дозвіл |
|---|---|
| Кольори ділянок, поріг, втрати, платежі, спільні споживачі, реквізити, розсилка | «{{ui:permission.SETTINGS_MANAGE.name}}» |
| «{{ui:water.sourcesTitle}}» | «{{ui:permission.PLOTS_VIEW.name}}»; кнопки в ній потребують «{{ui:permission.METERS_MANAGE.name}}» |
| «{{ui:expenseCategories.title}}» | «{{ui:permission.CASHBOOK_APPROVE.name}}» |
| Посилання «{{ui:settings.meterTreeLink}}» | «{{ui:permission.METERS_MANAGE.name}}» |

За замовчуванням усе це має голова. Казначей екран налаштувань не бачить. Пункт меню «{{ui:nav.settings}}» з’являється, якщо є дозвіл на налаштування або на керування лічильниками.

Кожна зміна налаштувань записується в [журнал змін](help:audit). Якщо збереження вдалося, над кнопкою з’являється «{{ui:settings.saved}}».

## Елементи екрана

### Хто бачить кольори ділянок

Панель із вибором «{{ui:settings.visibilityLegend}}».

- «{{ui:settings.visibility.OWN_ONLY.label}}» (так налаштовано за замовчуванням). {{ui:settings.visibility.OWN_ONLY.description}}
- «{{ui:settings.visibility.ALL_PLOTS.label}}». {{ui:settings.visibility.ALL_PLOTS.description}}

Кнопка {{ui:settings.save}} активна, лише коли вибір змінено.

> [!important]
> Сусіди знають, чия ділянка, тож колір фактично показує боржника. Вмикайте «{{ui:settings.visibility.ALL_PLOTS.label}}» лише за рішенням загальних зборів. Під вибором система нагадує: «{{ui:settings.visibilityHint}}». Суми, власників і персональні дані чужих ділянок член кооперативу не бачить за жодного налаштування.

Налаштування стосується мапи ділянок, яка з’явиться пізніше. Сьогодні члени кооперативу не бачать списку ділянок, тож чужих ділянок не бачать зовсім, хоч би що тут було обрано.

### Панель «{{ui:settings.threshold.title}}»

Ділянка стає червоною, коли борг сягає певної суми або найстаріший борг триває певну кількість днів. Будь-який інший борг жовтий. Поля:

- «{{ui:settings.threshold.redAmount}}». Сума з копійками, наприклад 1500,00.
- «{{ui:settings.threshold.redDays}}». Ціле число днів.

Кнопка {{ui:settings.threshold.save}}. Помилки: «{{ui:settings.threshold.amountInvalid}}» і «{{ui:settings.threshold.daysInvalid}}». Нова умова одразу діє на кольори ділянок у списку й на карті.

### Панель «{{ui:settings.losses.title}}»

Втрати вузла дерева лічильників (див. [Дерево лічильників](help:meter-tree)). Є два режими в полі «{{ui:settings.losses.mode}}»:

- «{{ui:settings.losses.FIXED_PERCENT}}»: відсоток із поля «{{ui:settings.losses.percent}}» додається до нарахування за споживання кожної ділянки;
- «{{ui:settings.losses.PROPORTIONAL}}»: розрахунок втрат вузла сам ділить втрати між ділянками вузла. Відсоток у цьому режимі не діє, поле вимкнене, а система пояснює: «{{ui:settings.losses.proportionalHint}}»

Кнопка {{ui:settings.losses.save}}. Відсоток має бути від 0 до 100, наприклад 5 або 5,25, інакше система відповість: «{{ui:settings.losses.percentInvalid}}». Докладніше про розрахунок: [Втрати](help:losses).

### Панель «{{ui:settings.payments.title}}»

Перемикач «{{ui:settings.payments.autoAllocatePrepayment}}» (за замовчуванням увімкнений). {{ui:settings.payments.autoAllocatePrepaymentHint}} Кнопка {{ui:settings.payments.save}}.

> [!important]
> Коли перемикач увімкнено, вільна частина платежу одразу йде на найстаріші борги ділянки і не лишається авансом. Аванс залишається лише тоді, коли в ділянки немає відкритих боргів. Це видно у [формі платежу](help:payments-enter).

### Панель «{{ui:settings.commonConsumers.title}}»

Як споживання кожного спільного лічильника (насос свердловини, вуличне освітлення) розподіляється між ділянками. {{ui:settings.commonConsumers.text}}

- Вибір «{{ui:settings.commonConsumers.defaultLegend}}»: «{{ui:billing.common.split.CONNECTED_PLOTS}}», «{{ui:billing.common.split.EQUAL_PER_PLOT}}», «{{ui:billing.common.split.SERVICE_USERS}}» або «{{ui:billing.common.split.NOT_CHARGED}}». Кнопка {{ui:settings.commonConsumers.saveDefault}}.
- Нижче по кожному спільному лічильнику поле «{{ui:settings.commonConsumers.consumerSplit}}»: залишити за замовчуванням або обрати свій спосіб. Якщо обрано «{{ui:billing.common.split.SERVICE_USERS}}», з’являється список «{{ui:settings.commonConsumers.serviceUsers}}»: додайте ділянки, які користуються цим споживачем, пошуком «{{ui:settings.commonConsumers.pickPlot}}». Кнопка {{ui:settings.commonConsumers.save}}. Якщо лічильників спільних споживачів ще немає, написано: «{{ui:settings.commonConsumers.noConsumers}}».

Докладніше: [Спільні споживачі](help:common-consumers).

### Панель «{{ui:settings.requisites.title}}»

{{ui:settings.requisites.text}} Поля: «{{ui:settings.requisites.officialName}}», «{{ui:settings.requisites.legalAddress}}», «{{ui:settings.requisites.bankName}}», «{{ui:settings.requisites.iban}}». Код «{{ui:settings.requisites.edrpou}}» лише читається. Кнопка {{ui:settings.requisites.save}}. Якщо IBAN неправильний, система пише: «{{ui:settings.requisites.errors.ibanInvalid}}».

Реквізити друкуються в рахунках, довідках і звітах. Поки не заповнено IBAN і код ЄДРПОУ, рахунки сформувати не можна: «{{ui:problem.REQUISITES_MISSING}}».

### Панель «{{ui:deliverySettings.title}}»

{{ui:deliverySettings.text}} Біля кожного каналу стоїть позначка «{{ui:deliverySettings.configured}}» або «{{ui:deliverySettings.notConfigured}}». Доки канал не налаштовано, через нього нічого не надсилається. Кому яким каналом піде рахунок і як читати результат, див. [Розсилка](help:deliveries).

#### Електронна пошта

Найпростіше завести для кооперативу безкоштовну поштову скриньку (Gmail або Ukr.net) і користуватися паролем застосунку: це окремий пароль для програм. Його створюють у налаштуваннях скриньки, і він не відкриває саму скриньку. Звичайний пароль від пошти більшість сервісів для таких програм не приймає.

| Сервіс | Що зробити в скриньці | Сервер | Порт і шифрування | Користувач і пароль |
|---|---|---|---|---|
| Gmail | Увімкнути двоетапну перевірку, потім створити «пароль застосунку» | smtp.gmail.com | 587, STARTTLS | повна адреса скриньки; пароль застосунку з 16 символів |
| Ukr.net | У налаштуваннях скриньки дозволити доступ із поштових програм і створити пароль для них | smtp.ukr.net | 465, SSL | повна адреса скриньки; пароль для програм |

Значення типові: перевірте їх у довідці вашого поштового сервісу, вони можуть змінитися.

Поля: «{{ui:deliverySettings.email.host}}», «{{ui:deliverySettings.email.port}}», «{{ui:deliverySettings.email.security}}» (STARTTLS, SSL або «{{ui:deliverySettings.email.securityNone}}»), «{{ui:deliverySettings.email.user}}», «{{ui:deliverySettings.email.password}}», «{{ui:deliverySettings.email.fromAddress}}» й «{{ui:deliverySettings.email.fromName}}» (наприклад, «СТ Надія»: так члени бачать відправника).

- Кнопка {{ui:deliverySettings.email.save}} записує дані. Пароль після збереження більше не показується: у полі написано «{{ui:deliverySettings.email.passwordSaved}}». Щоб лишити пароль без змін, залишайте поле порожнім. У журналі змін видно лише те, що пароль змінено.
- Кнопка {{ui:deliverySettings.email.sendTest}} надсилає тестовий лист на вашу власну адресу з картки члена (без неї кнопка скаже, що слати нікуди: «{{ui:problem.NO_OWN_EMAIL}}»). Кнопка працює із збереженими даними, тому система нагадує: «{{ui:deliverySettings.email.saveFirst}}» Результат: «{{ui:deliverySettings.email.testAccepted}}» або відмова сервера простими словами, наприклад «{{ui:deliveries.reason.SMTP_AUTH_FAILED}}».

> [!important]
> Gmail приймає від програм лише пароль застосунку, а не звичайний пароль акаунта. Якщо в полі «{{ui:deliverySettings.email.password}}» стоїть звичайний пароль, система скаже: «{{ui:deliverySettings.email.appPassword}}» Спершу ввімкніть двоетапну перевірку в акаунті Google (без неї пароль застосунку створити не можна). Потім створіть пароль на сторінці myaccount.google.com/apppasswords (на екрані є посилання «{{ui:deliverySettings.email.appPasswordLink}}») і вставте його в поле «{{ui:deliverySettings.email.password}}». Налаштування Gmail: сервер smtp.gmail.com, порт 587, шифрування STARTTLS, користувач це повна адреса скриньки, наприклад st.nadiya@gmail.com. Англійська відповідь сервера лежить під «{{ui:deliverySettings.email.details}}», її можна показати тому, хто допомагає з налаштуванням.

Система підключається лише до поштових служб в інтернеті й лише на порти 25, 465 чи 587. Адресу на кшталт localhost чи внутрішню адресу мережі (192.168.…, 10.…) зберегти не вдасться: «{{ui:problem.SMTP_HOST_NOT_ALLOWED}}»

#### Бот Telegram

Бота створює голова в самому Telegram: це безкоштовно й займає хвилину. Над полем токена на екрані є коротка інструкція.

1. У Telegram відкрийте @BotFather (офіційний бот для створення ботів).
2. Надішліть команду /newbot.
3. На запит імені напишіть назву кооперативу, наприклад «Надія — рахунки»: її бачать члени.
4. Оберіть унікальне ім’я користувача, що закінчується на bot, наприклад nadiya_rakhunky_bot.
5. BotFather відповість повідомленням із токеном: довгий рядок на кшталт 123456:ABC…. Скопіюйте його.
6. Вставте токен у поле «{{ui:deliverySettings.telegram.token}}» й натисніть {{ui:deliverySettings.telegram.save}}.
7. Натисніть {{ui:deliverySettings.telegram.check}}. Система запитає Telegram і покаже ім’я бота або «{{ui:deliverySettings.telegram.TOKEN_INVALID}}». Кнопка працює із збереженим токеном, тож спершу збережіть його.

Токен це ключ до бота: нікому його не передавайте. Після збереження екран показує лише останні 4 символи. Якщо токен змінити, бот кооперативу перезапуститься приблизно за хвилину. Якщо Telegram відхиляє токен (бота видалено чи токен перевипущено), бот зупиняється, доки токен не замінять. Коли Telegram недоступний, система пише: «{{ui:deliverySettings.telegram.TELEGRAM_UNAVAILABLE}}» Як член підключає Telegram, описано в розділі [Мій кабінет](help:account).

#### Загальні параметри

- «{{ui:deliverySettings.options.emailRate}}». {{ui:deliverySettings.options.emailRateHint}} Безкоштовні скриньки обмежують кількість листів на добу й блокують за надто швидку розсилку. Тому за замовчуванням стоїть 1: 120 листів підуть за пару хвилин. Збільшуйте число лише для платної пошти. Якщо кооператив більший за денний ліміт, частина листів отримає відмову «{{ui:deliveries.reason.SMTP_REJECTED}}»: надішліть їх ще раз наступного дня.
- Перемикач «{{ui:deliverySettings.options.notifyPayments}}» (за замовчуванням увімкнений). {{ui:deliverySettings.options.notifyPaymentsHint}}
- Кнопка {{ui:deliverySettings.options.save}} зберігає і швидкість, і перемикач.

### Панель «{{ui:water.sourcesTitle}}»

{{ui:water.sourcesText}} Для кожного джерела (водопровід кооперативу або лічильник спільного споживача) видно підключені ділянки з датами. Кнопки {{ui:water.connectPlots}} і {{ui:water.disconnectPlots}} відкривають форму: оберіть дату, знайдіть ділянки пошуком «{{ui:water.pickPlot}}» й натисніть {{ui:water.apply}}. Дія виконується «все або нічого»: якщо хоч одну ділянку не можна змінити, не зміниться жодна. Окрему ділянку підключають на її [картці](help:plot-card).

### Панель «{{ui:expenseCategories.title}}»

{{ui:expenseCategories.text}} Два списки: «{{ui:expenseCategories.expense}}» і «{{ui:expenseCategories.income}}». Біля статті кнопки {{ui:expenseCategories.rename}}, {{ui:expenseCategories.archive}} (або {{ui:expenseCategories.restore}} для архівної), {{ui:expenseCategories.moveUp}} і {{ui:expenseCategories.moveDown}}, внизу списку {{ui:expenseCategories.add}}. У формі впишіть назву й натисніть {{ui:expenseCategories.save}}. Статті не видаляються, лише архівуються. Помилки: «{{ui:expenseCategories.nameRequired}}» і «{{ui:problem.CATEGORY_NAME_TAKEN}}»

### Посилання «{{ui:settings.meterTreeLink}}»

{{ui:settings.meterTreeText}} Посилання відкриває [дерево лічильників](help:meter-tree).

## Приклад

Голова налаштовує кооператив «Надія» перед першим розсиланням рахунків.

1. У панелі «{{ui:settings.threshold.title}}» залишає 1000,00 ₴ і 90 днів. Ділянка з боргом 1 200,00 ₴ тепер червона, ділянка з боргом 400,00 ₴ і першим боргом тиждень тому жовта.
2. У «{{ui:settings.requisites.title}}» вписує повну назву, юридичну адресу, банк і IBAN. Без IBAN рахунки не сформуються.
3. У «{{ui:deliverySettings.title}}» вписує дані скриньки st.nadiya@gmail.com: сервер smtp.gmail.com, порт 587, STARTTLS, пароль застосунку. Натискає {{ui:deliverySettings.email.save}}, потім {{ui:deliverySettings.email.sendTest}}. Приходить лист на адресу голови, а система пише «{{ui:deliverySettings.email.testAccepted}}».
4. Створює бота @BotFather, вставляє токен і натискає {{ui:deliverySettings.telegram.save}} та {{ui:deliverySettings.telegram.check}}. Позначка змінюється на «{{ui:deliverySettings.configured}}».
5. Перемикач у панелі «{{ui:settings.payments.title}}» лишає ввімкненим. Тепер, якщо ділянка сплатила 1 000,00 ₴ при боргу 750,00 ₴, залишок 250,00 ₴ стає авансом і сам піде на нові нарахування, щойно їх проведуть. Якби перемикач був вимкнений, казначей розподіляв би аванс вручну.

Кольори ділянок для членів лишаються «{{ui:settings.visibility.OWN_ONLY.label}}», доки загальні збори не вирішать інакше.

## Знімки екрана

![Налаштування кооперативу](shot:settings)

![Панель порогу червоного статусу](shot:settings-thresholds)

![Панель розсилки: пошта, бот Telegram, параметри](shot:settings-delivery)

## Пов’язані сторінки

- [Ролі та дозволи](help:roles)
- [Розсилка](help:deliveries)
- [Мій кабінет](help:account)
- [Внести платіж](help:payments-enter)
- [Втрати](help:losses)
- [Спільні споживачі](help:common-consumers)
- [Дерево лічильників](help:meter-tree)
- [Види нарахувань](help:charge-types)
- [Журнал змін](help:audit)
