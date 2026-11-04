# Migration map, Slice 9 (board guide sections 13-17 and 26)

| Old section (board guide) | New page(s) |
|---|---|
| 13 Платежі та каса (intro, rights) | `payments`, `payment-card` |
| 13 / Список платежів | `payments` |
| 13 / Внести платіж | `payments-enter` |
| 13 / Пропозиція розподілу | `payments-enter` |
| 13 / Зберегти й номер квитанції | `payments-enter` |
| 13 / Один платіж за кілька ділянок одного платника | `payments-enter`, `payment-card` (group parts) |
| 13 / Картка платежу | `payment-card` |
| 13 / Перерозподілити | `payment-card` |
| 13 / Сторнувати платіж (incl. «Платіж і каса») | `payment-card`, `cashbook` (journal line) |
| 13 / Аванс: автоматично й вручну | `payments-enter` (trap 6), `payment-card` (allocate prepayment) |
| 13 / Рахунок ділянки після платежів | not migrated here: `plot-card` (Slice 7) and `charges-month` (Slice 8); «Баланс ділянок»/«Платежі» reports in `report` |
| 13 / Що бачить член кооперативу | not migrated here: `member-bill` (Slice 10); `payments` says a member does not see the screen |
| 14 Рахунки, довідки та квитанції (intro) | `certificates`, `deliveries` |
| 14 / Перед першим рахунком: реквізити | not migrated here: `charges-month` / `settings` |
| 14 / Формування рахунків за місяць | not migrated here: `charges-month` (Slice 8) |
| 14 / Версії | not migrated here: `charges-month`, `how-invoice-numbers` |
| 14 / Рахунок на вимогу | not migrated here: `plot-card` (Slice 7) |
| 14 / Що в рахунку | not migrated here: `charges-month`, `how-invoice-numbers` |
| 14 / QR-код і як перевірити його телефоном | not migrated here: `charges-month`, `member-documents` |
| 14 / Довідки про стан розрахунків | `certificates` (what is in it, annulling); issuing: `plot-card` (Slice 7) |
| 14 / Реєстр довідок | `certificates` |
| 14 / Квитанція про готівку | `payments-enter`, `payment-card` |
| 14 / Що бачить член | not migrated here: `member-documents` (Slice 10) |
| 14 / Журнал дій | not migrated here: `audit` (Slice 7) |
| 15 Розсилка (intro) | `deliveries` |
| 15 / Налаштування → «Розсилка»: поштова скринька (Gmail app password, limits) | `settings` (Slice 7); Gmail app-password trap 4 repeated in `deliveries` |
| 15 / Бот Telegram | not migrated here: `settings` (Slice 7) |
| 15 / Як член підключає Telegram | not migrated here: `member-telegram` (Slice 10) |
| 15 / Кому яким каналом піде | `deliveries` |
| 15 / Надсилання рахунків за місяць | not migrated here: `charges-month` (Slice 8); results on `deliveries` |
| 15 / Читаємо екран «Розсилка» | `deliveries` |
| 15 / Повідомлення про платежі | `deliveries`, `payments-enter` |
| 15 / Оголошення | `announcements`, `announcement-form`, `announcement-card` |
| 15 / Що бачить член у боті | not migrated here: `member-telegram` (Slice 10) |
| 15 / Журнал змін | not migrated here: `audit` (Slice 7) |
| 16 Каса і банк (intro, rights) | `cashbook` |
| 16 / Два рахунки й вхідний залишок | `cashbook` |
| 16 / Що показує екран | `cashbook` |
| 16 / Щоденна робота / Витрата | `expenses`, `expense-form` (trap 3), `expense-card` |
| 16 / Здати в банк | `cashbook` |
| 16 / Інший прихід | `cashbook` |
| 16 / Сторнування записів (incl. Закритий місяць) | `cashbook` |
| 16 / Перерахунок каси | `cash-count` |
| 16 / Кінець місяця: звірка з банком (incl. межі) | `bank-reconciliation` |
| 16 / Статті витрат і доходів | `expense-form` (mentions); the settings screen itself: `settings` (Slice 7) |
| 16 / Хто що може | `cashbook` (Who sees it), `expense-card`, `cash-count`, `bank-reconciliation` |
| 16 / Журнал змін | not migrated here: `audit` (Slice 7) |
| 17 Звіт для загальних зборів (intro) | `reports`, `report` |
| 17 / Чотири звіти каси | `report` (and `reports` for rights) |
| 17 / Акт звірки з членом кооперативу | not migrated here: `plot-card` and `account` (Slices 7 and 10) |
| 26 Звіти та шаблони (intro, «Звіти не вводять нових правил») | `reports` |
| 26 / Хто бачить звіти | `reports`, `report` |
| 26 / Екран «Звіти» | `reports`, `report` |
| 26 / Вісім звітів | `report` |
| 26 / Чотири звіти каси | `report` |
| 26 / Шаблони: власна форма Excel | `report-templates` |
| 26 / Що система робить із шаблоном | `report-templates` |
| 26 / Версії, архів і податковий звіт | `report-templates` |
| 26 / Реквізити кооперативу | not migrated here: `settings` (Slice 7) |
