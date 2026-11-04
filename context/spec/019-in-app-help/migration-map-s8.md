# Migration map, Slice 8 (tariffs, charges, meters)

| Old section (board guide) | New page(s) |
|---|---|
| 8 Тарифи (intro) | `tariffs` |
| 8 / Види нарахувань | `charge-types`, `tariffs` |
| 8 / Новий тариф (incl. the table of bases, frequency, rate precision) | `tariff-form` |
| 8 / Версії тарифу | `tariff-card` |
| 8 / До яких ділянок застосовується | `tariff-card` |
| 9 Лічильники та дерево лічильників (intro) | `meters` |
| 9 / Види лічильників | `meters` |
| 9 / Зареєструвати, замінити, зняти | `meters` (register), `meter-card` (replace, wrap-around, remove, delete) |
| 9 / Дерево лічильників | `meter-tree` |
| 10 Покази: обхід і підтвердження (intro) | `readings-round` |
| 10 / Обхід електрика | `readings-round` |
| 10 / Покази від членів кооперативу | `readings-approvals` |
| 10 / Виправлення | `meter-card`, `recalculation-new` |
| 22 Нарахування й закриття місяців (screen parts: «На екрані: Нарахування», «Не нараховано», «Закриття місяця», sources of charges, reversal, `Сторно й черга`) | `charges`, `charges-month`, `not-charged` |
| 22 / Рахунок ділянки, сторно та вхідне сальдо | `charges` (reversal, opening balance sources); the plot account panel itself belongs to `plot-card` (Slice 7) |
| 22 / Світло за показом, Місяці та закриття, Борг (calculation) | worked examples kept in `readings-round` and `charges-month`; the rules belong to `how-readings-consumption`, `how-charges-months` (Slice 10) |
| 22 / Кольори ділянок на мапі й у списку | not placed here: Slice 7 (`plots`) / Slice 10 |
| 22 / Казначей і член кооперативу | `charges` (Who sees it) |
| 23 Втрати в мережі та спільні споживачі (screen part «На екрані») | `losses`, `common-consumers` |
| 23 / Втрати: два режими, приклад квартал і підстанція | `losses` (examples kept), rules in `how-losses-common` (Slice 10) |
| 23 / Спільні споживачі — окремим рядком (table of splits) | `common-consumers` |
| 24 Вода та підключення | `charge-types` (activate «Вода»), `tariff-form` (trap), `common-consumers` (well pump), `readings-round` (water note); the plot's «Вода» panel belongs to `plot-card` (Slice 7), the rules to `how-water-connection` (Slice 10) |
| 25 Виправлення і перерахунок | `recalculations`, `recalculation-new`, `recalculation-card`, `tariff-card` (via a tariff version), `meter-card` (via a reading) |
