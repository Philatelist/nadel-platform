package app.nadel.members.internal;

import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * How a member receives invoices. EMAIL, TELEGRAM and VIBER need the matching contact; PAPER needs nothing.
 */
enum DeliveryChannel {
    EMAIL("email"), TELEGRAM("telegramUsername"), VIBER("viberPhone"), PAPER(null);

    private final @Nullable String contactField;

    DeliveryChannel(@Nullable String contactField) {
        this.contactField = contactField;
    }

    /**
     * The problem to report when the contact this channel needs is missing (null or blank), or empty when the
     * channel is satisfied.
     */
    Optional<FieldProblem> check(@Nullable String email, @Nullable String telegramUsername,
            @Nullable String viberPhone) {
        String contact = switch (this) {
            case EMAIL -> email;
            case TELEGRAM -> telegramUsername;
            case VIBER -> viberPhone;
            case PAPER -> "-";
        };
        if (contact != null && !contact.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new FieldProblem(contactField, ProblemCodes.CHANNEL_CONTACT_MISSING));
    }
}
