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
        List<FieldProblem> errors = new ArrayList<>();

        String lastName = blankToNull(input.getLastName());
        if (lastName == null) {
            errors.add(new FieldProblem("lastName", ProblemCodes.REQUIRED));
        }
        String firstName = blankToNull(input.getFirstName());
        if (firstName == null) {
            errors.add(new FieldProblem("firstName", ProblemCodes.REQUIRED));
        }

        LinkedHashSet<String> phones = new LinkedHashSet<>();
        List<String> rawPhones = input.getPhones() == null ? List.of() : input.getPhones();
        for (int i = 0; i < rawPhones.size(); i++) {
            var phone = PhoneNumber.parse(rawPhones.get(i));
            if (phone.isPresent()) {
                phones.add(phone.get().value());
            } else {
                errors.add(new FieldProblem("phones[" + i + "]", ProblemCodes.PHONE_INVALID));
            }
        }

        String viber = blankToNull(input.getViberPhone());
        if (viber != null) {
            var parsed = PhoneNumber.parse(viber);
            if (parsed.isPresent()) {
                viber = parsed.get().value();
            } else {
                errors.add(new FieldProblem("viberPhone", ProblemCodes.PHONE_INVALID));
            }
        }

        String email = blankToNull(input.getEmail());
        email = email == null ? null : email.toLowerCase(Locale.ROOT);
        String telegram = blankToNull(input.getTelegramUsername());
        DeliveryChannel channel = input.getDeliveryChannel() == null
                ? DeliveryChannel.PAPER
                : DeliveryChannel.valueOf(input.getDeliveryChannel().name());
        channel.check(email, telegram, viber).ifPresent(errors::add);

        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", errors);
        }
        return new MemberData(lastName, firstName, blankToNull(input.getMiddleName()), List.copyOf(phones), email,
                blankToNull(input.getPostalAddress()), channel, telegram, viber, blankToNull(input.getNotes()));
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
