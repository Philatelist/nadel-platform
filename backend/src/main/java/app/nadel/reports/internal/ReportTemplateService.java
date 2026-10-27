package app.nadel.reports.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeTypeCatalog;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import nadel.contract.model.ReportTemplate;
import nadel.contract.model.ReportTemplateField;
import nadel.contract.model.ReportTemplateSlot;
import nadel.contract.model.ReportTemplateStatus;
import nadel.contract.model.ReportTemplateUploadResult;
import nadel.contract.model.ReportTemplateVersion;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * The template store (feature 013, functional spec 2.11 and 2.12): the list with the fixed tax report slot, uploads of
 * new templates and new versions, the versions, the archive and the download of a version's file.
 * <p>
 * The cooperative always comes from the caller's {@link AccessProfile} (the token), never from the request; the
 * controller has checked the permission. An uploaded file is checked by {@link TemplateUploadChecks} before any
 * transaction starts (the checks need no database, and no row lock is held while a file is parsed); then one
 * transaction stores it and publishes the audit event last, so a refused upload stores nothing and writes no entry.
 * <p>
 * Names are unique among the active templates ignoring case and one active template holds a slot; the partial unique
 * indexes {@code report_template_name_uq} and {@code report_template_slot_uq} are the guarantee against a concurrent
 * request. A new version is the template's highest version number plus one, read under a row lock on the template, with
 * the unique key {@code report_template_version_no_uq} as the backstop. The files are never loaded by a list
 * ({@link ReportTemplateVersionRepository}). File names, template names and the files are never logged.
 */
@Service
class ReportTemplateService {

    private static final String NAME_INDEX = "report_template_name_uq";
    private static final String SLOT_INDEX = "report_template_slot_uq";
    private static final String VERSION_KEY = "report_template_version_no_uq";

    private final TemplateUploadChecks checks = new TemplateUploadChecks();
    private final ReportTemplateRepository templates;
    private final ReportTemplateVersionRepository versions;
    private final MemberDirectory members;
    private final ChargeTypeCatalog chargeTypes;
    private final ReportAuditPublisher audit;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;
    private final Clock clock;

    ReportTemplateService(ReportTemplateRepository templates, ReportTemplateVersionRepository versions,
            MemberDirectory members, ChargeTypeCatalog chargeTypes, ReportAuditPublisher audit,
            PlatformTransactionManager transactions, Clock clock) {
        this.templates = templates;
        this.versions = versions;
        this.members = members;
        this.chargeTypes = chargeTypes;
        this.audit = audit;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * The tax report slot first (the active template in it, or the slot as {@code NOT_ADDED}), then every other
     * template: active before archived, by name ignoring case. Each with its latest version and version count.
     */
    /**
     * The field catalog ({@link TemplateField#catalog}): every placeholder, the charge type families once per charge
     * type of the caller's cooperative. Read-only transaction (the charge types).
     */
    List<ReportTemplateField> catalog() {
        return Objects.requireNonNull(reading.execute(status -> TemplateField.catalog(chargeTypes.all()).stream()
                .map(TemplateField.Placeholder::toDto).toList()));
    }

    List<ReportTemplate> list(AccessProfile caller) {
        UUID tenant = tenantOf(caller);
        return reading.execute(status -> {
            List<ReportTemplateRecord> all = templates.findAllInOrder(tenant);
            List<UUID> ids = all.stream().map(ReportTemplateRecord::getId).toList();
            Map<UUID, TemplateVersionRow> latest = ids.isEmpty() ? Map.of()
                    : versions.findLatestRows(tenant, ids).stream()
                            .collect(Collectors.toMap(TemplateVersionRow::templateId, Function.identity()));
            Map<UUID, Integer> counts = new HashMap<>();
            if (!ids.isEmpty()) {
                for (Object[] row : versions.countByTemplate(tenant, ids)) {
                    counts.put((UUID) row[0], ((Number) row[1]).intValue());
                }
            }
            Map<UUID, String> names = uploaderNames(latest.values().stream());

            ReportTemplate taxSlot = null;
            List<ReportTemplate> others = new ArrayList<>();
            for (ReportTemplateRecord template : all) {
                ReportTemplate dto = toDto(template, latest.get(template.getId()),
                        counts.getOrDefault(template.getId(), 0), names);
                if (taxSlot == null && !template.isArchived()
                        && ReportTemplateRecord.TAX_SLOT.equals(template.getSlot())) {
                    taxSlot = dto;
                } else {
                    others.add(dto);
                }
            }
            List<ReportTemplate> result = new ArrayList<>(others.size() + 1);
            result.add(taxSlot != null ? taxSlot
                    : new ReportTemplate(ReportTemplateStatus.NOT_ADDED, 0).slot(ReportTemplateSlot.TAX));
            result.addAll(others);
            return result;
        });
    }

    /**
     * Stores a new active template with the file as its version 1.
     *
     * @throws ApiProblemException 400 {@code VALIDATION_FAILED} (blank name), 422 {@code TEMPLATE_TOO_LARGE},
     *                             400 {@code TEMPLATE_INVALID}, 409 {@code TEMPLATE_NAME_TAKEN} or
     *                             {@code TEMPLATE_SLOT_TAKEN}
     */
    ReportTemplateUploadResult upload(AccessProfile caller, MultipartFile file, @Nullable String rawName,
            @Nullable String rawDescription, @Nullable ReportTemplateSlot slot) {
        UUID tenant = tenantOf(caller);
        String name = cleanName(rawName);
        String description = blankToNull(rawDescription);
        TemplateUploadChecks.Checked checked = check(file);
        String slotCode = slot == null ? null : slot.getValue();
        return writing.execute(status -> {
            if (templates.existsActiveName(tenant, name)) {
                throw nameTaken();
            }
            if (slotCode != null && templates.existsActiveSlot(tenant, slotCode)) {
                throw slotTaken();
            }
            ReportTemplateRecord template = ReportTemplateRecord.create(tenant, slotCode, name, description,
                    caller.memberId(), clock.instant());
            saveTemplate(template);
            ReportTemplateVersionRecord version = saveVersion(ReportTemplateVersionRecord.create(tenant,
                    template.getId(), 1, checked, caller.memberId(), clock.instant()));
            audit.templateUploaded(caller, template.getId(), template.getName(), 1, checked.fileName());
            return result(template, version.toRow(), 1, checked);
        });
    }

    /**
     * Stores the file as the next version of the template.
     *
     * @throws ApiProblemException 422 {@code TEMPLATE_TOO_LARGE}, 400 {@code TEMPLATE_INVALID}, 404 (unknown or of
     *                             another cooperative), 409 {@code TEMPLATE_ARCHIVED}
     */
    ReportTemplateUploadResult uploadVersion(AccessProfile caller, UUID templateId, MultipartFile file) {
        UUID tenant = tenantOf(caller);
        TemplateUploadChecks.Checked checked = check(file);
        return writing.execute(status -> {
            ReportTemplateRecord template = templates.findOneForUpdate(tenant, templateId)
                    .orElseThrow(ApiProblemException::notFound);
            if (template.isArchived()) {
                throw archived();
            }
            int next = versions.maxVersionNo(tenant, templateId) + 1;
            ReportTemplateVersionRecord version = saveVersion(ReportTemplateVersionRecord.create(tenant,
                    templateId, next, checked, caller.memberId(), clock.instant()));
            audit.templateUploaded(caller, templateId, template.getName(), next, checked.fileName());
            return result(template, version.toRow(), next, checked);
        });
    }

    /** The versions of the template, newest first; 404 for an unknown template or one of another cooperative. */
    List<ReportTemplateVersion> versions(AccessProfile caller, UUID templateId) {
        UUID tenant = tenantOf(caller);
        return reading.execute(status -> {
            templates.findOne(tenant, templateId).orElseThrow(ApiProblemException::notFound);
            List<TemplateVersionRow> rows = versions.findRows(tenant, templateId);
            Map<UUID, String> names = uploaderNames(rows.stream());
            return rows.stream().map(row -> toDto(row, names)).toList();
        });
    }

    /**
     * Archives the template. Archiving an archived template changes nothing and writes no audit entry.
     *
     * @throws ApiProblemException 404 for an unknown template or one of another cooperative
     */
    ReportTemplate archive(AccessProfile caller, UUID templateId) {
        UUID tenant = tenantOf(caller);
        return writing.execute(status -> {
            ReportTemplateRecord template = templates.findOneForUpdate(tenant, templateId)
                    .orElseThrow(ApiProblemException::notFound);
            List<TemplateVersionRow> latest = versions.findLatestRows(tenant, List.of(templateId));
            TemplateVersionRow latestRow = latest.isEmpty() ? null : latest.getFirst();
            if (!template.isArchived()) {
                template.archive(caller.memberId(), clock.instant());
                templates.saveAndFlush(template);
                audit.templateArchived(caller, templateId, template.getName(),
                        latestRow == null ? 0 : latestRow.versionNo());
            }
            int count = latestRow == null ? 0 : versions.findRows(tenant, templateId).size();
            return toDto(template, latestRow, count, uploaderNames(Stream.ofNullable(latestRow)));
        });
    }

    /** The stored file of the version; 404 for an unknown template or version, or a version of another template. */
    TemplateFile download(AccessProfile caller, UUID templateId, UUID versionId) {
        UUID tenant = tenantOf(caller);
        return reading.execute(status -> versions.findFile(tenant, templateId, versionId)
                .orElseThrow(ApiProblemException::notFound));
    }

    // ---- checks

    /** The size the upload declares first (nothing read yet), then every check on the bytes. */
    private TemplateUploadChecks.Checked check(MultipartFile file) {
        TemplateUploadChecks.requireSize(file.getSize());
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw TemplateUploadChecks.invalid(ProblemCodes.BROKEN_ZIP);
        }
        return checks.check(file.getOriginalFilename(), content);
    }

    /** The trimmed name; the contract already limits the raw text to 1 to 200 characters, so only blank is left. */
    static String cleanName(@Nullable String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("name", ProblemCodes.REQUIRED)));
        }
        return name;
    }

    static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    // ---- storing

    /** Saves and flushes, so a concurrent request that took the name or the slot surfaces here as a 409. */
    private void saveTemplate(ReportTemplateRecord template) {
        try {
            templates.saveAndFlush(template);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, NAME_INDEX)) {
                throw nameTaken();
            }
            if (ConstraintViolations.violates(e, SLOT_INDEX)) {
                throw slotTaken();
            }
            throw e;
        }
    }

    private ReportTemplateVersionRecord saveVersion(ReportTemplateVersionRecord version) {
        try {
            ReportTemplateVersionRecord saved = versions.save(version);
            versions.flush();
            return saved;
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, VERSION_KEY)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TEMPLATE_VERSION_CONFLICT,
                        "Another file was stored for the template at the same moment; send the file again.");
            }
            throw e;
        }
    }

    // ---- mapping

    private ReportTemplateUploadResult result(ReportTemplateRecord template, TemplateVersionRow latest, int count,
            TemplateUploadChecks.Checked checked) {
        ReportTemplate dto = toDto(template, latest, count, uploaderNames(Stream.of(latest)));
        return new ReportTemplateUploadResult(dto, new ArrayList<>(checked.warnings()));
    }

    private static ReportTemplate toDto(ReportTemplateRecord template, @Nullable TemplateVersionRow latest,
            int versionCount, Map<UUID, String> names) {
        ReportTemplate dto = new ReportTemplate(template.isArchived() ? ReportTemplateStatus.ARCHIVED
                : ReportTemplateStatus.ACTIVE, versionCount)
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription());
        if (template.getSlot() != null) {
            dto.slot(ReportTemplateSlot.fromValue(template.getSlot()));
        }
        if (latest != null) {
            dto.latestVersion(toDto(latest, names));
        }
        return dto;
    }

    private static ReportTemplateVersion toDto(TemplateVersionRow row, Map<UUID, String> names) {
        return new ReportTemplateVersion(row.id(), row.versionNo(), row.fileName(), (long) row.sizeBytes(),
                row.sha256(), OffsetDateTime.ofInstant(row.uploadedAt(), ZoneOffset.UTC))
                .uploadedByName(row.uploadedBy() == null ? null : names.get(row.uploadedBy()));
    }

    /** Current names ("Last First Middle") of the uploaders; members that no longer exist are absent. */
    private Map<UUID, String> uploaderNames(Stream<TemplateVersionRow> rows) {
        Set<UUID> ids = rows.map(TemplateVersionRow::uploadedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        members.find(ids).forEach((id, member) -> names.put(id, displayName(member)));
        return names;
    }

    private static String displayName(MemberRef member) {
        return member.middleName() == null || member.middleName().isBlank() ? member.shortName()
                : member.shortName() + " " + member.middleName();
    }

    private static UUID tenantOf(AccessProfile caller) {
        // the controller lets only holders of a cooperative permission through; the operator has no cooperative
        return Objects.requireNonNull(caller.tenantId(), "A cooperative caller is required");
    }

    private static ApiProblemException nameTaken() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TEMPLATE_NAME_TAKEN,
                "An active template with this name already exists.",
                List.of(new FieldProblem("name", ProblemCodes.TEMPLATE_NAME_TAKEN)));
    }

    private static ApiProblemException slotTaken() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TEMPLATE_SLOT_TAKEN,
                "An active template is in the slot already; upload a new version of it instead.",
                List.of(new FieldProblem("slot", ProblemCodes.TEMPLATE_SLOT_TAKEN)));
    }

    private static ApiProblemException archived() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TEMPLATE_ARCHIVED,
                "The template is archived and takes no new version.");
    }
}
