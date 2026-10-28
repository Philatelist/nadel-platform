package app.nadel.members.internal;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.OwnerKeyring;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.privacy.PersonalDataUnreadableException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seals, opens and indexes the contacts of the members of one cooperative. One implementation runs on the schema
 * owner's connection ({@link OwnerKeyring}: the Java migration 22.1 and the demo contacts callback), the other in the
 * application ({@link PersonalDataCipher}); the helpers below are shared, so both write exactly the same formats.
 * <p>
 * Phones are one JSON array sealed once ({@code null} when there are none). Personal data: never log a value.
 */
interface ContactCipher {

    byte[] encrypt(FieldRef field, String plaintext);

    /** @throws PersonalDataUnreadableException when the value does not open */
    String decrypt(FieldRef field, byte[] sealed);

    byte[] blindIndex(IndexKind kind, String normalised);

    /** The cipher of a cooperative on the owner's connection. */
    static ContactCipher of(OwnerKeyring.TenantCipher owner) {
        return new ContactCipher() {
            @Override
            public byte[] encrypt(FieldRef field, String plaintext) {
                return owner.encrypt(field, plaintext);
            }

            @Override
            public String decrypt(FieldRef field, byte[] sealed) {
                return owner.decrypt(field, sealed);
            }

            @Override
            public byte[] blindIndex(IndexKind kind, String normalised) {
                return owner.blindIndex(kind, normalised);
            }
        };
    }

    /** The cipher of a cooperative in the application (its transaction is bound to that cooperative). */
    static ContactCipher of(PersonalDataCipher cipher, UUID tenantId) {
        return new ContactCipher() {
            @Override
            public byte[] encrypt(FieldRef field, String plaintext) {
                return cipher.encrypt(tenantId, field, plaintext);
            }

            @Override
            public String decrypt(FieldRef field, byte[] sealed) {
                return cipher.decrypt(tenantId, field, sealed);
            }

            @Override
            public byte[] blindIndex(IndexKind kind, String normalised) {
                return cipher.blindIndex(tenantId, kind, normalised);
            }
        };
    }

    /** The sealed value of a text field of a member, or null for no value (null or blank). */
    default byte @Nullable [] seal(ContactField field, UUID memberId, @Nullable String value) {
        return value == null || value.isBlank() ? null : encrypt(field.ref(memberId), value);
    }

    default @Nullable String open(ContactField field, UUID memberId, byte @Nullable [] sealed) {
        return sealed == null ? null : decrypt(field.ref(memberId), sealed);
    }

    /** The phones as one sealed JSON array, or null when there are none. */
    default byte @Nullable [] sealPhones(UUID memberId, List<String> phones) {
        return phones.isEmpty() ? null : encrypt(ContactField.PHONES.ref(memberId), PhonesJson.write(phones));
    }

    /** The phones in their stored order; empty when none are stored. */
    default List<String> openPhones(UUID memberId, byte @Nullable [] sealed) {
        return sealed == null ? List.of() : PhonesJson.read(decrypt(ContactField.PHONES.ref(memberId), sealed));
    }

    /** The blind index of an e-mail ({@link PhoneIndexValues#normalisedEmail}), or null for no e-mail. */
    default byte @Nullable [] emailIndex(@Nullable String email) {
        String normalised = PhoneIndexValues.normalisedEmail(email);
        return normalised == null ? null : blindIndex(IndexKind.EMAIL, normalised);
    }

    /** The rows of {@code member_phone_index} of the given phones: full phone and last 4 digits, hashed. */
    default List<PhoneIndexRow> phoneIndex(List<String> phones) {
        return PhoneIndexValues.entries(phones).stream()
                .map(entry -> new PhoneIndexRow(blindIndex(IndexKind.PHONE, entry.phone()),
                        blindIndex(IndexKind.PHONE_LAST4, entry.last4())))
                .toList();
    }

    /** A row of {@code member_phone_index} without its keys. */
    record PhoneIndexRow(byte[] phoneBidx, byte[] last4Bidx) {
    }

    /** The JSON form of the phones inside the sealed value. */
    final class PhonesJson {

        private static final TypeReference<List<String>> LIST = new TypeReference<>() {
        };

        private PhonesJson() {
        }

        static String write(List<String> phones) {
            return JsonMapper.shared().writeValueAsString(phones);
        }

        static List<String> read(String json) {
            try {
                List<String> phones = JsonMapper.shared().readValue(json, LIST);
                return phones == null ? List.of() : List.copyOf(phones);
            } catch (JacksonException | NullPointerException e) {
                // the value opened but is not the array this module seals: unreadable, never an empty list
                throw new PersonalDataUnreadableException();
            }
        }
    }
}
