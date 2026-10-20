package app.nadel.members.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import nadel.contract.model.MemberInput;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The editable fields of a member after validation and normalisation: trimmed text (blank becomes null), phones in
 * international format without duplicates, e-mail trimmed and lower-case. Personal data: never log it.
 */
record MemberData(
        String lastName,
        String firstName,
        @Nullable String middleName,
        List<String> phones,
        @Nullable String email,
        @Nullable String postalAddress,
        DeliveryChannel deliveryChannel,
        @Nullable String telegramUsername,
        @Nullable String viberPhone,
        @Nullable String notes) {

    /**
     * Validates what Bean Validation cannot (blank names, phone format, channel contact) and normalises the rest.
     *
     * @throws ApiProblemException 400 {@code VALIDATION_FAILED} listing every invalid field
     */
    static MemberData from(MemberInput input) {
        DeliveryChannel channel = input.getDeliveryChannel() == null
                ? DeliveryChannel.PAPER
                : DeliveryChannel.valueOf(input.getDeliveryChannel().name());
        Validation result = tryFrom(input.getLastName(), input.getFirstName(), input.getMiddleName(),
                input.getPhones() == null ? List.of() : input.getPhones(), input.getEmail(),
                input.getPostalAddress(), channel, input.getTelegramUsername(), input.getViberPhone(),
                input.getNotes());
        if (result.data() == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", result.problems());
        }
        return result.data();
    }

    /**
     * The outcome of {@link #tryFrom}: the data when every field is valid, otherwise the problems of every invalid
     * field (never both).
     */
    record Validation(@Nullable MemberData data, List<FieldProblem> problems) {
    }

    /**
     * The same checks and normalisation as {@link #from}, for callers that collect the problems of many records
     * instead of failing on the first one (the Excel import). Field names are those of {@code MemberInput}; a
     * phone is reported as {@code phones[i]}.
     */
    static Validation tryFrom(@Nullable String rawLastName, @Nullable String rawFirstName,
            @Nullable String rawMiddleName, List<String> rawPhones, @Nullable String rawEmail,
            @Nullable String rawPostalAddress, DeliveryChannel channel, @Nullable String rawTelegram,
            @Nullable String rawViber, @Nullable String rawNotes) {
        List<FieldProblem> errors = new ArrayList<>();

        String lastName = blankToNull(rawLastName);
        if (lastName == null) {
            errors.add(new FieldProblem("lastName", ProblemCodes.REQUIRED));
        }
        String firstName = blankToNull(rawFirstName);
        if (firstName == null) {
            errors.add(new FieldProblem("firstName", ProblemCodes.REQUIRED));
        }

        LinkedHashSet<String> phones = new LinkedHashSet<>();
        for (int i = 0; i < rawPhones.size(); i++) {
            var phone = PhoneNumber.parse(rawPhones.get(i));
            if (phone.isPresent()) {
                phones.add(phone.get().value());
            } else {
                errors.add(new FieldProblem("phones[" + i + "]", ProblemCodes.PHONE_INVALID));
            }
        }

        String viber = blankToNull(rawViber);
        if (viber != null) {
            var parsed = PhoneNumber.parse(viber);
            if (parsed.isPresent()) {
                viber = parsed.get().value();
            } else {
                errors.add(new FieldProblem("viberPhone", ProblemCodes.PHONE_INVALID));
            }
        }

        String email = blankToNull(rawEmail);
        email = email == null ? null : email.toLowerCase(Locale.ROOT);
        String telegram = blankToNull(rawTelegram);
        channel.check(email, telegram, viber).ifPresent(errors::add);

        if (!errors.isEmpty()) {
            return new Validation(null, List.copyOf(errors));
        }
        return new Validation(new MemberData(lastName, firstName, blankToNull(rawMiddleName), List.copyOf(phones),
                email, blankToNull(rawPostalAddress), channel, telegram, viber, blankToNull(rawNotes)), List.of());
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
