# The NBU payment QR code (format 003)

This document is the source of truth for the QR code printed on invoices (feature 016,
`backend/src/main/java/app/nadel/documents/internal/nbu/`). It quotes the official text and records, for every element,
the value our code writes and where that value comes from. Any change to the QR code is made here first.

Quotations from the official text are in Ukrainian, as published (only the line breaks and hyphenation of the PDF are
undone). Everything else is in English.

## 1. Source

- **Resolution of the Board of the National Bank of Ukraine No. 97 of 19 August 2025** «Про затвердження Правил
  формування, передачі та обробки структури даних і графічного зображення QR-коду для обміну реквізитами кредитових та
  миттєвих кредитових переказів», officially published 25.08.2025, in force from 01.11.2025 (item 5 of the
  resolution).
- Official PDF: <https://bank.gov.ua/admin_uploads/law/19082025_97.pdf> (55 pages).
- Used here: **Annex 1** (general requirements: the data structure, the generation of the image, its display) and
  **Annex 4** (the data structure of **format version 003**, the only format we write).
- The field layout is never guessed: every element below is quoted from Annex 4. Open-source implementations were not
  used as a source.

## 2. Annex 4, table 1: the parts of the link

Format 003 is a hyperlink (Annex 4 item 5). Column headings as in the official table.

| № з/п | Назва / призначення елемента даних структури QR-коду | Довжина | Фіксована / змінна | Обов’язковість | Кодування | Наповнення / зміст |
|---|---|---|---|---|---|---|
| 1 | Код старту застосунку | 50 B | Змінна | О | А | https://qr.bank.gov.ua/ або персоніфікований код старту застосунку |
| 2 | Закодовані елементи структури даних QR-коду | 475 B | Змінна | О | Base64URL | Закодований вміст таблиці 2 додатка 4 до Правил формування, передачі та обробки структури даних і графічного зображення QR-коду для обміну реквізитами кредитових та миттєвих кредитових переказів (далі – Правила) |

## 3. Annex 4, table 2: the open data structure (17 elements)

Column headings as in the official table. In column 3 the PDF prints the unit letters B and C partly in Cyrillic
(«В», «С»); they are the units of item 6.1 (B – bytes, C – characters) and are written in Latin here.

| № з/п | Назва / призначення елемента даних структури QR-коду | Довжина | Фіксована / змінна | Обов’язковість | Кодування | Наповнення / зміст |
|---|---|---|---|---|---|---|
| 1 | Службова мітка | 3 B | Фіксована | О | А | BCD |
| 2 | Версія формату | 3 B | Фіксована | О | А | 003 |
| 3 | Кодування | 1 B | Фіксована | О | А | 1 або 2 |
| 4 | Функція | 3 B | Фіксована | О | А | UCT або ICT або XCT |
| 5 | Унікальний ідентифікатор отримувача | 11 B | Змінна | Р | А | RFU (зарезервовано для подальшого використання) |
| 6 | Отримувач | 140 C | Змінна | О | * | Прізвище, власне ім’я, по батькові (за наявності) фізичної особи / найменування юридичної особи |
| 7 | Рахунок отримувача | 29 B | Фіксована | О | А | Номер рахунку отримувача |
| 8 | Сума / валюта | 15 B | Змінна | Оп. | А | Сума та валюта |
| 9 | Код отримувача | 10 B | Змінна | О | * | Код отримувача коштів |
| 10 | Категорія / ціль | 9 C | Змінна | О | А | Код категорії (C) та код цілі (P) у класифікаторі платежів у форматі CCCC/PPPP |
| 11 | Reference | 35 B | Змінна | Оп. | А | Ідентифікатор рахунку на оплату отримувача |
| 12 | Призначення платежу | 420 C | Змінна | О | * | Узгодження у форматі тексту |
| 13 | Відображення (дисплей) | 140 C | Змінна | Оп. | * | Додатковий текст |
| 14 | Код заборони зміни полів | 4 B | Змінна | Оп. | А | Маска можливості зміни полів структури користувачем |
| 15 | Дата / час дії рахунку на оплату | 14 B | Змінна | Оп. | А | Дата та час, до якого може бути сформований платіж у НПП платника у форматі РРММДДГГХХСС |
| 16 | Дата / час формування рахунку на оплату | 14 B | Змінна | Оп. | А | Дата та час формування рахунку на оплату у форматі РРММДДГГХХСС |
| 17 | Електронний підпис даних | 90 B | Змінна | Оп. | А | Електронний підпис структури даних QR-коду RFU (зарезервовано для подальшого використання) |

## 4. Annex 4, item 4: the elements (4.1–4.17)

> 1) елемент “Службова мітка” (рядок 1) містить дані, які розкодовуються з QR-коду, починаються із символів “BCD” –
> “службової мітки”, після якої відразу міститься Закінчення рядка. Подальша перевірка вмісту QR-коду повинна містити
> розпізнавання та визначення Закінчення рядка;
>
> 2) елемент “Версія формату” (рядок 2) містить номер версії формату QR-коду;
>
> 3) елемент “Кодування” (рядок 3). Значення в полі “Кодування” визначає інтерпретацію даних (кодову таблицю символів),
> що використовуються в полях, для яких у колонці “Кодування” таблиці зазначено символ “*”. Для версії формату QR-коду
> 003 у полі “Кодування” значення повинно бути “2” або “1”, що передбачає використання кодування Win1251 або UTF-8
> відповідно для всіх полів, які можуть містити кириличні символи;
>
> 4) елемент “Функція” (рядок 4) ‒ для версії формату QR-коду 003 поле “Функція” може містити одне з трьох значень:
> “UCT” – кредитовий переказ (англійською мовою Ukrainian Credit Transfer);
> “ICT” – миттєвий кредитовий переказ (англійською мовою Instant Credit Transfer);
> “XCT” – миттєвий кредитовий переказ або кредитовий переказ;
>
> 5) елемент “Унікальний ідентифікатор отримувача” (рядок 5) ‒ для версії формату QR-коду 003 поле “Унікальний
> ідентифікатор отримувача” зарезервовано для подальшого використання;
>
> 6) елемент “Отримувач” (рядок 6) містить прізвище, власне ім’я, по батькові (за наявності) фізичної особи або
> найменування юридичної особи;
>
> 7) елемент “Рахунок отримувача” (рядок 7) містить номер рахунку отримувача;
>
> 8) елемент “Сума / валюта” (рядок 8). Значення в полі “Сума / валюта” є опціональним. Якщо поле “Сума / валюта”
> порожнє, то заповнення суми має здійснитися під час ініціювання платежу. Максимальне число в полі “Сума / валюта”
> становить 999999999.99.  Символом “.” у сумі відділяється ціла частина від дрібної частини одиниці валюти. Якщо сума
> не містить дрібної частини одиниці валюти, то допускається, що символу “.” та нулів після цього символу немає. Якщо
> сума містить дрібну частину одиниці валюти, то ця дрібна частина обов’язково складається з двох цифрових символів.
> Перед сумою повинні розміщуватися три великі літери коду валюти. Єдиною валютою, доступною для версії формату QR-коду
> 003, є гривня. Cума має бути якомога коротшою відповідно до результуючого коду, наприклад, краще “UAH3”, ніж
> “UAH3.00”. Нулі перед сумою зазначати заборонено;
>
> 9) елемент “Код отримувача” (рядок 9) містить код, який має бути зазначений у платіжній інструкції кредитового або
> миттєвого кредитового переказу відповідно до вимог Національного банку України;
>
> 10) елемент “Категорія / ціль” (рядок 10). У версії формату QR-коду 003 заповнюється під час формування структури
> даних QR-коду отримувачем значеннями з довідників, визначеними в міжнародному стандарті ISO 20022, який був
> запроваджений у платіжній інфраструктурі України постановою Правління Національного банку України від 16 вересня
> 2021 року № 93 “Про запровадження міжнародного стандарту ISO 20022 у платіжній інфраструктурі України” (зі змінами)
> (далі – ISO 20022);
>
> 11) елемент “Reference” (рядок 11) заповнюється унікальним в інформаційній системі отримувача значенням, яке
> ідентифікує місце продажу товару, послуги отримувача та рахунок на оплату отримувача, додається в платіжну інструкцію
> і дані кредитового або миттєвого кредитового переказу. Значення з елементу “Reference” може бути використане для
> встановлення відповідності сформованого отримувачем рахунку на оплату та результату виконання кредитового або
> миттєвого кредитового переказу в системі надавача платіжних послуг отримувача. У разі потреби в передачі в структурі
> даних QR-коду внутрішнього ідентифікатора місця продажу товару, послуги окремо від значення в елементі “Reference” в
> елемент “Відображення (дисплей)” має бути доданий фрагмент XML повідомлення ISO 20022, яке формується відповідно до
> вимог Національного банку України із зазначеним у такому фрагменті значенням внутрішнього ідентифікатора;
>
> 12) елемент “Призначення платежу” (рядок 12) містить інформацію про платіж у текстовій формі. Якщо текст поля
> починається з символу ‘?’, то подальший рядок може містити назви і значення додаткових параметрів кредитового або
> миттєвого кредитового переказу у форматі <назва>=“значення”. Допускається формування значення поля з кількома
> додатковими параметрами, між якими міститься символ ‘&’, наприклад: ?MerchantBusinessName=“АЗС
> 222”&Goods=“А95+”&ClientType=“Regular”. Максимальний розмір даних у даному елементі обмежений значенням, наведеним у
> таблиці 2 додатка 4 до Правил з урахуванням обмеження максимального розміру закодованої структури даних QR-коду,
> наведеної в таблиці 1 додатка 4 до Правил;
>
> 13) елемент “Відображення (дисплей)” (рядок 13) містить текст, призначений для виведення на дисплей або друку. Цей
> текст не включається до даних операції переказу коштів і має бути показаний користувачеві після розкодування QR-коду.
> Крім того, цей текст у незмінному або зміненому вигляді може використовуватись у системах обробки даних для
> деталізації даних операції. Якщо текст поля починається з символу ‘?’, то подальший рядок може містити назви та
> значення  додаткових XML тегів та атрибутів платіжної інструкції, які можуть включатись у повідомлення ISO 20022, яке
> формується відповідно до вимог Національного банку України;
>
> 14) елемент “Код заборони зміни полів” (рядок 14) містить число від 0 до FFFF (hex). Бітова маска зазначеного числа
> дає змогу встановити  заборону редагування полів структури відкритих даних. Номер поля, для якого потрібно встановити
> заборону редагування, наведений у колонці 1 таблиці 2 додатка 4 до Правил. Цей номер відповідає номеру біта
> (починаючи з наймолодшого) у бітовій масці числа, яке міститься в даному елементі. Для заборони зміни поля платником
> відповідний біт значення поля “Код заборони зміни полів” потрібно встановити у '1'. Зміна полів із номерами 1‒5, 11,
> 14‒17 повинна бути завжди заборонена. Наприклад, для заборони зміни значення всіх полів, крім значення в полі “Сума /
> валюта” (поле номер 8), у полі “Код заборони зміни полів” має бути значення FEFF (hex);
>
> 15) елемент “Дата / час дії рахунку на оплату” (рядок 15) містить дату та час, до настання яких має бути розпочатий
> кредитовий або миттєвий кредитовий переказ, реквізити якого введені зі структури даних QR-коду банком платника.
> Заповнення поля є опціональним. Якщо поле не заповнене, дата / час дії рахунку на оплату вважаються необмеженими;
>
> 16) елемент “Дата / час формування рахунку на оплату” (рядок 16) містить дату та час, коли була сформована структура
> даних QR-коду. Для структур даних QR-коду, які не містять значення в елементі даних “Електронний підпис даних”,
> заповнення поля є опціональним, інакше – обов’язковим;
>
> 17) елемент “Електронний підпис даних” (рядок 17) ‒ для версії формату QR-коду 003 поле “Електронний підпис даних”
> зарезервовано для подальшого використання.

## 5. Annex 4, items 5–11: the legend, the line end, the size and the generation

> 5. Структура даних QR-коду версії формату QR-коду 003 є гіперпосиланням, яке сформоване відповідно до таблиці 1
> додатка 4 до Правил.
>
> 6. Умовні позначення, що містяться в таблицях 1, 2 додатка 4 до Правил:
>
> 1) у колонці 3 “Довжина” зазначається визначений або максимальний розмір елемента:
> B – у байтах;
> C – у символах;
>
> 2) у колонці 4 “Фіксована / змінна” зазначається довжина елемента:
> фіксована – елемент завжди має бути визначеної довжини;
> змінна – елемент може бути будь-якої довжини в межах визначеної;
>
> 3) у колонці 5 “Обов’язковість”:
> О – обов’язковий, не може бути порожнім;
> Оп. – опціональний, обов’язково наявний, може містити текст або бути порожнім (складатися виключно із Закінчення
> рядка);
> Р – резервний, у зазначеній версії формату має бути порожнім (складатися виключно із Закінчення рядка);
>
> 4) у колонці 6 “Кодування”:
> А – ISO 646;
> \* – ISO 646 або інше (UTF-8, Windows-1251). Для усіх полів, у яких можуть міститися дані з кодуванням, відмінним від
> ISO 646, повинно застосовуватись однакове кодування, визначене в полі “Кодування”.
>
> 7. Закінчення рядка. Для мінімізації розміру QR-коду у версії формату 003 потрібно застосовувати як Закінчення рядка
> виключно Lf.
>
> 8. Загальний обсяг даних, що підлягають кодуванню для генерації QR-коду, що містить структуру версії формату QR-коду
> 003, не повинен перевищувати 507 байтів.
>
> 9. Максимальна версія згенерованого QR-коду для версії формату QR-коду 003 дорівнює 17. Це еквівалентно кількості
> модулів, що дорівнює 85.
>
> 10. Для генерації QR-коду версії формату QR-коду 003 потрібно послідовно виконати такі дії:
>
> 1) заповнити значення елементів даних структури QR-коду для версії формату QR-коду 003 відповідно до таблиці 2
> додатка 4 до Правил;
>
> 2) виконати кодування заповнених відповідно до підпункту 1 пункту 10 розділу IV додатка 4 до Правил елементів даних
> структури QR-коду за алгоритмом Base64URL;
>
> 3) сформувати структуру даних QR-коду шляхом додання методом конкатенації закодованих відповідно до підпункту 2
> пункту 10 розділу IV додатка 4 до Правил елементів даних структури QR-коду до константи, визначеної в полі “Код старту
> застосунку” таблиці 1 додатка 4 до Правил;
>
> 4) сформувати графічне зображення QR-коду як кодування отриманого в результаті попередніх дій гіперпосилання, яке є
> структурою даних QR-коду, зі встановленням рівня корекції помилок “M” або “Q”;
>
> 5) модифікувати графічне зображення QR-коду шляхом додавання зображення графічного знака національної грошової
> одиниці гривні у фоновому колі в спосіб, визначений у пунктах 10‒12 розділу II додатка 1 до Правил.
>
> 11. Для декодування структури даних QR-коду для версії формату QR-коду 003 потрібно послідовно виконати такі дії:
>
> 1) виконати декодування графічного зображення QR-коду в гіперпосилання;
>
> 2) відкинути від гіперпосилання  елемент “Код старту застосунку”;
>
> 3) декодувати за алгоритмом Base64URL частину рядка, що залишиться після відкидання елемента “Код старту застосунку”.
> Після декодування отримані дані повинні відповідати таблиці 2 додатка 4 до Правил.

## 6. Annex 1: characters, versions, error correction, the circle, the sign, the size

Item 4 (the characters an element may hold; quoted because it decides our normalisation):

> 4. Елемент даних структури QR-коду може містити символи з номерами від 32 (20 hex) до 255 (1F hex), крім символів
> 127 (7F hex), 152 (98 hex), 160 (A0 hex) у кодовій таблиці символів Windows-1251 або еквіваленти цих символів у
> таблиці Unicode UTF-8 під час використання кодування UTF-8. […]

(The PDF prints «255 (1F hex)»; 255 is FF hex.)

Items 5–14:

> 5. Версія QR-коду може мати значення від 10 до 20 включно.
>
> 6. Генерація QR-коду має бути виконана з рівнем корекції помилок “Q”, “M” або “L” залежно від версії формату QR-коду,
> умов відображення та сканування QR-коду, розміру даних, які мають бути закодовані в QR-коді. Частка площі графічного
> зображення QR-коду, яку припустимо змінити без втрати можливості декодування графічного зображення QR-коду в структуру
> даних QR-коду за рахунок застосованого рівня корекції помилок, становить близько 25%, 15% або 7% відповідно до
> застосованого рівня корекції помилок.
>
> 7. Розмір сторони QR-коду дорівнює: номер версії QR-коду*4+17 модулів. Ширина / висота QR-коду розраховується як
> кількість модулів, помножена на Х-розмір модуля.
>
> 8. Максимальна версія QR-коду визначається в додатках 2‒4 до Правил формування, передачі та обробки структури даних і
> графічного зображення QR-коду для обміну реквізитами кредитових та миттєвих кредитових переказів (далі – Правила) з
> описом відповідних версій формату QR-коду.
>
> 9. Використання версій QR-коду менших, ніж визначені як максимальні для відповідних версій формату QR-коду,
> дозволяється.
>
> 10. Графічне зображення QR-коду має містити зображення графічного знака національної грошової одиниці гривні на фоні
> білого кола (далі – фонове коло), центр якого повинен бути розташований на перетині діагоналей квадрата, який є
> графічним зображенням QR-коду.
>
> 11. Діаметр фонового кола (у модулях) залежно від версії QR-коду має бути таким:
>
> 1) 17 модулів для версії QR-коду 10;
>
> 2) 19 модулів для версій QR-коду 11, 12;
>
> 3) 21 модуль для версії QR-коду 13;
>
> 4) 23 модулі для версій QR-коду 14, 15;
>
> 5) 25 модулів для версій QR-коду 16, 17.
>
> 12. Зображення графічного знака національної грошової одиниці має бути вписаним у коло, центр якого збігається із
> центром фонового кола, а діаметр на 4 модулі менший від діаметра фонового кола.
>
> 13. Для QR-кодів з версією формату QR-коду 001 з рівнем корекції помилок “М” або “Q” модифікація QR-коду шляхом
> додавання зображення графічного знака національної грошової одиниці гривні у фоновому колі є опціональною. Для
> QR-кодів із версіями форматів QR-коду 002 та 003 ‒ обов’язковою. У разі генерації QR-кодів із версіями форматів 001,
> 002, 003 з модифікацією QR-коду шляхом додавання зображенням графічного знака національної грошової одиниці гривні
> рівень корекції помилок “L” не може бути використаний.
>
> 14. Форма зображення графічного знака національної грошової одиниці гривні визначається Національним банком України.

Items 16–18:

> 16. Вміст інформації в тексті рахунку на оплату та в розкодованих даних із QR-коду має бути ідентичним.
>
> 17. На паперовому рахунку на оплату має бути зарезервовано достатньо місця, щоб відповідати QR-кодам, які містять
> максимальний обсяг даних. Для коректного відображення QR-коду має забезпечуватися відступ від країв паперу, ліній
> згину та тексту. Точка прив’язки QR-коду і напрямок, у якому QR-код зростає зі збільшенням даних, також повинні
> враховуватися під час позиціонування QR-коду.
>
> 18. Роздільна здатність принтера визначатиме точний розмір QR-коду. X-розмір менше 0,5 мм не рекомендовано
> використовувати через можливі проблеми з друкуванням і подальшим скануванням QR-коду.

## 7. Annex 4 example 1 «Оплата комунальних послуг» (items 12–14, tables 3 and 4)

Table 3 (part 1) lists the elements of the example. As its HEX column has them (the text column is quoted in
brackets where it differs):

| № | Text | Element |
|---|---|---|
| 1 | `BCD` | Службова мітка |
| 2 | `003` | Версія формату |
| 3 | `2` | Кодування |
| 4 | `UCT` | Функція |
| 5 | (empty) | Унікальний ідентифікатор отримувача |
| 6 | `ТОВ «ГК«Нафтогаз України»` | Отримувач |
| 7 | `UA201234560000000260323012042` and a space (`20` hex before `0A`) | Рахунок отримувача |
| 8 | `UAH2998.39` | Сума / валюта |
| 9 | `40121452` (the HEX column repeats row 8 by mistake; table 4 encodes `40121452`) | Код отримувача |
| 10 | `SUPP/SUPP` | Категорія / ціль |
| 11 | `AA15678-679` | Reference |
| 12 | `?TickNo=”YA1267”&Addr=”вулиця Лугова, буд. 911,Микитинцi”` (the quotes are U+201D, `94` hex; the last «i» is Latin, `69` hex) | Призначення платежу |
| 13 | (empty) | Відображення (дисплей) |
| 14 | `FEFF` | Код заборони зміни полів (дозволено змінювати суму платником) |
| 15 | `250921120000` (text column: `250821120000`) | Дата / час дії рахунку на оплату |
| 16 | `250229120000` (text column: `240729120000`) | Дата / час формування рахунку на оплату |
| 17 | `RFU`, no line end after it | Електронний підпис даних |

Table 4 (part 2), row 2, the encoded data, with the line breaks of the printed table removed (the text column and the
HEX column give the same 276 characters):

```
QkNECjAwMwoyClVDVAoK0s7CIKvDyqvN4PTy7uPg5yDT6vDgv-3ouwpVQTIwMTIzNDU2MDAwMDAwMDI2MDMyMzAxMjA0MiAKVUFIMjk5OC4zOQo0MDEy
MTQ1MgpTVVBQL1NVUFAKQUExNTY3OC02NzkKP1RpY2tObz2UWUExMjY3lCZBZGRyPZTi8-vo9v8gy_Pj7uLgLCDh8-QuIDkxMSzM6Oro8ujt9mmUCgpGRUZG
CjI1MDkyMTEyMDAwMAoyNTAyMjkxMjAwMDAKUkZV
```

`NbuQrPayloadTests` re-encodes the 17 elements above with our joiner (LF, no LF after the last, Windows-1251, Base64URL
without padding) and gets exactly this text.

## 8. Our values

The joiner: the 17 elements in table 2 order, each followed by LF (`0A`) except the last (items 4.1, 6.3 and 7); the
bytes in the encoding of element 3; Base64URL without padding; the link is `https://qr.bank.gov.ua/` + that text
(item 10).

| № | Element | Our value | Source |
|---|---|---|---|
| 1 | Службова мітка | `BCD` | table 2 |
| 2 | Версія формату | `003` | table 2 |
| 3 | Кодування | `2` (Windows-1251); `1` (UTF-8) only when some character of elements 6, 9, 12, 13 has no Windows-1251 form | item 4.3 («“2” або “1”»); every official 003 example uses `2`; Annex 1 item 3 (the smallest structure) |
| 4 | Функція | `XCT` | item 4.4 («миттєвий кредитовий переказ або кредитовий переказ»): the payer chooses (feature requirement §2) |
| 5 | Унікальний ідентифікатор отримувача | empty | table 2 «Р», item 6.3 («має бути порожнім») |
| 6 | Отримувач | the cooperative's official name (the tenant name when none is set), at most 140 characters, cut at a word | table 2 (140 C), item 4.6 |
| 7 | Рахунок отримувача | the IBAN, `UA` and 27 digits | table 2 (29 B, fixed), item 4.7 |
| 8 | Сума / валюта | `UAH` + the amount due in the shortest form: `UAH413.26`, `UAH3`, `UAH0.50`; empty when there is no amount | item 4.8 (quoted in §4) |
| 9 | Код отримувача | the ЄДРПОУ of the cooperative (8 to 10 digits) | table 2 (10 B), item 4.9 |
| 10 | Категорія / ціль | `SUPP/SUPP` | item 4.10 (the recipient fills ISO 20022 codes as `CCCC/PPPP`, quoted in §4) and Annex 4 example 1 «Оплата комунальних послуг», table 3 row 10 `SUPP/SUPP` (§7). Owner decision of 28.10.2026: Annex 4 gives no default, so we take the value of its utility-payment example; no code is invented |
| 11 | Reference | the invoice number (`2026-10/9`) in ISO 646: Ukrainian letters of a plot number transliterated by the table of the Cabinet of Ministers Resolution No. 55 of 27.01.2010 (`9а` → `9a`, `12-Щ` → `12-Shch`), any other non-ISO 646 character as `-`, at most 35 characters | table 2 («А», 35 B), item 4.11 |
| 12 | Призначення платежу | `Ділянка {номер}. Внески за {місяць рік}. Рахунок {номер рахунку}`, shortened as §9 says | item 4.12; feature requirement §2 |
| 13 | Відображення (дисплей) | empty | table 2 «Оп.» |
| 14 | Код заборони зміни полів | `FEFF`, a constant | item 4.14's own example («всіх полів, крім значення в полі “Сума / валюта”») and examples 1 and 2 («дозволено змінювати суму платником»); see §10 |
| 15 | Дата / час дії рахунку на оплату | empty (no expiry) | item 4.15 («Якщо поле не заповнене, … вважаються необмеженими») |
| 16 | Дата / час формування рахунку на оплату | empty | item 4.16 (optional without a signature) |
| 17 | Електронний підпис даних | empty | table 2 «Оп.», item 4.17 (reserved); see §11 |

Before encoding, every text passes through the same filter as the PDF (`PdfFonts.printable`: line breaks and tabs
become spaces, control characters are dropped, a character without a glyph becomes `?`), then the no-break space and
every other space character become a plain space (Annex 1 item 4 forbids `A0` hex), the typographic apostrophes
U+2019, U+02BC (and U+2018, often typed for one) become `'`, runs of spaces become one and the text is trimmed (Annex 1
item 3: the smallest structure).

The demo invoice of plot 9 for October 2026 (413,26 ₴) gives 174 bytes and the link
`https://qr.bank.gov.ua/QkNECjAwMwoyClhDVAoK…CgpGRUZGCgoK` (255 bytes, QR version 12); `NbuQrPayloadTests` checks it
byte by byte.

## 9. Size limits and shortening

- The Base64URL part is at most **475 characters** (table 1 row 2), so the payload is at most 356 bytes; with the
  23-character start the link is at most 498 bytes, within the **507 bytes** of item 8. At error correction level M,
  QR version 17 holds 504 bytes, so every link fits version 17 (item 9).
- Element 6 is at most 140 characters, element 12 at most 420 characters (table 2).
- When the payload would be longer, the purpose and then the name are shortened, stopping at the first form that fits:
  1. the full purpose `Ділянка 9. Внески за жовтень 2026. Рахунок 2026-10/9`;
  2. the month in digits: `Ділянка 9. Внески за 10.2026. Рахунок 2026-10/9`;
  3. without the charges part: `Ділянка 9. Рахунок 2026-10/9`;
  4. the name cut further at a word (a first word longer than the space left is cut inside).

  The plot number and the invoice number are never cut.
- In Windows-1251 (one byte per character) the longest valid invoice fits without shortening: a 140-character name, a
  20-character plot number (the most `plot_number_chk` allows), `UAH999999999.99`, a 10-digit code and the longest
  month name give 355 bytes, 474 Base64 characters (tested). Shortening happens on the UTF-8 path, where a Cyrillic
  letter takes two bytes, and only for long names.
- The final refusal (`IllegalArgumentException`) cannot happen with valid inputs: the fixed elements take at most 128
  bytes; the short purpose with a 20-character plot number takes at most 200 bytes even if every character took four
  bytes; that leaves at least 28 bytes of the 356 for the name.

## 10. The lock mask and the bit-order contradiction of item 4.14

Item 4.14 says the field number of column 1 «відповідає номеру біта (починаючи з наймолодшого)», and gives `FEFF` as
the value that locks every field except the amount (field 8). The two readings of the bit number do not agree with
each other and the item:

- **Bits numbered from 1** (field 1 = `0001` hex): the amount is bit 8 = `0080` hex, so «all but the amount» would be
  `FF7F`, not `FEFF`; `FEFF` would unlock field 9 (the recipient code). Fields 16 and 17 would need bits 16 and 17,
  outside the range `0`–`FFFF` the item sets.
- **Bits numbered from 0** (field N = 2^N): the amount is `0100` hex and `FEFF` is consistent, but bit 0 belongs to no
  field and fields 16 and 17 (2^16, 2^17) are again outside `FFFF`, although the item requires fields 14–17 to be
  always locked.

Neither reading can express the item fully, so we do not compute the mask. We write the item's own example value
`FEFF` as a constant, as do the official examples 1 and 2 («дозволено змінювати суму платником»); banks implement the
examples. Under the reading that matches the example, `FEFF` locks fields 1–7 and 9–15 and leaves the amount editable,
which is what the feature asks for (the payer may change only the amount).

## 11. Field 17

Table 2 marks field 17 «Оп.» (optional: present, may be only the line end) and item 4.17 reserves it; its content
column says «RFU (зарезервовано для подальшого використання)». The official examples print `RFU` in it. We leave it
**empty**, the literal reading of «Оп.» and of «зарезервовано». If a bank rejects an empty field 17, switching to
`RFU` is a one-line change (`NbuQrPayload`, element 17); the official example test already encodes `RFU` through the
same joiner.

## 12. The image

- Error correction **M** (Annex 4 item 10.4 allows M or Q; Annex 1 item 13 forbids L with the sign); M keeps the
  longest link within version 17.
- QR version **10 to 17**: Annex 1 item 5 allows 10 to 20, Annex 4 item 9 caps format 003 at 17. A link that would fit
  a smaller version is encoded as version 10; more than 17 is refused.
- A quiet zone of **4 modules** around the code.
- The white **background circle** is centred on the crossing of the code's diagonals (item 10). Its diameter is 17
  modules for version 10, 19 for 11–12, 21 for 13, 23 for 14–15 and 25 for 16–17 (item 11). The side of a code is odd
  (`4 × version + 17`, item 7), so the centre is the middle of the centre module.
- The **hryvnia sign** is inscribed in a circle 4 modules smaller with the same centre (item 12): the bounding box of
  the sign's outline has that circle's diameter as its diagonal.
- In the PDF the code is drawn as vector shapes with a **0.5 mm** module (item 18), so the code alone is at least
  28.5 mm and with the quiet zone at least 32.5 mm (version 10). `qr.png` uses the same geometry at 8 px per module.
- Annex 1 item 17 (space for the largest code): the invoice layout reserves the space of a version 17 code beside
  the totals (`NbuQrImage.maxSidePoints`) and draws the code at its top right (`NbuQrImage` reports the side of the
  code it drew, `sidePoints`).
- The link the code holds is **not printed**: three lines of base64 are noise on paper. Under «Відскануйте QR-код у
  застосунку свого банку» the invoice prints one line «Оплатити в застосунку банку» (bundle key `invoice.qr.open`),
  a PDF link annotation with a URI action to the same link, so a member reading the PDF on a phone can open the
  transfer without scanning. The link stays in the snapshot, in `qr.png` and behind the annotation.

### The sign

Annex 1 item 14 leaves the form of the sign to the National Bank. The resolution PDF has the sign only inside the
raster images of the examples (PDF pages 46, 49, 52 and 55; for example page 46 is sheet 11 of Annex 4, a
500 × 500 px PNG); it contains no vector drawing of it. So, by the owner's decision of 28.10.2026, the sign is the
outline of the glyph **U+20B4 (₴)** of the embedded IBM Plex Sans SemiBold
(`backend/src/main/resources/documents/fonts/IBMPlexSans-SemiBold.ttf`, OFL-1.1), behind a replaceable source
(`HryvniaSign`). When the owner supplies the official drawing as `docs/domain/nbu-hryvnia-sign.svg`, it is copied into
the backend resources and an SVG source replaces the glyph (`backend/src/main/java/app/nadel/documents/README.md`).

## 13. Annex 1 item 16: invoice text and QR content

Item 16 asks that the invoice text and the decoded QR content be identical. So the invoice prints the texts the QR
code carries: the IBAN, the ЄДРПОУ, the amount due and the purpose `NbuQrPayload` returns (stored in the invoice
snapshot with the link); every text has passed the same printable filter as the PDF. One difference is unavoidable: the PDF prints the cooperative's **full official name**, while the QR code carries it cut at
a word when it is longer than 140 characters (table 2) or, on the UTF-8 path, when the payload would be too long (§9).
The name is never cut in the PDF.

## 14. Code and tests

- `documents/internal/nbu/NbuQrPayload`: invoice facts → the 17 elements, the encoding, the link (§8, §9).
- `documents/internal/nbu/NbuQrImage`: the ZXing matrix (`com.google.zxing:core` 3.5.4), the version, the circles,
  vector drawing into a PDF page and the PNG (§12).
- `documents/internal/nbu/HryvniaSign`, `GlyphHryvniaSign`: the sign source (§12).
- `documents/internal/nbu/Transliteration`: the reference in ISO 646 (§8, element 11).
- `NbuQrPayloadTests`: every element against table 2 with a byte dump, example 1 re-encoded to its table 4 text, the
  amount forms, the size limits, every shortening step, the UTF-8 path, the reference.
- `NbuQrImageTests`: the circle per version, the version per link length, decoding with ZXing (`javase`, test scope)
  after the circle and the sign are drawn, as a PNG and from a PDF page rendered at 300 dpi.
- Manual: the owner scans a demo invoice with two banking apps before the merge (feature 016 acceptance criteria).
