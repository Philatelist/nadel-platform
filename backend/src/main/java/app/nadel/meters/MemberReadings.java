package app.nadel.meters;

import app.nadel.identity.AccessProfile;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A member's readings outside the account (feature 017 §2.5: the cooperative's Telegram bot): the meters of a plot the
 * member owns now, a submission for the board's approval and the board's decision on it. The rules are the account's
 * ({@code POST /plots/{id}/readings}): only a current owner of the plot reaches it, the reading waits for approval
 * ({@code PENDING}) and is refused with the same problem codes. The cooperative is the bound one
 * ({@code TenantContext}); row-level security hides every other.
 */
public interface MemberReadings {

    /**
     * An active meter of a member's plot.
     *
     * @param serial the serial number (user content)
     */
    record MemberMeter(UUID meterId, MeterResource resource, String serial) {
    }

    /**
     * Why a submission was refused: the problem code the account gets ({@code READING_OUT_OF_ORDER},
     * {@code READING_DATE_IN_FUTURE}, {@code READING_DATE_OUTSIDE_METER}, {@code METER_VALUE_INVALID},
     * {@code METER_REMOVED}, {@code NOT_FOUND} for a meter that is not the plot's, {@code PLOT_NOT_FOUND} for a plot
     * the member does not own now, {@code PERMISSION_DENIED} without a cooperative) and the parameters of its message
     * (ISO dates and meter values with three decimals, as the account's batch rows carry them).
     */
    record Problem(String code, Map<String, String> params) {

        public Problem {
            Objects.requireNonNull(code, "code");
            params = Map.copyOf(params);
        }
    }

    /** What a submission did. */
    sealed interface SubmitResult {
    }

    /**
     * The reading waits for the board's approval (the same value already waiting on that day counts as accepted, as in
     * the account).
     */
    record Accepted(UUID readingId, MeterValue value, String serial) implements SubmitResult {
    }

    /** Nothing was saved. */
    record Rejected(List<Problem> problems) implements SubmitResult {

        public Rejected {
            problems = List.copyOf(problems);
        }
    }

    /**
     * A reading as the board left it.
     *
     * @param serial the serial number of its meter (user content)
     * @param reason the reason of a rejection (user content, shown as entered); null otherwise
     */
    record ReadingDecisionView(MeterValue value, String serial, ReadingStatus status, @Nullable String reason) {
    }

    /**
     * The active plot meters of the plot, by resource and serial number, when {@code member} owns the plot now
     * ({@code AccessProfile.ownerOnly}); otherwise, an unknown plot and one of another cooperative included, none.
     */
    List<MemberMeter> metersOf(UUID plotId, AccessProfile member);

    /**
     * Submits a reading of the meter dated today in Europe/Kyiv, for the board's approval, exactly as the account does
     * (same checks, same audit entry {@code READING_SUBMITTED}, the member the author). Runs in a transaction of its
     * own (a refusal rolls only that back), so the caller holds none open.
     *
     * @param value the wire format of a meter value ({@code "12345"}, {@code "12345.5"}); anything else is refused with
     *              {@code METER_VALUE_INVALID}
     */
    SubmitResult submit(UUID plotId, UUID meterId, String value, AccessProfile member);

    /**
     * The reading as it stands now, for the message about the board's decision; empty for an unknown reading or one of
     * another cooperative. Answers as the system: the caller decides who is told.
     */
    Optional<ReadingDecisionView> decision(UUID readingId);
}
