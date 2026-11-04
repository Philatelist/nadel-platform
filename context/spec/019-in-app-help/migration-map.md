# Migration map: board guide (old HTML) to help pages

Every `<h2>`/`<h3>` of the retired `docs/guide/uk/board-guide.html` (in the order of the old guide) and the help page(s) in `docs/help/{uk,en}/` that now carry it. The HTML board guide is deleted; the PDFs are built from the help. Where a section is named twice in the old guide ("Дерево лічильників", "Статті витрат і доходів", "Розібрані приклади", "На екрані", "Чотири звіти каси") the old section number is shown. The printable `docs/guide/uk/telegram-leaflet.html` is kept as is; its steps are in `member-telegram`.

| Old section (board guide) | New page(s) |
|---|---|
| Зміст | dropped (the help has its own contents, built from `section` and `order`) |
| 1 Про систему, ролі та вхід | `getting-started` (Про систему) |
| 1 / Хто що бачить | `getting-started` (base roles table), `roles` |
| 1 / Вхід | `getting-started` (Вхід) |
| 1 / Мова та тема | `getting-started` (Шапка і меню, Мова і тема) |
| 2 Члени кооперативу | `members` |
| 2 / Пошук | `members` (Пошук) |
| 2 / Додати члена | `member-form` (incl. notes and personal data box) |
| 2 / Картка члена | `member-card` |
| 2 / Архівування | `member-card` (Кнопки в заголовку, box) |
| 3 Ролі та дозволи | `roles` |
| 3 / Дозволи | `roles` (table of all permissions) |
| 4 Ділянки | `plots` |
| 4 / Що зберігається про ділянку | `plot-form` (field table), `plot-card` (Дані ділянки) |
| 4 / Статус (колір) ділянки | `plots` (Статус (колір) ділянки), `how-charges-months` (colours table) |
| 4 / Картка ділянки | `plot-card` (incl. archive/delete, Акт звірки tab) |
| 5 Власники ділянок | `plot-card` (panel Власники) |
| 5 / Зміна власника (продаж, спадщина) | `plot-card` (button Змінити власника) |
| 5 / Виправлення помилки | `plot-card` (button Виправити) |
| 6 Налаштування кооперативу | `settings` |
| 6 / Хто бачить кольори ділянок | `settings` |
| 6 / Аванс | `settings` (panel Платежі) |
| 6 / Дерево лічильників | `settings` (link), `meter-tree` |
| 6 / Статті витрат і доходів | `settings` (panel Статті витрат і доходів) |
| 6 / Налаштування нарахувань | `settings` (threshold, losses, common consumers, water) |
| 6 / Реквізити | `settings` |
| 7 Імпорт з Excel | `imports` |
| 8 Тарифи | `tariffs` |
| 8 / Види нарахувань | `charge-types`, `tariffs` |
| 8 / Новий тариф | `tariff-form` (bases, frequency, rate precision incl. «50,00 ₴, а не 50,0000 ₴» added in close-out) |
| 8 / Версії тарифу | `tariff-card` |
| 8 / До яких ділянок застосовується | `tariff-card` |
| 9 Лічильники та дерево лічильників | `meters` |
| 9 / Види лічильників | `meters` |
| 9 / Зареєструвати, замінити, зняти | `meters` (register), `meter-card` (replace, wrap-around, remove, delete) |
| 9 / Дерево лічильників | `meter-tree` |
| 10 Покази: обхід і підтвердження | `readings-round` |
| 10 / Обхід електрика | `readings-round` |
| 10 / Покази від членів кооперативу | `readings-approvals`, `member-readings` (member part) |
| 10 / Виправлення | `meter-card`, `recalculation-new` |
| 11 Журнал змін | `audit` |
| 12 Кабінет члена кооперативу | `account` |
| 12 / Мої нарахування | `account` (block «Мої нарахування і платежі»), `member-bill` |
| 12 / Акт звірки | `account`, `member-documents` |
| 13 Платежі та каса | `payments`, `payment-card` |
| 13 / Внести платіж | `payments-enter` |
| 13 / Пропозиція розподілу | `payments-enter` |
| 13 / Зберегти й номер квитанції | `payments-enter` |
| 13 / Один платіж за кілька ділянок одного платника | `payments-enter`, `payment-card` (group parts) |
| 13 / Картка платежу | `payment-card` |
| 13 / Перерозподілити | `payment-card` |
| 13 / Сторнувати платіж | `payment-card`, `cashbook` (journal line) |
| 13 / Аванс: автоматично й вручну | `payments-enter` (trap 6), `payment-card` (allocate prepayment) |
| 13 / Рахунок ділянки після платежів | `plot-card` (Вкладка «Рахунок»: columns, advance, filter «Лише платежі» added in close-out), `charges-month`; reports «Баланс ділянок»/«Платежі» in `report` |
| 13 / Що бачить член кооперативу | `account`, `member-bill` |
| 14 Рахунки, довідки та квитанції | `certificates`, `deliveries`, `charges-month` |
| 14 / Перед першим рахунком: реквізити | `charges-month` (Рахунки за місяць, box; added in close-out), `settings` (Реквізити) |
| 14 / Формування рахунків за місяць | `charges-month` (Рахунки за місяць: preview, switch, build, result, concurrency, list, PDF для друку; added in close-out) |
| 14 / Версії | `charges-month` (Версії рахунків; added in close-out), `how-invoice-numbers` |
| 14 / Рахунок на вимогу | `plot-card` (вкладка «Рахунки»; completed in close-out) |
| 14 / Що в рахунку | `member-bill` (Що в рахунку; interval line, payments block, «Отримано після», A4 and page two completed in close-out), `how-invoice-numbers` |
| 14 / QR-код і як перевірити його телефоном | `member-bill` (QR-код; NBU rules added in close-out), `charges-month` (Перевірити QR-код перед роздачею; added in close-out) |
| 14 / Довідки про стан розрахунків | `certificates` (what is in it, annulling), `plot-card` (issuing) |
| 14 / Реєстр довідок | `certificates` |
| 14 / Квитанція про готівку | `payments-enter`, `payment-card` (receipt contents added in close-out), `member-documents` |
| 14 / Що бачить член | `member-documents`, `member-bill` |
| 14 / Журнал дій (unnamed paragraph in section 14) | `audit` (Зокрема в журнал потрапляють; added in close-out) |
| 15 Розсилка: пошта, Telegram і бот | `deliveries` |
| 15 / Налаштування → «Розсилка»: поштова скринька | `settings` (Розсилка: Електронна пошта, Загальні параметри); Gmail app-password trap also in `deliveries` |
| 15 / Бот Telegram | `settings` (Розсилка: Бот Telegram) |
| 15 / Як член підключає Telegram | `member-telegram` |
| 15 / Кому яким каналом піде | `deliveries`, `member-telegram` |
| 15 / Надсилання рахунків за місяць | `charges-month` (Надіслати рахунки за місяць: preview by channel, nothing-due switch, queue, Telegram and email text, dedup, single send, paper; added in close-out), results on `deliveries` |
| 15 / Читаємо екран «Розсилка» | `deliveries` |
| 15 / Повідомлення про платежі | `deliveries`, `payments-enter` |
| 15 / Оголошення | `announcements`, `announcement-form`, `announcement-card` |
| 15 / Що бачить член у боті | `member-telegram` (Що вміє бот) |
| 15 / Журнал змін (unnamed paragraph in section 15) | `audit` (Зокрема в журнал потрапляють; added in close-out) |
| 16 Каса і банк | `cashbook` |
| 16 / Два рахунки й вхідний залишок | `cashbook` |
| 16 / Що показує екран | `cashbook` |
| 16 / Щоденна робота | `expenses`, `expense-form`, `expense-card`, `cashbook` (Здати в банк, Інший прихід, Сторнування записів) |
| 16 / Перерахунок каси | `cash-count` |
| 16 / Кінець місяця: звірка з банком | `bank-reconciliation` |
| 16 / Статті витрат і доходів | `expense-form` (mentions), `settings` |
| 16 / Хто що може | `cashbook` (Who sees it), `expense-card`, `cash-count`, `bank-reconciliation` |
| 16 / Журнал змін (unnamed paragraph in section 16) | `audit` (Зокрема в журнал потрапляють; added in close-out) |
| 17 Звіт для загальних зборів | `reports`, `report` |
| 17 / Чотири звіти каси | `report`, `reports` |
| 17 / Акт звірки з членом кооперативу | `plot-card` (вкладка «Акт звірки»: contents, period rules, signatures; added in close-out), `member-documents`, `account` |
| Як система рахує (Part II lead) | dropped (replaced by the section title in the help contents) |
| 18 Основні принципи | `how-principles` |
| 19 Тарифи та очікувана сума | `how-tariffs-expected` |
| 19 / Розібрані приклади | `how-tariffs-expected` |
| 19 / До яких ділянок | `how-tariffs-expected` |
| 19 / Виправлення версії | `how-tariffs-expected` |
| 20 Лічильники та дерево | `how-meters-tree` (the tree diagram is described by its caption; the SVG is not carried over) |
| 20 / Заміна лічильника | `how-meters-tree` |
| 21 Покази та споживання | `how-readings-consumption` |
| 21 / Статуси показу | `how-readings-consumption` |
| 21 / Інтервал і значення на дату | `how-readings-consumption` |
| 21 / Перевірки | `how-readings-consumption` |
| 22 Нарахування й закриття місяців | `how-charges-months`; screens in `charges`, `charges-month`, `not-charged` |
| 22 / Звідки беруться нарахування | `how-charges-months`, `charges` |
| 22 / Світло за показом | `how-readings-consumption` (moved: a bill topic for everyone), worked example in `readings-round` |
| 22 / Місяці та закриття | `how-charges-months`, `charges-month` |
| 22 / Сторно й черга «Не нараховано» | `how-charges-months`, `charges`, `not-charged` |
| 22 / Борг | `how-charges-months` |
| 22 / На екрані: «Нарахування» | `charges`, `how-charges-months` |
| 22 / «Не нараховано» | `not-charged`, `how-charges-months` |
| 22 / Закриття місяця | `charges-month`, `how-charges-months` |
| 22 / Рахунок ділянки, сторно та вхідне сальдо | `how-charges-months`, `charges` (reversal, opening balance sources), `plot-card` |
| 22 / Кольори ділянок на мапі й у списку | `how-charges-months` (colours table), `plots` |
| 22 / Казначей і член кооперативу | `how-charges-months`, `charges` (Who sees it) |
| 23 Втрати в мережі та спільні споживачі | `how-losses-common`, `losses`, `common-consumers` |
| 23 / Втрати: два режими на вибір голови | `how-losses-common`, `losses` (examples) |
| 23 / Спільні споживачі — окремим рядком | `how-losses-common`, `common-consumers` (table of splits) |
| 23 / На екрані: «Втрати» та «Спільні споживачі» | `losses`, `common-consumers` |
| 24 Вода та підключення | `how-water-connection`; `charge-types` (activate «Вода»), `tariff-form` (trap), `common-consumers` (well pump), `readings-round` (water note), `plot-card` («Вода» panel) |
| 24 / На екрані | `how-water-connection`, `plot-card` |
| 25 Виправлення і перерахунок | `how-recalculation`, `recalculations` |
| 25 / На екрані | `recalculation-new`, `recalculation-card`, `recalculations`, `tariff-card` (via a tariff version), `meter-card` (via a reading) |
| 26 Звіти та шаблони | `how-reports`, `reports` |
| 26 / Хто бачить звіти | `reports`, `report`, `how-reports` |
| 26 / Екран «Звіти» | `reports`, `report` |
| 26 / Вісім звітів | `report`, `how-reports` |
| 26 / Чотири звіти каси | `report` |
| 26 / Шаблони: власна форма Excel | `report-templates` |
| 26 / Що система робить із шаблоном | `report-templates` |
| 26 / Версії, архів і податковий звіт | `report-templates` |
| 26 / Реквізити кооперативу | `settings` (Реквізити), `how-reports` |
| 27 Платежі й розподіл | `how-payments-allocate` |
| 27 / Розібрані приклади | `how-payments-allocate` |
| 27 / Борг і колір після платежів | `how-payments-allocate` |
| 28 Цифри рахунку | `how-invoice-numbers` |
| 28 / Приклади | `how-invoice-numbers` |
| 29 Кому яким каналом і як система повторює відправку | `how-delivery` |
| 29 / Розібрані приклади | `how-delivery` |
| 29 / Спроби відправки | `how-delivery` |
| 30 Каса, банк і звірка | `how-cash-and-bank` |
| 30 / Розібрані приклади | `how-cash-and-bank` |
| 31 Доходи та витрати й акт звірки | `how-income-expenses` |
| 32 Що з’явиться далі | dropped on purpose (planned features, no content) |
