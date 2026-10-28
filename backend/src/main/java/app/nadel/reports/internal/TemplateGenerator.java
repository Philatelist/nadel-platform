package app.nadel.reports.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nadel.contract.model.ReportTemplateWarning;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generates a file from the latest version of a template (feature 013 §4.3, ADR 0015) in one plain transaction, as
 * {@link ReportRunner} runs a report: the period is checked, the template and its latest file are read, the values are
 * resolved ({@link TemplateValues}, whose facades need the transaction), the file is filled ({@link TemplateFiller}) and
 * the audit event is published last, so a refused or failed generation leaves no entry. The cooperative comes from the
 * caller's token; the controller has checked {@code REPORTS_VIEW}, and a payment field of the template needs
 * {@code PAYMENTS_VIEW} too (the resolver refuses it with 403 before the audit event).
 */
@Component
class TemplateGenerator {

    /** The most warnings in {@code X-Report-Warnings-Detail}. */
    static final int DETAIL_WARNINGS = 50;
    /** The encoded {@code X-Report-Warnings-Detail} stays below this length, for reverse proxies. */
    static final int DETAIL_BYTES = 4096;

    /** A generated file: its name {@code <template name>_<from>_<to>.xlsx}, its bytes and its warnings. */
    record Generated(String fileName, byte[] content, List<ReportTemplateWarning> warnings) {
    }

    private final TemplateFiller filler = new TemplateFiller();
    private final CurrentUser currentUser;
    private final ReportTemplateRepository templates;
    private final ReportTemplateVersionRepository versions;
    private final TemplateValues values;
    private final ReportAuditPublisher audit;
    private final ReportLimits limits;
    private final JsonMapper json;
    private final Clock clock;

    TemplateGenerator(CurrentUser currentUser, ReportTemplateRepository templates,
            ReportTemplateVersionRepository versions, TemplateValues values, ReportAuditPublisher audit,
            ReportLimits limits, JsonMapper json, Clock clock) {
        this.currentUser = currentUser;
        this.templates = templates;
        this.versions = versions;
        this.values = values;
        this.audit = audit;
        this.limits = limits;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @throws ApiProblemException 400 {@code REPORT_FILTER_INVALID} ({@code to} before {@code from}, or a year or
     *                             more), 404, 409 {@code TEMPLATE_ARCHIVED}, 400 {@code TEMPLATE_INVALID}, 422
     *                             {@code REPORT_TOO_LARGE}
     * @throws org.springframework.security.access.AccessDeniedException (403) when the template uses a payment field
     *                             and the caller lacks {@code PAYMENTS_VIEW}
     */
    @Transactional
    public Generated generate(UUID templateId, LocalDate from, LocalDate to, ReportLanguage language) {
        if (to.isBefore(from) || !to.isBefore(from.plusYears(1))) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.REPORT_FILTER_INVALID,
                    "The filters do not fit the report.", List.of(new FieldProblem("to", ProblemCodes.INVALID)));
        }
        AccessProfile caller = currentUser.profile();
        UUID tenant = Objects.requireNonNull(caller.tenantId(), "A cooperative caller is required");
        ReportTemplateRecord template = templates.findOne(tenant, templateId)
                .orElseThrow(ApiProblemException::notFound);
        if (template.isArchived()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.TEMPLATE_ARCHIVED,
                    "The template is archived and can no longer be generated from.");
        }
        TemplateVersionRow latest = versions.findLatestRows(tenant, List.of(templateId)).stream().findFirst()
                .orElseThrow(ApiProblemException::notFound);
        TemplateFile file = versions.findFile(tenant, templateId, latest.id())
                .orElseThrow(ApiProblemException::notFound);
        TemplateValues.Resolver resolver = values.resolver(from, to, language, caller.displayName(), clock.instant(),
                caller.permissions());
        TemplateFiller.Filled filled = filler.fill(file.content(), template.getName(), latest.versionNo(), resolver,
                ReportTexts.of(language), limits.rowCap());
        audit.templateGenerated(caller, templateId, template.getName(), latest.versionNo(), from, to,
                filled.rows());
        return new Generated(template.getName() + "_" + from + "_" + to + ".xlsx", filled.content(),
                filled.warnings());
    }

    /**
     * {@code X-Report-Warnings-Detail}: the first {@value #DETAIL_WARNINGS} warnings as JSON in base64url without
     * padding, fewer when the encoded value would reach {@value #DETAIL_BYTES} characters; null without warnings.
     */
    @Nullable String detail(List<ReportTemplateWarning> warnings) {
        for (int count = Math.min(warnings.size(), DETAIL_WARNINGS); count > 0; count--) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    json.writeValueAsString(warnings.subList(0, count)).getBytes(StandardCharsets.UTF_8));
            if (encoded.length() < DETAIL_BYTES) {
                return encoded;
            }
        }
        return null;
    }
}
