package app.nadel.settings.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.LossesMode;
import app.nadel.settings.PlotStatusVisibility;
import app.nadel.settings.Requisites;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the settings of the current cooperative. A cooperative with no row has the defaults
 * ({@link SettingsValues#DEFAULTS}). A change is one transaction that also publishes the {@link AuditEvent}; saving the
 * current values writes nothing.
 * <p>
 * The cooperative comes from the token ({@code TenantContext} or the caller's profile), never from the request. The
 * caller of {@link #update} must hold {@code SETTINGS_MANAGE} (checked on the controller).
 */
@Service
class SettingsService implements CooperativeSettings {

    /** {@code numeric(14,2)}: at most 12 digits before the point. */
    private static final int MAX_AMOUNT_INTEGER_DIGITS = 12;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** A code, not a phrase: the client shows the name of the settings in the reader's language. */
    private static final String LABEL = "settings";

    /**
     * A change of the settings: a null field keeps its value. {@code lossesPercent} and {@code redAmount} are the
     * request's strings, checked here. A requisite is trimmed and a blank one clears it; the IBAN is normalised and
     * checked here (its length by the contract before this runs).
     */
    record Change(@Nullable PlotStatusVisibility memberPlotStatusVisibility, @Nullable LossesMode lossesMode,
            @Nullable String lossesPercent, @Nullable CommonConsumerSplit commonConsumerDefaultSplit,
            @Nullable String redAmount, @Nullable Integer redDays, @Nullable String officialName,
            @Nullable String legalAddress, @Nullable String bankName, @Nullable String iban,
            @Nullable Boolean autoAllocatePrepayment) {
    }

    private final SettingsRepository settings;
    private final ApplicationEventPublisher events;

    SettingsService(SettingsRepository settings, ApplicationEventPublisher events) {
        this.settings = settings;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public PlotStatusVisibility memberPlotStatusVisibility() {
        return current().memberPlotStatusVisibility();
    }

    @Override
    @Transactional(readOnly = true)
    public LossesMode lossesMode() {
        return current().lossesMode();
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal lossesPercent() {
        return current().lossesPercent();
    }

    @Override
    @Transactional(readOnly = true)
    public CommonConsumerSplit commonConsumerDefaultSplit() {
        return current().commonConsumerDefaultSplit();
    }

    @Override
    @Transactional(readOnly = true)
    public Money redAmount() {
        return Money.of(current().redAmount());
    }

    @Override
    @Transactional(readOnly = true)
    public int redDays() {
        return current().redDays();
    }

    @Override
    @Transactional(readOnly = true)
    public Requisites requisites() {
        return current().requisites();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean autoAllocatePrepayment() {
        return current().autoAllocatePrepayment();
    }

    @Transactional(readOnly = true)
    SettingsValues values(AccessProfile viewer) {
        return valuesOf(viewer.tenantId());
    }

    /**
     * Applies the change. 400 {@code VALIDATION_FAILED} with {@code INVALID} on {@code redAmount} (negative or more
     * than 12 integer digits) or {@code lossesPercent} (above 100); the shape of both strings is checked by the
     * contract before this runs. Then 400 {@code IBAN_INVALID} with the field {@code iban} {@code IBAN_INVALID} when
     * the IBAN is not a valid Ukrainian IBAN. Either way nothing is saved.
     */
    @Transactional
    SettingsValues update(Change change, AccessProfile actor) {
        BigDecimal lossesPercent = change.lossesPercent() == null ? null : new BigDecimal(change.lossesPercent());
        BigDecimal redAmount = change.redAmount() == null ? null : Money.parse(change.redAmount()).value();
        List<FieldProblem> errors = new ArrayList<>();
        if (lossesPercent != null && lossesPercent.compareTo(HUNDRED) > 0) {
            errors.add(new FieldProblem("lossesPercent", ProblemCodes.INVALID));
        }
        if (redAmount != null && (redAmount.signum() < 0
                || redAmount.precision() - redAmount.scale() > MAX_AMOUNT_INTEGER_DIGITS)) {
            errors.add(new FieldProblem("redAmount", ProblemCodes.INVALID));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", errors);
        }
        String iban = change.iban() == null ? null : ibanOf(change.iban());

        UUID tenantId = actor.tenantId();
        SettingsValues before = valuesOf(tenantId);
        SettingsValues after = new SettingsValues(
                orElse(change.memberPlotStatusVisibility(), before.memberPlotStatusVisibility()),
                orElse(change.lossesMode(), before.lossesMode()),
                orElse(lossesPercent, before.lossesPercent()),
                orElse(change.commonConsumerDefaultSplit(), before.commonConsumerDefaultSplit()),
                orElse(redAmount, before.redAmount()),
                orElse(change.redDays(), before.redDays()),
                new Requisites(
                        textOf(change.officialName(), before.requisites().officialName()),
                        textOf(change.legalAddress(), before.requisites().legalAddress()),
                        textOf(change.bankName(), before.requisites().bankName()),
                        change.iban() == null ? before.requisites().iban() : iban),
                orElse(change.autoAllocatePrepayment(), before.autoAllocatePrepayment()));
        List<FieldChange> changes = after.changesSince(before);
        if (!changes.isEmpty()) {
            settings.upsert(tenantId, after.memberPlotStatusVisibility().name(), after.lossesMode().name(),
                    after.lossesPercent(), after.commonConsumerDefaultSplit().name(), after.redAmount(),
                    after.redDays(), after.requisites().officialName(), after.requisites().legalAddress(),
                    after.requisites().bankName(), after.requisites().iban(), after.autoAllocatePrepayment());
            events.publishEvent(AuditEvent.now(
                    tenantId,
                    new Actor(actor.memberId(), actor.subject(), actor.displayName()),
                    "SETTINGS_UPDATED",
                    "SETTINGS",
                    tenantId,
                    LABEL,
                    changes));
        }
        return after;
    }

    /**
     * The settings of the bound cooperative. Without one, RLS would hide every row, so the answer is what a cooperative
     * that never saved its settings has: the defaults.
     */
    private SettingsValues current() {
        return TenantContext.currentTenantId().map(this::valuesOf).orElse(SettingsValues.DEFAULTS);
    }

    private SettingsValues valuesOf(UUID tenantId) {
        return settings.findById(tenantId).map(SettingsRow::values).orElse(SettingsValues.DEFAULTS);
    }

    /** The normalised IBAN; null for a blank text (clears it); 400 {@code IBAN_INVALID} for anything else. */
    private static @Nullable String ibanOf(String text) {
        if (text.isBlank()) {
            return null;
        }
        return Iban.parse(text).map(Iban::value).orElseThrow(() -> new ApiProblemException(HttpStatus.BAD_REQUEST,
                ProblemCodes.IBAN_INVALID, "The IBAN is not valid.",
                List.of(new FieldProblem("iban", ProblemCodes.IBAN_INVALID))));
    }

    /** A requisite: null keeps the current value, a blank text clears it, anything else is stored trimmed. */
    private static @Nullable String textOf(@Nullable String value, @Nullable String current) {
        if (value == null) {
            return current;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static <T> T orElse(@Nullable T value, T fallback) {
        return value != null ? value : fallback;
    }
}
