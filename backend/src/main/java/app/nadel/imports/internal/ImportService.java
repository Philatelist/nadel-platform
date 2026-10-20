package app.nadel.imports.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import nadel.contract.model.ImportCounts;
import nadel.contract.model.ImportEntityCounts;
import nadel.contract.model.ImportPreview;
import nadel.contract.model.ImportProblem;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload and preview of an import file. Checking the file and reading the workbook need no database, so they run
 * outside any transaction; planning against the cooperative's data and recording the run happen in one.
 * <p>
 * File names, sheet names and cell values are never logged.
 */
@Service
class ImportService {

    /** The size limit also enforced by the multipart configuration; checked again here for clients that bypass it. */
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final Duration PREVIEW_VALID_FOR = Duration.ofMinutes(30);
    /** The preview lists at most this many problems; the count says how many there are. */
    static final int MAX_LISTED_PROBLEMS = 1000;

    private static final int MAX_FILE_NAME_CODE_POINTS = 255;
    private static final String DEFAULT_FILE_NAME = "import.xlsx";
    /** Control and invisible formatting characters (bidirectional overrides, zero-width marks). */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private final ZipGuard zipGuard = new ZipGuard();
    private final ImportPlanner planner;
    private final ImportRunRepository runs;
    private final TransactionTemplate transaction;
    private final Clock clock;

    ImportService(ImportPlanner planner, ImportRunRepository runs, PlatformTransactionManager transactions,
            Clock clock) {
        this.planner = planner;
        this.runs = runs;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /**
     * Checks and reads the file, plans it against the data of the caller's cooperative and records the run. Nothing
     * else is saved.
     *
     * @throws ApiProblemException 413 {@code FILE_TOO_LARGE}; 400 {@code FILE_NOT_XLSX}, {@code IMPORT_LIMIT_EXCEEDED}
     */
    ImportPreview preview(MultipartFile file, AccessProfile actor) {
        String fileName = baseName(file.getOriginalFilename());
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiProblemException(HttpStatus.CONTENT_TOO_LARGE, ProblemCodes.FILE_TOO_LARGE,
                    "The uploaded file is too large.");
        }
        byte[] content = read(file);
        zipGuard.check(fileName, content);
        PlotsSheet.Parsed plots;
        MembersSheet.Parsed members;
        OwnershipSheet.Parsed ownership;
        try (WorkbookReader reader = WorkbookReader.open(content)) {
            plots = PlotsSheet.parse(reader);
            members = MembersSheet.parse(reader);
            ownership = OwnershipSheet.parse(reader);
        }

        UUID importId = UUID.randomUUID();
        return transaction.execute(status -> {
            Instant now = clock.instant();
            runs.clearExpiredPlans(actor.tenantId(), now);
            ImportPlan plan = planner.plan(actor.tenantId(), importId, plots, members, ownership);
            ImportRun run = runs.save(ImportRun.create(importId, actor.tenantId(), fileName, actor.memberId(),
                    actor.subject(), actor.displayName(), now, now.plus(PREVIEW_VALID_FOR),
                    plan.hasProblems() ? ImportStatus.HAS_ERRORS : ImportStatus.PREVIEW, plan.counts().toMap(),
                    plan.problems().size(), plan.hasProblems() ? null : plan.rows(), plan.digest()));
            return toPreview(run, plan);
        });
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ZipGuard.notXlsx();
        }
    }

    /** The last path segment without control characters, at most 255 characters; never empty. */
    static String baseName(@Nullable String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = INVISIBLE.matcher(name).replaceAll("").strip();
        if (name.codePointCount(0, name.length()) > MAX_FILE_NAME_CODE_POINTS) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_FILE_NAME_CODE_POINTS));
        }
        return name.isEmpty() ? DEFAULT_FILE_NAME : name;
    }

    private static ImportPreview toPreview(ImportRun run, ImportPlan plan) {
        List<ImportProblem> listed = plan.problems().stream()
                .limit(MAX_LISTED_PROBLEMS)
                .map(ImportService::toApi)
                .toList();
        return new ImportPreview()
                .id(run.getId())
                .fileName(run.getFileName())
                .expiresAt(OffsetDateTime.ofInstant(run.getExpiresAt(), ZoneOffset.UTC))
                .counts(toApi(plan.counts()))
                .problems(listed)
                .problemCount(plan.problems().size())
                .committable(plan.committable());
    }

    static ImportProblem toApi(RowProblem problem) {
        ImportProblem api = new ImportProblem()
                .sheet(ImportProblem.SheetEnum.valueOf(problem.sheet().name()))
                .row(problem.row())
                .column(problem.column())
                .code(problem.code());
        if (!problem.params().isEmpty()) {
            api.params(problem.params());
        }
        return api;
    }

    static ImportCounts toApi(PlanCounts counts) {
        return new ImportCounts()
                .plots(toApi(counts.plots()))
                .members(toApi(counts.members()))
                .ownerships(toApi(counts.ownerships()));
    }

    private static ImportEntityCounts toApi(PlanCounts.EntityCounts counts) {
        return new ImportEntityCounts()
                .created(counts.created())
                .updated(counts.updated())
                .unchanged(counts.unchanged());
    }
}
