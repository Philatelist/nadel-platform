package app.nadel.members.internal;

import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.privacy.PersonalDataUnreadableException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The only place in the application that seals and opens member contacts and writes {@code member_phone_index}
 * (ADR 0016). Runs inside the caller's cooperative transaction: the cipher refuses a member of another cooperative
 * than {@code TenantContext}'s, and the index rows go through the transaction's connection under RLS.
 * <p>
 * A value that does not open is a {@link PersonalDataUnreadableException} (500), never an empty field that a later
 * save would write back. Personal data: never log a value.
 */
@Component
class MemberCrypto {

    private final PersonalDataCipher cipher;
    private final JdbcTemplate jdbc;

    MemberCrypto(PersonalDataCipher cipher, JdbcTemplate jdbc) {
        this.cipher = cipher;
        this.jdbc = jdbc;
    }

    /** Every contact of the member in the clear. */
    OpenedContacts open(Member member) {
        ContactCipher contacts = cipherOf(member.getTenantId());
        UUID id = member.getId();
        return new OpenedContacts(
                contacts.openPhones(id, member.sealed(ContactField.PHONES)),
                contacts.open(ContactField.EMAIL, id, member.sealed(ContactField.EMAIL)),
                contacts.open(ContactField.POSTAL_ADDRESS, id, member.sealed(ContactField.POSTAL_ADDRESS)),
                contacts.open(ContactField.TELEGRAM_USERNAME, id, member.sealed(ContactField.TELEGRAM_USERNAME)),
                contacts.open(ContactField.VIBER_PHONE, id, member.sealed(ContactField.VIBER_PHONE)),
                contacts.open(ContactField.NOTES, id, member.sealed(ContactField.NOTES)));
    }

    /** The phones only, in the stored order (the list and the reports need no more). */
    List<String> phones(Member member) {
        return cipherOf(member.getTenantId()).openPhones(member.getId(), member.sealed(ContactField.PHONES));
    }

    /** The e-mail only; null when there is none. */
    @Nullable String email(Member member) {
        return cipherOf(member.getTenantId()).open(ContactField.EMAIL, member.getId(),
                member.sealed(ContactField.EMAIL));
    }

    /**
     * Seals the contacts of {@code data} into the member, re-sealing only the fields that differ from {@code before}
     * (what {@link #open} returned, or {@link OpenedContacts#NONE} for a new member), and keeps {@code email_bidx} in
     * step with the e-mail. The stored phone order is kept when only the order differs, so such an edit changes
     * nothing.
     *
     * @return the contacts the member now holds
     */
    OpenedContacts seal(Member member, OpenedContacts before, MemberData data) {
        ContactCipher contacts = cipherOf(member.getTenantId());
        UUID id = member.getId();
        List<String> phones = sameSet(before.phones(), data.phones()) ? before.phones() : data.phones();
        OpenedContacts after = new OpenedContacts(phones, data.email(), data.postalAddress(),
                data.telegramUsername(), data.viberPhone(), data.notes());
        if (!before.phones().equals(after.phones())) {
            member.seal(ContactField.PHONES, contacts.sealPhones(id, after.phones()));
        }
        if (!Objects.equals(before.email(), after.email())) {
            member.seal(ContactField.EMAIL, contacts.seal(ContactField.EMAIL, id, after.email()));
            member.setEmailBidx(contacts.emailIndex(after.email()));
        }
        sealIfChanged(contacts, member, ContactField.POSTAL_ADDRESS, before.postalAddress(), after.postalAddress());
        sealIfChanged(contacts, member, ContactField.TELEGRAM_USERNAME, before.telegramUsername(),
                after.telegramUsername());
        sealIfChanged(contacts, member, ContactField.VIBER_PHONE, before.viberPhone(), after.viberPhone());
        sealIfChanged(contacts, member, ContactField.NOTES, before.notes(), after.notes());
        return after;
    }

    /**
     * Replaces the {@code member_phone_index} rows of a member (delete, then insert: the application may not update
     * them). The member row must already be written (foreign key), so callers flush first.
     */
    void replacePhoneIndex(Member member, List<String> phones) {
        jdbc.update("delete from member_phone_index where member_id = ?", member.getId());
        List<ContactCipher.PhoneIndexRow> rows = cipherOf(member.getTenantId()).phoneIndex(phones);
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("insert into member_phone_index (tenant_id, member_id, phone_bidx, last4_bidx) "
                + "values (?, ?, ?, ?)", rows.stream()
                        .map(row -> new Object[] {member.getTenantId(), member.getId(), row.phoneBidx(),
                                row.last4Bidx()})
                        .toList());
    }

    /** The blind index of an already normalised value of the cooperative. */
    byte[] index(UUID tenantId, IndexKind kind, String normalised) {
        return cipher.blindIndex(tenantId, kind, normalised);
    }

    private ContactCipher cipherOf(UUID tenantId) {
        return ContactCipher.of(cipher, tenantId);
    }

    private static void sealIfChanged(ContactCipher contacts, Member member, ContactField field,
            @Nullable String before, @Nullable String after) {
        if (!Objects.equals(before, after)) {
            member.seal(field, contacts.seal(field, member.getId(), after));
        }
    }

    private static boolean sameSet(List<String> stored, List<String> wanted) {
        return stored.size() == wanted.size() && Set.copyOf(stored).equals(Set.copyOf(wanted));
    }
}
