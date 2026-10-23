# imports

Excel import of a whole cooperative in one go: plots (with their water connection), members, ownership, plot meters and
opening balances from one `.xlsx` file with five sheets (`Plots`, `Members`, `Ownership`, `Meters`, `Opening balances`;
in the Ukrainian template `Ділянки`, `Члени`, `Власність`, `Лічильники`, `Вхідне сальдо`). Requirements:
`docs/features/008-excel-import.md`, for the Meters sheet section 7 of `docs/features/010-meters-and-readings.md`, for
the water column and the Opening balances sheet sections 2.8 and 2.9 of feature 011; specs:
`context/spec/008-excel-import/`, `context/spec/010-meters-and-readings/`,
`context/spec/011-ledger-charges-period-closing/`.

The module offers the template download, the upload with a preview of all five sheets, the commit and the history of
the uploads.

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /imports/template?lang=uk\|en` | `downloadImportTemplate` | `IMPORT_RUN` (the platform operator has no permission and gets 403); the language is `lang`, else `Accept-Language`, else `uk` (see The template) |
| `POST /imports` | `uploadImport` | `IMPORT_RUN`; multipart, one `file` part; answers 201 `ImportPreview` |
| `POST /imports/{id}/commit` | `commitImport` | `IMPORT_RUN`; answers 200 `ImportResult` (see Commit) |
| `GET /imports` | `listImports` | `IMPORT_RUN`; `page` (from 0) and `size` (1 to 100, default 50) as the audit list; answers 200 `ImportPage` of `ImportSummary` (see History) |

The template is returned as `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` with
`Content-Disposition: attachment; filename="nadel-import-template-uk.xlsx"` (or `-en.xlsx`; the name is always ASCII) and
`Vary: Accept-Language`.

## The template

`TemplateWriter` (fastexcel writer) builds it in memory, in one of two languages (`TemplateLanguage`). The language is the
optional `lang` query parameter (`uk` or `en`; any other value is 400 `VALIDATION_FAILED` through the contract's `enum` and
`pattern`); without it the best match of `uk` and `en` in the `Accept-Language` header (`uk-UA` is `uk`, `en-US` is `en`,
`q` weights respected, `q=0` and malformed headers ignored); without a match (`de`, no header) the default `uk`.

Each sheet has the header row, then an instruction row, then an example row. Both of those rows start with `#`; the import
ignores every row whose first cell starts with `#`, so an unchanged template imports nothing. The first cell of the example
row reads `# example: 12a` (`# приклад: 31а`); to keep the example as data, delete that whole marker (`# example: `) from
the first cell and the row is imported. Deleting only the `#` leaves the label in the plot number.

| Sheet (English) | Columns |
|---|---|
| `Plots` | number, street, kind, area_m2, area_sotky, cadastral_number, has_building, notes, water |
| `Members` | external_id, last_name, first_name, middle_name, phones, email, postal_address, delivery_channel, telegram_username, viber_phone |
| `Ownership` | plot_number, member_external_id, share, valid_from |
| `Meters` | plot_number, resource, serial, installed_on, initial_reading |
| `Opening balances` | plot_number, charge_type, amount, date, reason |

**English template:** the sheet names above, the canonical column keys as headers, English hints and examples
(`imports/template_en.properties`).

**Ukrainian template:** everything a person reads is Ukrainian. Sheet names (`Ділянки`, `Члени`, `Власність`), headers
(`Номер ділянки`, `Вулиця`, `Тип ділянки`, `Площа, м²`, ...) and value words (`ДІЛЯНКА`, `так`, `ЕЛ. ПОШТА`) are the **first
word of each line of `imports/header-aliases.properties`**, the same vocabulary the readers accept, so the template cannot
show a word the upload refuses. The hint sentences and the example values are in `imports/template_uk.properties`; they
reach the vocabulary's words through placeholders (`{sheet.members}`, `{col.plots.area_m2}`, `{value.kind.PLOT}`). The
example is a consistent set: plot `31а` (not `12а`: the demo cooperative already has a plot `12а`), street `вулиця Садова`,
kind `ДІЛЯНКА`, 600 m2, building `так`; member key `Ч-001`, `Шевченко Тарас Григорович`, `+380 67 123 45 67`; ownership of
plot `31а` by `Ч-001`, share `1` (a single owner must hold the whole plot, rules in `plots/README.md`), since 01.03.2020;
an electricity meter (`ЕЛЕКТРОЕНЕРГІЯ`) of plot `31а`, serial `0456789`, installed 01.03.2020 at `1250,5` (the English
example: plot `12a`, `ELECTRICITY`, the same serial, date and `1250.5`); water `так` (`yes`) on the plot; an opening
balance of plot `31а` (`12a`) for `Членський внесок` (`Membership fee`, the default name of the seeded type, so the
example is a name the upload accepts) of `1500,00` (`1500.00`) as of 01.09.2026 with the reason `Борг за 2025 рік`
(`Debt for 2025`).
Missing texts or placeholders fail the build's tests, not a download.

Columns that hold identifiers or phone numbers (`number`, `phones`, `viber_phone`, `external_id`, `plot_number`,
`member_external_id`, `cadastral_number`, the meter `serial`, the opening balance `plot_number`) and the `share` have the
text format, so Excel keeps leading zeros, long digit strings and `1/2`.

## The vocabulary

`ImportVocabulary` loads `imports/header-aliases.properties` once. One line per item, words separated by `|`; the first
word is the Ukrainian label the template shows:

| Line | Meaning |
|---|---|
| `<sheet>.<column key> = label \| alias ...` | column headers of `plots`, `members`, `ownership`, `meters`, `opening` (the Opening balances sheet) |
| `sheet.<plots\|members\|ownership\|meters\|opening> = label \| alias ...` | sheet names |
| `value.kind.<PLOT\|COMMON>`, `value.channel.<EMAIL\|TELEGRAM\|VIBER\|PAPER>`, `value.yesno.<true\|false>`, `value.resource.<ELECTRICITY\|WATER>` | value words |

The upload accepts **both languages, also mixed in one file**, for all three: a sheet is found by its English name or any
listed name (case-insensitive, `WorkbookReader.readSheet(List, ...)`); headers are matched by `HeaderMatcher` after
normalisation; value words are matched ignoring case and extra spaces. `PlotsSheet`, `MembersSheet` and `MetersSheet` turn a
Ukrainian kind, delivery channel or resource word into its canonical English word (`ДІЛЯНКА` -> `PLOT`, `вода` -> `WATER`)
before the row goes to the plots, members and meters modules, which only know the English words; a word that is not in the vocabulary is passed on as typed and reported by
the owning module (`INVALID`). Yes and no (`так`/`ні`, `yes`/`no`, `true`/`false`) come from the same file (`Cells.yesNo`).

## Upload and preview

`ImportService.preview` runs in this order; only the last step uses the database and a transaction.

1. The size is checked (over 10 MB: 413 `FILE_TOO_LARGE`; the multipart limits in `application.yml` and the container
   answer the same way before the controller is reached).
2. `ZipGuard` refuses anything that is not a safe `.xlsx` (400 `FILE_NOT_XLSX`): wrong extension, no zip signature (so
   also the old `.xls` and encrypted workbooks, which are OLE2 files), a corrupt archive, missing
   `[Content_Types].xml` or `xl/workbook.xml`, more than 200 entries, an entry over 50 MB or an archive over 100 MB once
   unpacked. Unpacked sizes are counted from the bytes decompression really produces, never from the sizes the archive
   declares (a zip bomb declares what it likes). The archive is read with commons-compress, which the spreadsheet
   library brings along; the JDK's `ZipInputStream` cannot read files written with data descriptors (as fastexcel does).
3. `WorkbookReader` (the only user of the spreadsheet library) streams the rows; `PlotsSheet`, `MembersSheet` and
   `OwnershipSheet` find the columns with `HeaderMatcher`, read every cell with `Cells` and stop with 400
   `IMPORT_LIMIT_EXCEEDED` at the 2,001st plot row, the 3,001st member row or the 6,001st ownership row. The workbook's
   date system (1900 or 1904) comes from `WorkbookReader.date1904()` and is used for the Excel dates of `valid_from`,
   `installed_on` and the opening balance `date`. `MetersSheet` stops at the 2,001st meter row, `OpeningBalancesSheet`
   at the 6,001st opening balance row. A workbook without a `Members` sheet is fine (plots only); a `Members` sheet needs
   its header (`external_id`, `last_name` and `first_name` are required columns). `phones` is split at commas and
   semicolons; a phone cell that Excel stored as a number has lost its leading zero, so nine digits get the `0` back.
   Anything the library throws on a damaged file, a DOCTYPE included (DTDs are off), becomes `FILE_NOT_XLSX`. File
   names, sheet names and cell values are never logged.
4. In one transaction: the expired previews of the cooperative lose their plan (there is no scheduled job yet), the plots,
   members, meters and ledger modules plan the rows (`plots.PlotImport`, `members.MemberImport`, `meters.MeterImport`,
   `meters.WaterConnectionImport`, `ledger.OpeningBalanceImport`), and an `import_run` row is inserted. Nothing else is saved: a preview
   creates no plot and no audit entry.

The preview lists the problems found (at most 1,000; `problemCount` is the total) with sheet, Excel row (0 for the file or
the whole sheet), canonical column and code. A row with a problem is not counted. `committable` is true only without
problems and with something to create or update, so the unchanged template and a workbook that is already imported are
not committable. The run is `PREVIEW` (with the normalised rows in `plan`, valid 30 minutes on the Kyiv clock) or
`HAS_ERRORS` (no plan).

Members are matched among the active members by e-mail when the row has one, otherwise by last name, first name and
first phone; a blank cell keeps the stored value; `MEMBER_KEY_MISSING`, `MEMBER_AMBIGUOUS`, `EMAIL_TAKEN` and
`DUPLICATE_IN_FILE` are described in `members/README.md` and in the contract (`uploadImport`). The planner maps the
fields of the plan to canonical columns (`lastName` to `last_name`, `phones` to `phones`, ...) and keeps the member of
every importable row by its `external_id` in lower case (an existing member or the deterministic id of a new one), for
the Ownership sheet.

### Ownership

The Ownership sheet lists the current owners of plots (`plot_number`, `member_external_id`, `share`, `valid_from`; all
four columns are required, the sheet itself may be missing). The planner turns each row's key into a member (ignoring
case) and asks `plots.PlotImport.planOwnership`, which groups the rows by plot and decides per plot: first owners
(`FIRST_OWNERS`, counted as created ownership rows), the recorded owners are exactly the file's members and shares
(`SKIP`, counted as unchanged; dates are ignored) or `OWNERSHIP_CONFLICT`. The import never changes existing ownership,
so `ownerships.updated` is always 0. The rules and codes are in `plots/README.md` and in the contract (`uploadImport`).

How the cascades are avoided (one mistake is reported once, where it is):

- a key with no row in the Members sheet is `UNKNOWN_MEMBER_KEY` on `member_external_id`; a key whose Members row has a
  problem is not reported again on the ownership rows (the Members sheet already lists it), and a plot with such a
  row is not checked any further until the member row is fixed;
- a plot whose Plots row has a problem (also a problem found while reading the row) is not checked at all;
- an unreadable `valid_from` is `DATE_INVALID` and is not also reported as `REQUIRED`.

### Meters

The Meters sheet (feature 010) lists plot meters only (the electricity tree is set up on its screen); all five columns are
required and the sheet itself may be missing. `MetersSheet` reads the cells (an unreadable `installed_on` is `DATE_INVALID`,
an unreadable `initial_reading` `NUMBER_INVALID`); `meters.MeterImport.plan` decides per row, with the Plots sheet's rows
and plan (the effective one, as for ownership):

- `REQUIRED` (any column; not repeated on a cell that already has a reading problem), `INVALID` (`resource` other than
  `ELECTRICITY`/`WATER` or their Ukrainian words), `TOO_LONG` (`serial` over 50 characters), `METER_VALUE_INVALID`
  (`initial_reading` negative, over three decimals or eleven integer digits; the value is a `meters.MeterValue`, never
  rounded), `READING_DATE_IN_FUTURE` (`installed_on` after today on the Kyiv `Clock`);
- `DUPLICATE_IN_FILE` on `resource`: an earlier row without problems names the same plot (ignoring case) and resource;
- the plot: a plot of the Plots sheet (its kind from the row, else the stored one) or an active plot, else `UNKNOWN_PLOT`;
  common land is `PLOT_COMMON_LAND`; a plot whose own Plots row has a problem is not checked further;
- against the stored meters: no active meter of the plot and resource is `NEW` (`meters.created`); an active meter with
  the same serial (ignoring case and spaces) is `UNCHANGED` (`meters.unchanged`; its dates and reading are not compared),
  so a second upload of the same file changes nothing; any other active meter is `METER_ACTIVE_EXISTS` on `serial` with
  `params.plot` (the stored number): the import never replaces a meter, that is done in the register. Meters are never
  updated (`meters.updated` is 0).

A new meter's id is derived from the import id, the plot number (lower case) and the resource, so the commit's second
planning gives the same ids.

### Water column (feature 011)

The optional `water` column of the Plots sheet (`Вода`) connects plots to water. `PlotsSheet` keeps the cell as typed;
the planner reads it: a yes word (`так`, `yes`, `true`, a boolean cell) asks for the cooperative's water supply, a no
word (`ні`, `no`, `false`) and an empty cell ask for nothing, and any other text is the name of a water source (an active
common consumer meter, a well). The import never disconnects: "ні" on a connected plot leaves it connected, and so does
an empty cell; a disconnection is made on the plot card or the water source. The cells that ask for a connection go to
`meters.WaterConnectionImport.plan` with the Plots sheet's rows and effective plan; its problems are reported on the
Plots row, column `water`: `WATER_SOURCE_INVALID` (no active common consumer meter has the name),
`WATER_SOURCE_AMBIGUOUS` (two or more do), `WATER_ALREADY_CONNECTED` (a lasting connection to another source, or a
connection that ends today or later), `PLOT_COMMON_LAND`. A row whose plot row has another problem is not checked. A
plot with a lasting connection is unchanged for a yes and for the name of its current source
(`waterConnections.unchanged`); a new connection is `waterConnections.created` and starts on the day of the commit
(Europe/Kyiv). Rules and locks: `meters/README.md` (Water connections).

### Opening balances (feature 011)

The Opening balances sheet (`Вхідне сальдо`) may be missing; a sheet that is there needs `plot_number`, `charge_type`,
`amount` and `date` (`HEADER_MISSING` otherwise); `reason` is optional. `OpeningBalancesSheet` reads the cells (an
unreadable `amount` is `NUMBER_INVALID`, an unreadable `date` `DATE_INVALID`, neither then also `REQUIRED`);
`ledger.OpeningBalanceImport.plan` decides per row with the rules of an opening balance entered on the plot account
(`ledger/README.md`, Opening balances from the import): `REQUIRED`, `AMOUNT_NOT_POSITIVE`, `INVALID` (more than two
decimals or 12 integer digits; on `charge_type` a name of two types), `DATE_IN_FUTURE`, `TOO_LONG` (`reason` over 500),
`UNKNOWN_PLOT`, `PLOT_COMMON_LAND`, `UNKNOWN_CHARGE_TYPE`, `CHARGE_TYPE_ARCHIVED`, `DUPLICATE_IN_FILE` (on
`charge_type`: the same plot, charge type and date as an earlier row) and `OPENING_BALANCE_EXISTS` (on `amount`,
`params.amount`: the plot has a standing opening balance of the charge type and date with another amount). The charge
type is named as the cooperative calls it, or by the English or Ukrainian default name of a seeded type that was never
renamed. A row whose plot already has a standing opening balance with the same charge type, date and amount is
unchanged (`openingBalances.unchanged`), so the same file uploaded again books nothing twice; any other row is
`openingBalances.created`. A balance is booked in the month of its date or the earliest open month after it, with the
key `OPENING:{importId}:{row}`; without a reason that key is the reference to the import.

### Stored plan and digest

The stored `plan` (version 1, now with a fifth array) holds the normalised rows: `plots` (with the `water` cell as typed),
`members`, `ownership` (row, plot number, member key as typed, share, `valid_from` as an ISO date), `meters` (row, plot
number, resource, serial, `installedOn` as an ISO date, `initialReading` as plain decimal text) and `openingBalances`
(row, plot number, charge type as typed, `amount` as plain decimal text, `date` as an ISO date, reason). A plan stored
before the Meters sheet existed has no `meters` and reads as an empty sheet; one stored before feature 011 has no
`water` cells and no `openingBalances`. Member ids are never stored: they are derived
again when the plan is recomputed. `PlanDigest` computes the SHA-256 of a canonical text of the whole plan and the
preview stores it in `import_run.plan_digest` (null for a run with problems). One line per plot row, member row,
ownership row, meter row, water cell that asks for a connection (the plot, the action and the source the plan found)
and opening balance row (the plot, the action, the charge type id, the amount, the date and the reason) with the sheet,
the natural key (lower case), the action or outcome, the id of the matched record or `new`,
the changed field names and the values of the file; the lines are sorted, so the digest does not depend on the order of
the rows; ids of records the import creates are left out because they depend on the import's own id. The commit slice
recomputes the plan from the stored rows and refuses the preview as stale when the digest differs.

Rules for reading a sheet: the header is the first row that is not empty and not a comment; a row whose first cell
starts with `#` and an empty row are skipped; Excel row numbers are kept. Cells: text is trimmed; a number cell is
cleaned of binary floating point noise (15 significant digits, so `0.059999999999999998` is `0.06`); text numbers accept
a comma as the decimal separator; yes/no accepts yes, no, так, ні, true and false in any case; a date is an Excel
date (a number in a cell formatted as a date) or text `DD.MM.YYYY`. The area is given in exactly one of `area_m2` and
`area_sotky` (1 sotka = 100 m2, computed exactly); both filled is `AREA_BOTH_OR_NONE`, and so is none for a new plot.

## Commit

`ImportCommitter.commit(id, actor)` saves a previewed import in ONE transaction (the tenant-aware transaction manager),
or saves nothing. In this order:

1. `pg_advisory_xact_lock(hashtextextended(tenant || '/imports', 0))`: two commits of one cooperative never interleave
   (the key differs from the chairman lock of `members`).
2. The run row `FOR UPDATE` (RLS hides other cooperatives' runs: 404), then the checks that need nothing from the
   other modules: `COMMITTED` is 409 `IMPORT_ALREADY_COMMITTED`, `HAS_ERRORS` is 409 `IMPORT_HAS_ERRORS`, `STALE` is 409
   `IMPORT_STALE`, a `PREVIEW` past `expires_at` (Kyiv `Clock`; also one whose plan was already dropped) is 410
   `IMPORT_EXPIRED`, and a preview with nothing to create or update is 409 `IMPORT_NOTHING_TO_COMMIT` (an accurate code
   of its own: a commit would only add a no-op audit entry; `IMPORT_HAS_ERRORS` would be false).
3. The stored rows are read back (`StoredPlan`) and planned once to learn which existing plots, meters and members they
   touch; the persistence context is cleared; `PlotImport.lock` takes those plots `FOR UPDATE`, then `MeterImport.lock`
   the stored plots that get a new meter `FOR SHARE` (the lock of a registration by hand) and the unchanged meters
   `FOR UPDATE`, then `WaterConnectionImport.lock` the stored plots that get a connection `FOR SHARE` and the
   connections of the planned plots `FOR UPDATE`, then `OpeningBalanceImport.lock` the stored plots that get an opening
   balance and their charge types `FOR SHARE`, then `MemberImport.lock` those members, each in id order. The order is that of a change of owner (plot first, then its owners) and of archiving
   (member row), so no lock cycle exists; the advisory lock serialises two imports. The clear matters: without it the
   second planning would read the first one's managed entities instead of the locked rows.
4. The rows are planned again under the locks and the digest is compared with the stored one: a different digest, or any
   problem, is `IMPORT_STALE` (a plot with one of the file's numbers created meanwhile, an owner changed, a member
   archived, a meter registered, removed or replaced, a plot connected to water, an opening balance entered or
   reversed, a charge type renamed or archived, ...).
5. `MemberImport.apply` (members), then `PlotImport.apply` (plots with their GREY status rows, plot updates, then first
   owners with the status refresh), then `MeterImport.apply` (the new plot meters, after the plots they may belong to),
   then `WaterConnectionImport.apply` (connections from the day of the commit), then `OpeningBalanceImport.apply` (the
   balances, one `ledger.ChargesBooked` for their plots), each record audited as when entered by hand
   (`MEMBER_CREATED`/`MEMBER_UPDATED`, `PLOT_CREATED`/`PLOT_UPDATED`, `OWNERSHIP_CHANGED`, `METER_REGISTERED`,
   `WATER_CONNECTED`, `OPENING_BALANCE_ENTERED`). The facades **return** their audit events instead of publishing
   them; `ImportCommitter` publishes them as `audit.AuditBatch` events of at most 500 entries (members first, then plots
   and owners), in the commit transaction, so the outbox stores them atomically with the data. 7,000 records are 14
   batches, not 7,000 events: the audit listener writes a batch with one JDBC batch insert in one transaction (see the
   `audit` README). Then one `IMPORT_COMMITTED` event (a normal single `AuditEvent`, exactly one per import) (entity type `IMPORT`, entity
   id = import id, label = file name, fields `plotsCreated`, `plotsUpdated`, `plotsUnchanged`, `membersCreated`, ...,
   `ownershipsCreated`, `ownershipsUnchanged`, `metersCreated`, `metersUnchanged`, `waterConnectionsCreated`,
   `waterConnectionsUnchanged`, `openingBalancesCreated`, `openingBalancesUnchanged` as text). New rows are persisted (no SELECT per row) with Hibernate
   batching (`jdbc.batch_size: 50`, `order_inserts`, `order_updates`). Finally the run becomes `COMMITTED` with
   `committed_at` and its plan dropped.

A unique or exclusion violation while saving (plot number, cadastral number, member e-mail, ownership overlap, a second
active meter of a plot and resource) is translated by the facades into 409 `IMPORT_STALE`. Any other exception rolls everything back, audit events and outbox
rows included, and leaves the run as a `PREVIEW` that can be tried again.

A stale or expired refusal is also recorded: after the main transaction rolled back, a short `REQUIRES_NEW`
transaction marks a stale run `STALE` (plan and digest dropped) or drops the plan of an expired one (it stays `PREVIEW`
and is derived as expired). The expired plans of the cooperative are also cleared lazily on every upload, on every
successful commit and in the outcome transaction of a stale or expired refusal. The clearing at the start of a commit
that is refused any other way is rolled back with it (there is no scheduled job yet).

Facade locking and apply contracts live in `members/README.md` and `plots/README.md`.

Hibernate would log a failed batch with the values of the row, so `application.yml` turns the `org.hibernate.orm.jdbc.error`
logger off and sets the driver property `logServerErrorDetail=false` (the constraint name is still in the message).

Tests: `ImportPlotsWaterTests` (the water column in both languages, no and empty leave a plot as it is, a re-import,
every problem code, a stale preview), `ImportOpeningBalancesTests` (the sheet in both languages, booking, a re-import,
a reversed balance imported again, a renamed seeded type, every problem code, missing headers, a stale preview),
`ImportCommitTests` and `ImportConstraintViolationTests` (a rule broken between the plan and the flush is 409
`IMPORT_STALE`, for each of the four constraints, through the batched flush). `ImportLargeCommitTests` (a file at the
limits) is tagged `large`; it runs in `make test` by default and can be skipped with `-DexcludedGroups=large`.

## History

`ImportHistory.list` returns the uploads of the caller's cooperative (tenant from the token, RLS as well), newest first
(`created_at desc`, then `id desc`, so equal instants page stably; the index `import_run_tenant_created_idx` of V9 serves
the order). The query selects only the history columns (`ImportHistoryRow`): the stored plan, which holds personal data,
is never loaded or returned. An `ImportSummary` has the id, file name, `uploadedBy`, `uploadedAt`, `committedAt` (only
for a committed import), `status`, `counts` and `problemCount`.

- `uploadedBy` is the member's current display name, resolved as in the audit log (`audit.ActorNames`); a member that no
  longer exists keeps the name saved with the upload.
- `status` is `PREVIEW`, `HAS_ERRORS`, `COMMITTED`, `STALE` or `EXPIRED`. `EXPIRED` is derived, never stored: a stored
  `PREVIEW` is shown as `EXPIRED` once the Kyiv `Clock` is after its `expires_at` (the rule of the commit), whether or not
  its plan has been cleared yet.

## Header aliases and the Ukrainian exception

Headers (and now sheet names and value words) of either language are accepted; see The vocabulary above. The two resource
files `imports/header-aliases.properties` (the vocabulary) and `imports/template_uk.properties` (the hint sentences and the
examples of the Ukrainian template) are the exceptions to the "everything in the repository is English" rule, next to the
UI catalogs; recorded in ADR 0012. `template_en.properties` is English like the rest.

## Libraries

`org.dhatim:fastexcel` writes the template, `org.dhatim:fastexcel-reader` streams uploaded workbooks. Both are pinned to the same exact version (`fastexcel.version` in `pom.xml`).

Table: `import_run` (V9, RLS `tenant_isolation`; the application role cannot delete rows, the history is kept). It stores the
author as a snapshot (member id, Keycloak subject, display name), the counts, the problem count and, for a preview that
can still be committed, the normalised rows (`plan`, personal data, cleared when no longer needed).

Depends on `identity`, `problem`, `tenancy`, `plots`, `members`, `meters` (`MeterImport`, `WaterConnectionImport`),
`ledger` (`OpeningBalanceImport`) and `audit` (it publishes `IMPORT_COMMITTED`).
