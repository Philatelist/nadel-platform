package app.nadel.reports.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportFilterValue;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the audit entries of the reports module: one run of a report, and the uploads and archiving of report
 * templates.
 * <p>
 * A run of a report is {@code REPORT_GENERATED}, entity type {@code REPORT}. The entity
 * id is fixed per report (a name-based UUID of {@code report:<code>}), so the log can be filtered by report; the label
 * is the report code. The fields are new values only: {@code report} (the code), {@code output} ({@code PREVIEW} or
 * {@code XLSX}), {@code filters} (a JSON array of {@code {param, value}} as the query string carries them: ids, codes,
 * dates and the street as typed), {@code rows} (the rows of every sheet) and {@code personalData}. Never the contents.
 * The outbox stores the publication in the runner's transaction.
 * <p>
 * A template entry is about the template (entity type {@code REPORT_TEMPLATE}, entity id the template id, label its
 * name as entered): {@code REPORT_TEMPLATE_UPLOADED} for a new template and for a new version, with the new values
 * {@code version} (the version number) and {@code fileName} (the stored file name as uploaded); and
 * {@code REPORT_TEMPLATE_ARCHIVED} with {@code version} (the latest version number). A file generated from a template is
 * {@code REPORT_GENERATED} about the template, with {@code template} (the id), {@code version}, {@code filters} (as for
 * a report, the params {@code from} and {@code to}), {@code rows} (the rows written for row sets), {@code personalData}
 * ({@code false}: no placeholder names a person) and {@code output} ({@code XLSX}). Never the file's contents. Each is
 * published last in the transaction that stores the change or makes the file.
 */
@Component
class ReportAuditPublisher {

    static final String ACTION = "REPORT_GENERATED";
    static final String ENTITY_TYPE = "REPORT";
    static final String TEMPLATE_UPLOADED = "REPORT_TEMPLATE_UPLOADED";
    static final String TEMPLATE_ARCHIVED = "REPORT_TEMPLATE_ARCHIVED";
    static final String TEMPLATE_ENTITY_TYPE = "REPORT_TEMPLATE";

    /** What a run produced. */
    enum Output {
        PREVIEW,
        XLSX
    }

    private final ApplicationEventPublisher events;
    private final JsonMapper json;

    ReportAuditPublisher(ApplicationEventPublisher events, JsonMapper json) {
        this.events = events;
        this.json = json;
    }

    void publish(AccessProfile caller, ReportCode code, Output output, List<ReportFilterValue> filters, int rows,
            boolean personalData) {
        List<Map<String, String>> values = filters.stream().map(value -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("param", value.getParam().getValue());
            entry.put("value", value.getValue());
            return entry;
        }).toList();
        List<FieldChange> changes = List.of(
                new FieldChange("report", null, code.getValue()),
                new FieldChange("output", null, output.name()),
                new FieldChange("filters", null, json.writeValueAsString(values)),
                new FieldChange("rows", null, Integer.toString(rows)),
                new FieldChange("personalData", null, Boolean.toString(personalData)));
        events.publishEvent(AuditEvent.now(Objects.requireNonNull(caller.tenantId()),
                new Actor(caller.memberId(), caller.subject(), caller.displayName()), ACTION, ENTITY_TYPE,
                entityId(code), code.getValue(), changes));
    }

    /** A new template or a new version of one was stored. */
    void templateUploaded(AccessProfile caller, UUID templateId, String name, int versionNo, String fileName) {
        publishTemplate(caller, TEMPLATE_UPLOADED, templateId, name, List.of(
                new FieldChange("version", null, Integer.toString(versionNo)),
                new FieldChange("fileName", null, fileName)));
    }

    /** The template was archived; {@code latestVersionNo} is the version generation used until now. */
    void templateArchived(AccessProfile caller, UUID templateId, String name, int latestVersionNo) {
        publishTemplate(caller, TEMPLATE_ARCHIVED, templateId, name, List.of(
                new FieldChange("version", null, Integer.toString(latestVersionNo))));
    }

    /** A file was generated from the version of the template for the period. */
    void templateGenerated(AccessProfile caller, UUID templateId, String name, int versionNo, LocalDate from,
            LocalDate to, int rows) {
        publishTemplate(caller, ACTION, templateId, name, List.of(
                new FieldChange("template", null, templateId.toString()),
                new FieldChange("version", null, Integer.toString(versionNo)),
                new FieldChange("filters", null, json.writeValueAsString(List.of(filter("from", from.toString()),
                        filter("to", to.toString())))),
                new FieldChange("rows", null, Integer.toString(rows)),
                new FieldChange("personalData", null, Boolean.toString(false)),
                new FieldChange("output", null, Output.XLSX.name())));
    }

    /** One filter as the audit entry lists it, {@code param} first. */
    private static Map<String, String> filter(String param, String value) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("param", param);
        entry.put("value", value);
        return entry;
    }

    private void publishTemplate(AccessProfile caller, String action, UUID templateId, String name,
            List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(Objects.requireNonNull(caller.tenantId()),
                new Actor(caller.memberId(), caller.subject(), caller.displayName()), action, TEMPLATE_ENTITY_TYPE,
                templateId, name, changes));
    }

    /** The entity id of every entry about the report. */
    static UUID entityId(ReportCode code) {
        return UUID.nameUUIDFromBytes(("report:" + code.getValue()).getBytes(StandardCharsets.UTF_8));
    }
}
