# xlsx

Safety checks for uploaded `.xlsx` workbooks, run before any workbook parser sees the file. No tables, no endpoints, no
HTTP semantics and no dependencies on other modules. Moved out of `imports` (where it was `ZipGuard`) by feature 013, so
the Excel import and the report templates share one implementation (ADR 0012).

| Type | What it is |
|---|---|
| `WorkbookGuard` | `withDefaultLimits()` (200 entries, 50 MB per entry, 100 MB in total) or `withLimits(entries, entryBytes, totalBytes)` for callers that accept only smaller files. `check(fileName, content)` refuses: a name that does not end with `.xlsx` (ignoring case); content without the zip signature `PK\3\4` (so also the old `.xls` and encrypted workbooks, which are OLE2 files); an archive without `[Content_Types].xml` or `xl/workbook.xml`; more entries than the limit; an entry or the whole archive unpacking over its limit; a corrupt, truncated or unreadable archive. Immutable and thread-safe |
| `UnsafeWorkbookException` | The refusal. `reason()` is an `UnsafeWorkbookReason`; the message is a fixed English sentence without the file name or anything read from the file |
| `UnsafeWorkbookReason` | `WRONG_EXTENSION`, `NOT_A_ZIP`, `NOT_A_WORKBOOK`, `TOO_MANY_ENTRIES`, `ENTRY_TOO_LARGE`, `TOTAL_TOO_LARGE`, `UNREADABLE` |

Unpacked sizes are counted from the bytes decompression really produces, never from the sizes the archive declares (a
zip bomb declares what it likes). The archive is read in memory with commons-compress `ZipFile`, which arrives
transitively with `fastexcel-reader`; the JDK's `ZipInputStream` cannot read archives written with data descriptors, as
fastexcel writes them. The guard does not parse XML: a `DOCTYPE` or an external entity is the parser's concern.

Each caller maps the refusal to its own problem: `imports` answers 400 `FILE_NOT_XLSX` for every reason; report
templates (feature 013) answer `TEMPLATE_INVALID` and enforce their own upload size cap before calling the guard.
