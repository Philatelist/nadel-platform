package app.nadel.members.internal;

import app.nadel.audit.FieldChange;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The audited fields of a member as strings, so two states can be compared and the difference written to the audit
 * log. Multi-valued fields are sorted and joined; an empty value is null. Personal data: never log it.
 */
record MemberSnapshot(
        @Nullable String lastName,
        @Nullable String firstName,
        @Nullable String middleName,
        @Nullable String phones,
        @Nullable String email,
        @Nullable String postalAddress,
        @Nullable String deliveryChannel,
        @Nullable String telegramUsername,
        @Nullable String viberPhone,
        @Nullable String notes,
        boolean archived) {

    /** The state before a member exists: every field empty, not archived. */
    static final MemberSnapshot NONE = new MemberSnapshot(null, null, null, null, null, null, null, null, null, null,
            false);

    static MemberSnapshot of(Member member) {
        return new MemberSnapshot(
                member.getLastName(),
                member.getFirstName(),
                member.getMiddleName(),
                join(member.getPhones()),
                member.getEmail(),
                member.getPostalAddress(),
                member.getDeliveryChannel().name(),
                member.getTelegramUsername(),
                member.getViberPhone(),
                member.getNotes(),
                member.isArchived());
    }

    /** The state a member would have with this data: active, phones sorted and joined as in {@link #of(Member)}. */
    static MemberSnapshot of(MemberData data) {
        return new MemberSnapshot(data.lastName(), data.firstName(), data.middleName(), join(data.phones()),
                data.email(), data.postalAddress(), data.deliveryChannel().name(), data.telegramUsername(),
                data.viberPhone(), data.notes(), false);
    }

    /** The fields whose value differs, in a fixed order; empty when nothing changed. */
    static List<FieldChange> diff(MemberSnapshot before, MemberSnapshot after) {
        List<FieldChange> changes = new ArrayList<>();
        add(changes, "lastName", before.lastName, after.lastName);
        add(changes, "firstName", before.firstName, after.firstName);
        add(changes, "middleName", before.middleName, after.middleName);
        add(changes, "phones", before.phones, after.phones);
        add(changes, "email", before.email, after.email);
        add(changes, "postalAddress", before.postalAddress, after.postalAddress);
        add(changes, "deliveryChannel", before.deliveryChannel, after.deliveryChannel);
        add(changes, "telegramUsername", before.telegramUsername, after.telegramUsername);
        add(changes, "viberPhone", before.viberPhone, after.viberPhone);
        add(changes, "notes", before.notes, after.notes);
        add(changes, "archived", String.valueOf(before.archived), String.valueOf(after.archived));
        return changes;
    }

    private static void add(List<FieldChange> changes, String field, @Nullable String before,
            @Nullable String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before, after));
        }
    }

    private static @Nullable String join(Collection<String> values) {
        return values.isEmpty() ? null : String.join(", ", values.stream().sorted().toList());
    }
}
