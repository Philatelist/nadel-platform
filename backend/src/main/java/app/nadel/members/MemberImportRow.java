package app.nadel.members;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One row of the Members sheet after the cells were read: trimmed text, the phones split into separate values. A
 * blank cell is {@code null}. The rules (required values, formats, lengths) are checked by
 * {@link MemberImport#plan}. Personal data: never log it.
 *
 * @param row             the Excel row number, echoed in the plan
 * @param externalId      the key that links the row to the Ownership sheet; unique in the file ignoring case
 * @param phones          the phones as typed, in file order; empty when the cell is blank
 * @param deliveryChannel {@code EMAIL}, {@code TELEGRAM}, {@code VIBER} or {@code PAPER}, any letter case
 */
public record MemberImportRow(
        int row,
        @Nullable String externalId,
        @Nullable String lastName,
        @Nullable String firstName,
        @Nullable String middleName,
        List<String> phones,
        @Nullable String email,
        @Nullable String postalAddress,
        @Nullable String deliveryChannel,
        @Nullable String telegramUsername,
        @Nullable String viberPhone) {

    public MemberImportRow {
        phones = List.copyOf(phones);
    }
}
