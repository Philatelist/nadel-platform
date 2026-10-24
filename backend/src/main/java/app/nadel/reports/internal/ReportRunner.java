package app.nadel.reports.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.reports.internal.ReportAuditPublisher.Output;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ChargeTypeRef;
import nadel.contract.model.ReportCell;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumn;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportInfo;
import nadel.contract.model.ReportNote;
import nadel.contract.model.ReportPreview;
import nadel.contract.model.ReportRow;
import nadel.contract.model.ReportSheet;
import nadel.contract.model.ReportTotal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a report for the caller: checks that they may run it, reads the filters, loads every sheet, refuses a sheet
 * over the row cap (422 {@code REPORT_TOO_LARGE}), makes the preview or the workbook and, last, publishes the audit
 * entry. One plain transaction per run, not read-only: the outbox stores the audit publication in it. The entry is
 * published only after the output was made, so a refused run or a failure while writing leaves no entry (the
 * transaction rolls back with the publication).
 */
@Component
class ReportRunner {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private final ReportCatalog catalog;
    private final CurrentUser currentUser;
    private final ChargeTypeCatalog chargeTypes;
    private final TenantDirectory tenants;
    private final XlsxReportWriter writer;
    private final ReportAuditPublisher audit;
    private final ReportLimits limits;
    private final Clock clock;

    ReportRunner(ReportCatalog catalog, CurrentUser currentUser, ChargeTypeCatalog chargeTypes,
            TenantDirectory tenants, XlsxReportWriter writer, ReportAuditPublisher audit, ReportLimits limits,
            Clock clock) {
        this.catalog = catalog;
        this.currentUser = currentUser;
        this.chargeTypes = chargeTypes;
        this.tenants = tenants;
        this.writer = writer;
        this.audit = audit;
        this.limits = limits;
        this.clock = clock;
    }

    /** The reports the caller may run, with the defaults of today. */
    List<ReportInfo> list() {
        return catalog.infos(currentUser.profile(), LocalDate.now(clock.withZone(KYIV)));
    }

    /** The preview: per sheet the first rows, the totals over every row and the row count. Audited as PREVIEW. */
    @Transactional
    public ReportPreview preview(ReportCode code, RawFilters raw) {
        Run run = load(code, raw);
        ReportPreview preview = new ReportPreview(code, run.startedAt().atZone(KYIV).toOffsetDateTime(),
                run.filters().values(), run.result().notes().stream()
                        .map(note -> new ReportNote(note.code(), note.params())).toList(),
                chargeTypeRefs(run), run.result().sheets().stream().map(ReportRunner::sheet).toList());
        publish(run, Output.PREVIEW);
        return preview;
    }

    /** The workbook with every row, its texts in {@code language}. Audited as XLSX. */
    @Transactional
    public ReportFile file(ReportCode code, RawFilters raw, ReportLanguage language) {
        Run run = load(code, raw);
        ReportTexts texts = ReportTexts.of(language);
        String cooperative = tenants.current().map(TenantSummary::name).orElse("");
        byte[] content = writer.write(run.definition(), run.filters(), run.result(),
                new XlsxReportWriter.Header(cooperative, run.startedAt().atZone(KYIV), run.caller().displayName()),
                texts, chargeTypeNames(run.context(), texts));
        ReportFilters.Period range = run.definition().filenameRange(run.filters());
        publish(run, Output.XLSX);
        return new ReportFile(code.getValue() + "_" + range.from() + "_" + range.to() + ".xlsx", content);
    }

    private Run load(ReportCode code, RawFilters raw) {
        AccessProfile caller = currentUser.profile();
        ReportDefinition definition = catalog.require(code, caller);
        Instant startedAt = clock.instant();
        int cap = limits.rowCap();
        ReportContext context = new ReportContext(LocalDate.ofInstant(startedAt, KYIV), cap + 1, chargeTypes.all());
        ReportFilters filters = ReportFilters.parse(definition.filters(), raw, context);
        ReportResult result = definition.load(filters, context);
        if (result.sheets().stream().anyMatch(sheet -> sheet.rows().size() > cap)) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.REPORT_TOO_LARGE,
                    "The report is too large. Narrow the filters.");
        }
        return new Run(caller, definition, context, filters, result, startedAt);
    }

    private void publish(Run run, Output output) {
        int rows = run.result().sheets().stream().mapToInt(sheet -> sheet.rows().size()).sum();
        audit.publish(run.caller(), run.definition().code(), output, run.filters().values(), rows,
                run.definition().personalData());
    }

    private static ReportSheet sheet(Sheet sheet) {
        List<Column> columns = sheet.columns();
        List<ReportRow> rows = sheet.rows().stream().limit(ReportLimits.PREVIEW_ROWS)
                .map(row -> new ReportRow(row.kind(), row.level(), cells(columns, row.cells()))).toList();
        return new ReportSheet(sheet.key(), columns.stream().map(ReportRunner::column).toList(), rows,
                sheet.totals().stream().map(total -> new ReportTotal(total.key(), cells(columns, total.cells())))
                        .toList(),
                sheet.rows().size(), sheet.rows().size() > rows.size());
    }

    private static ReportColumn column(Column column) {
        return new ReportColumn(column.key(), column.type()).chargeTypeId(column.chargeTypeId());
    }

    private static List<ReportCell> cells(List<Column> columns, List<Cell> cells) {
        return IntStream.range(0, columns.size()).mapToObj(index -> {
            Cell cell = cells.get(index);
            return new ReportCell().value(cell.json(columns.get(index).type())).reason(cell.reason());
        }).toList();
    }

    /** The charge types the preview refers to (cells, columns and the filter), in their configured order. */
    private static List<ChargeTypeRef> chargeTypeRefs(Run run) {
        Set<UUID> used = new HashSet<>(run.filters().chargeTypeIds());
        for (Sheet sheet : run.result().sheets()) {
            List<Column> columns = sheet.columns();
            for (int index = 0; index < columns.size(); index++) {
                Column column = columns.get(index);
                if (column.chargeTypeId() != null) {
                    used.add(column.chargeTypeId());
                }
                if (column.type() == ReportColumnType.CHARGE_TYPE) {
                    int at = index;
                    sheet.rows().stream().map(Row::cells).map(cells -> cells.get(at).value())
                            .filter(UUID.class::isInstance).map(UUID.class::cast).forEach(used::add);
                }
            }
        }
        return run.context().chargeTypes().stream().filter(type -> used.contains(type.id())).map(type -> {
            ChargeTypeRef ref = new ChargeTypeRef(type.id(), type.name(), type.nameCustomized());
            if (type.code() != null) {
                ref.setCode(ChargeTypeCode.fromValue(type.code()));
            }
            return ref;
        }).toList();
    }

    /** A charge type's name in the file: a customised name as stored, a seeded one's default in the language. */
    private static Function<UUID, String> chargeTypeNames(ReportContext context, ReportTexts texts) {
        Map<UUID, ChargeTypeFacts> byId = context.chargeTypes().stream()
                .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
        return id -> {
            ChargeTypeFacts type = byId.get(id);
            if (type == null) {
                return id.toString();
            }
            return type.code() != null && !type.nameCustomized()
                    ? texts.text("chargeTypes.defaultNames." + type.code()) : type.name();
        };
    }

    /** One run: who, which report, the context, the filters as read and what was loaded. */
    private record Run(AccessProfile caller, ReportDefinition definition, ReportContext context,
            ReportFilters filters, ReportResult result, Instant startedAt) {
    }
}
