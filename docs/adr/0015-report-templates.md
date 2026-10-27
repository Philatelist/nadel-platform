# 0015. Report templates: an own streaming OOXML filler in `reports`, Apache POI only as the recorded fallback

**Status:** accepted; introduced in phase 2 by spec 013 (`context/spec/013-reports/`)

## Context
Feature 013 lets the chairman upload an `.xlsx` report template with `${...}` placeholders (single values and one
repeating row per sheet) and fills it for a chosen period (feature document section 4). The template is the
cooperative's own layout: its styles, merged cells, column widths, print settings and formulas must survive, and the
rows below a repeating row must still add up after it has been copied once per plot, month, meter or water row.

The constraint: fastexcel (ADR 0012) writes new workbooks and reads them, but it cannot edit an existing workbook. Two
ways remain: Apache POI (XSSF), or a small filler of our own that edits the OOXML parts directly. Questions:

1. Which engine fills templates, and what decides it?
2. What exactly does the engine support, so that template authors, the field catalog and the guides can promise it?
3. How are the security rules of section 4.7 (templates are data, no formula evaluation, no macros, no external links,
   no XXE, zip-bomb limits, 2 MB cap) kept by the engine, not only by the upload checks?
4. When would we switch to POI after all?

Criteria (feature document 4.7, technical considerations 2.3):

| Criterion | Own filler | Apache POI 5.x (XSSF) |
|---|---|---|
| Styles, merged cells, formulas survive | Untouched parts are copied byte for byte; the sheet XML is rewritten event by event, so everything not named below is kept as written | Kept while POI understands the part; parts it does not model are kept as opaque XML |
| References shifted for the copied rows | Own A1 shifter (`FormulaShifter`, this ADR's spike) for every place listed in the decision | `shiftRows`/`copyRows` cover cell formulas and merged regions; the other places (charts in particular) still need own code |
| New dependencies | None: JDK StAX and `java.util.zip`, commons-compress already present (ADR 0012) | About 17 MB in about ten artifacts: `poi`, `poi-ooxml`, `poi-ooxml-lite`, `xmlbeans`, `commons-collections4`, `commons-math3`, `commons-io`, `commons-codec`, `SparseBitSet`, `log4j-api`, `curvesapi` |
| CVE record against the OSV gate (any severity fails `Security`) | No new artifact to watch | POI and its stack have a steady advisory record (for example zip-bomb and XXE denial-of-service fixes in POI, XXE in older XmlBeans, a DoS in commons-io's `XmlStreamReader`); each new advisory, however minor, turns CI red until bumped or accepted in `osv-scanner.toml` |
| Zip-bomb and XXE guards | One set: `WorkbookGuard` and `TemplateXml` (StAX, no DTD, no external entities, `<!DOCTYPE` refused) for upload and fill | POI has its own zip-bomb ratio guard and XML settings, a second set to configure and keep aligned with ours |
| Memory at 20 000 rows | Proportional to the template (at most 2 MB, its shared strings) and the output bytes; no document model | XSSF holds the whole workbook as an XmlBeans tree, several times the unpacked XML; SXSSF streams only new rows and cannot shift or copy existing template rows |

## Decision
- **The engine is our own streaming OOXML filler** in module `reports` (`internal`: `TemplateFiller`,
  `FormulaShifter`, `TemplateField`), no Apache POI. It reads the stored template with commons-compress (`ZipFile`, as
  `WorkbookGuard` does), copies every part it does not change byte for byte, rewrites the parts it changes with the
  JDK's StAX reader from `TemplateXml` and the JDK's StAX writer, and writes the result with `java.util.zip`. Nothing
  builds a document model. **POI 5.x (XSSF), pinned to one exact version, is the documented fallback** (triggers below).
- **Placeholders** match `\$\{([A-Za-z][A-Za-z0-9_.\-]*)}` in shared strings (rich-text runs are joined, the cell
  loses its run formatting and the warning `RICH_TEXT_FLATTENED` is listed) and inline strings.
  - A placeholder that is **the whole cell** becomes a typed cell keeping the cell's style: a number, or a date as an
    Excel serial number (the template author gives the cell a date format), so formulas in the template work on it.
  - A placeholder **inside text** is replaced by formatted text (Ukrainian number and date formats, section 4.1) and the
    cell is written as an inline string. `sharedStrings.xml` itself is not changed, because other cells may share the
    same string.
  - An **unknown placeholder** (not in the catalog, or a row set the app does not know) is left in the cell as written
    and listed (`UNKNOWN_PLACEHOLDER`); it is never dropped.
  - A **known placeholder whose value is unknown** (for example an unknown tree value) is written empty and listed
    (`VALUE_UNKNOWN`), never as 0.
- **One repeating row per sheet**: the row whose placeholders all belong to one row set (`plots`, `months`, `meters`,
  `water`; `TemplateRowSet`) is copied once per row of that set, the rows below move down by `copies - 1`, and with an
  empty set the row is removed and the rows below move up by one. Uploads with two sets on one row or two repeating rows
  on one sheet are already refused (`TWO_ROW_SETS`, slice 8).
- **References follow Excel's rule for inserting rows inside a range** (`FormulaShifter`):
  - A reference to a row below the repeating row moves, **absolute or not** (`$D$9` becomes `$D$11` with three copies),
    because rows are inserted, not cells moved. `$` markers are kept. Columns never change; whole-column references
    (`A:A`) stay.
  - A reference above stays; a single cell on the repeating row stays (it is the first copy).
  - A range (a cell range or a whole-row range such as `5:5`) whose last row is the repeating row or below **grows to the
    last copy**, one that starts below moves: `SUM(D5:D5)` with three copies becomes `SUM(D5:D7)`. The repeating row
    counts as inside every range that starts or ends on it.
  - With an empty set, a single cell on the removed row and a range lying only on it become `#REF!` (a sheet qualifier is
    kept: `Sheet1!#REF!`), other ranges lose the row. A row pushed past 1 048 576 becomes `#REF!`.
  - Only references to the repeated sheet move: unqualified ones in formulas on that sheet, and qualified ones
    (`Sheet1!A5`, `'My Sheet'!A5:B9`, sheet names compared ignoring case) from any sheet or defined name.
  - Left as written: string literals, error literals, function names (`LOG10(`), names that only look like references
    (`A1B`, `XFE1`), numbers, structured references (`Table1[A5]`), references into other workbooks (`[1]Sheet1!A5`) and
    three-dimensional references (`Sheet1:Sheet3!A5`). R1C1 notation does not occur: the file format stores A1 only.
  - **Inside the copies** a formula of the repeating row is pasted the way Excel fills down: relative row parts move with
    the copy (`D5*E5` is `D7*E7` in the third copy, a running total `SUM(D$5:D5)` becomes `SUM(D$5:D7)`), absolute row
    parts stay, and references below the repeating row first move by the inserted rows. Ranges do not grow inside a copy.
- **Where references are shifted**: cell formulas (`<f>`) on every sheet; **shared formulas** are first expanded into
  plain formulas (`FormulaShifter.offset`, the paste rule in both directions) for every shared group on the repeated
  sheet and every group elsewhere whose formula names the repeated sheet, because a group that crosses the repeating
  row cannot be shifted as a group; **array formulas** (`t="array"`): their text and their `ref` range, an array on the
  repeating row is copied per copy; an array range that crosses the repeating row is refused at upload (Excel refuses
  the same insertion; new `TEMPLATE_INVALID` code `ARRAY_ACROSS_ROW`, added to the contract with the filler);
  **merged ranges**: one lying only on the repeating row is copied per copy (removed with an empty set), others follow
  the range rule; the sheet **`dimension`**; **conditional formatting** and **data validation** `sqref` lists and their
  formulas; **hyperlinks** (a link on the repeating row is copied per copy); the **auto filter**; **defined names**
  (print area, print titles, user names); **table** parts (`ref` and their auto filter); **row breaks** below the
  repeating row; **drawing anchors** (images, shapes, chart frames) below the repeating row move with their rows. Row
  numbers (`<row r>`, `<c r>`) are renumbered.
- **Chart references are not shifted.** Charts are out of scope of feature 013; a template with charts that refer to
  the repeated sheet gets the warning `CHART_REFS_NOT_SHIFTED`.
- **No formula is evaluated.** `xl/calcChain.xml` is removed (with its relationship and content type override),
  `<calcPr fullCalcOnLoad="1">` is set in `workbook.xml`, and the cached values (`<v>`) of formula cells are dropped,
  so Excel and LibreOffice recalculate on opening and no stale number from the template is shown.
- **Warnings** are codes with parameters (sheet, cell, placeholder key), never display text. They go to:
  - a **warnings sheet** appended at the end when there is at least one warning, named «Попередження» or "Warnings"
    by the file language, made unique if the template already has a sheet of that name, with the texts from the
    generated-file bundles (ADR 0012 exception);
  - the response header **`X-Report-Warnings`** (the full count) and **`X-Report-Warnings-Detail`** (base64url JSON of
    at most 50 warnings, also cut so the encoded value stays under 4 KB for reverse proxies; the sheet always has all).
    Both are listed in `Access-Control-Expose-Headers` wherever the API answers cross-origin (today the web app is
    same-origin behind Caddy and the Vite proxy), so a client can read them.
- **Document properties**: `docProps/core.xml` gets the template name as the title and the version number
  (`cp:version`); the part is added if the template has none. The audit entry names the same template and version.
- **The same guards at fill time as at upload.** The stored bytes are checked again with `WorkbookGuard` and the
  template limits of `TemplateUploadChecks`, every XML part is read through `TemplateXml`, and the 20 000-row cap of
  the reports (422 `REPORT_TOO_LARGE`, counted over all row sets of the template) bounds the output, as does a bound
  of 256 MiB on what the output unpacks to (the same 422; a long text in a repeated cell cannot fill the heap).
  Macros, ActiveX, OLE objects and external links never reach a stored template (slice 8).
- **Fallback trigger.** We switch to POI if any of these holds in feature 013's filler work:
  - a reference form that real templates need (in cell formulas, shared or array formulas, merged ranges, conditional
    formatting or data validation) cannot be shifted correctly by the tokenizer without evaluating formulas;
  - a filled sample template opens in Excel or LibreOffice with a repair message that the filler cannot avoid;
  - filling 20 000 rows of 20 columns takes more than 5 s or more than about 100 MB of heap;
  - the engine (filler and shifter, without tests) grows past about 2 000 lines, which would mean rebuilding POI.
  While this ADR is not merged, the switch amends it; afterwards a new ADR supersedes it.
- **Spike result (this ADR's evidence).** `FormulaShifter` (about 460 lines with documentation, about 335 of code) and
  `FormulaShifterTests` (178 parameterised cases: references above, on and below the row, absolute and mixed markers,
  ranges growing and moving, whole rows and columns, sheet-qualified and quoted names with doubled quotes and Cyrillic,
  string and error literals, function names that look like cells, structured and external references, `sqref` lists,
  defined names, the empty set with `#REF!`, the last row, copies with fill-down and the offset that expands shared
  formulas) pass with 100 % line coverage. Nothing found triggers the fallback; the spike turned two cases into
  explicit rules above (shared formulas are expanded, an array across the row is refused).

## Consequences
- No new dependency and no new advisory surface: the `Security` job stays as it is. commons-compress stays a transitive
  dependency of fastexcel-reader used directly (ADR 0012); `reports` now uses it for reading as `xlsx` does.
- We own the OOXML details. The filler is tested with golden files (XML assertions on the rewritten parts), a round trip
  through fastexcel-reader and sample templates kept in test resources; the shifter and the filler need near-complete
  unit coverage to keep the JaCoCo floor.
- The supported feature set above is the promise: the field catalog page and both guides state it, including the
  limits (one repeating row per sheet, charts not shifted, no evaluation, `$` does not stop a reference below from
  moving, `SUM(D$5:D$5)` inside the repeating row is the first copy only).
- A generated file shows formula results only after Excel or LibreOffice recalculates it on opening; a viewer that does
  not calculate (some previewers) shows empty formula cells. This is the price of evaluating nothing on the server.
- Template authors write ordinary Excel: a totals row `SUM(D5:D5)` under a repeating row 5 sums every copy.

## Alternatives considered
- **Apache POI (XSSF)** as the engine: rejected for now by the criteria table (size, advisory record against an
  any-severity gate, a second set of guards, a document model in memory), and because it would not remove the
  reference work for charts, data validations and the other places listed. It stays the fallback.
- **POI SXSSF** (streaming writer): cannot copy or shift existing rows of a template; it only appends new rows.
- **Build the file with fastexcel from a template description** (no template file): loses the cooperative's own layout,
  styles and formulas, which is the point of templates.
- **Evaluate formulas on the server** (POI's evaluator or our own) to write cached values: rejected by section 4.7
  (templates are data, never code) and unnecessary with `fullCalcOnLoad`.
- **Docx or ODS templates, or a templating library** (JXLS, which builds on POI): out of scope; the same dependency
  objection as POI.

## Non-goals
- Formula evaluation of any kind; R1C1 notation; cached values in the output.
- More than one repeating row per sheet, nested or two-dimensional repeats, repeating columns, conditional sections,
  loops over sheets.
- Shifting references in charts, pivot tables and their caches, sparklines, slicers, data tables (`t="dataTable"`),
  three-dimensional references and references into other workbooks.
- Images or drawings copied per row: a drawing anchored on the repeating row stays on the first copy.
- Macro-enabled or legacy formats (`.xlsm`, `.xltx`, `.xls`, ODS), ActiveX, OLE objects and external links (refused or
  stripped at upload).
- Names and contacts in templates, the official tax form and PDF output (feature document section 10).
