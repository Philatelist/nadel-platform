package app.nadel.reports.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import java.nio.charset.StandardCharsets;
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
 * Publishes the audit entry of one run of a report ({@code REPORT_GENERATED}, entity type {@code REPORT}). The entity
 * id is fixed per report (a name-based UUID of {@code report:<code>}), so the log can be filtered by report; the label
 * is the report code. The fields are new values only: {@code report} (the code), {@code output} ({@code PREVIEW} or
 * {@code XLSX}), {@code filters} (a JSON array of {@code {param, value}} as the query string carries them: ids, codes,
 * dates and the street as typed), {@code rows} (the rows of every sheet) and {@code personalData}. Never the contents.
 * The outbox stores the publication in the runner's transaction.
 */
@Component
class ReportAuditPublisher {

    static final String ACTION = "REPORT_GENERATED";
    static final String ENTITY_TYPE = "REPORT";

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

    /** The entity id of every entry about the report. */
    static UUID entityId(ReportCode code) {
        return UUID.nameUUIDFromBytes(("report:" + code.getValue()).getBytes(StandardCharsets.UTF_8));
    }
}
