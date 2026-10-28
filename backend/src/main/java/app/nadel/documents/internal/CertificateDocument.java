package app.nadel.documents.internal;

import app.nadel.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything a debt certificate shows (feature 016 §2.7), already named: what {@link CertificateSnapshots} stores and
 * {@link CertificateLayout} draws. The annulment is not part of the stored snapshot (a certificate never changes); it
 * comes from the annul columns of the row. Holds personal data (the owners' and the issuer's names): never log it.
 *
 * @param id          the certificate
 * @param number      {@code Д-{YYYY}-{N}}
 * @param year        the year of the issue in Kyiv, the {@code YYYY} of the number
 * @param seq         the {@code N} of the number
 * @param asOf        the day the debt is certified as of
 * @param issuedAt    the moment it was issued (microseconds, as stored)
 * @param cooperative the requisites block
 * @param plot        the plot
 * @param owners      the current owners on the day of issue, the earliest first
 * @param types       the debt per charge type, in the cooperative's charge type order
 * @param totalDebt   the debt over every type ({@code debtsAsOf} total)
 * @param prepayment  the prepayment on the day
 * @param issuer      who issued it
 * @param annulment   the annulment; null while the certificate is valid
 */
record CertificateDocument(UUID id, String number, int year, int seq, LocalDate asOf, Instant issuedAt,
        ReceiptContent.Requisites cooperative, Plot plot, List<Owner> owners, List<TypeDebt> types, Money totalDebt,
        Money prepayment, Issuer issuer, @Nullable Annulment annulment) {

    CertificateDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(cooperative, "cooperative");
        Objects.requireNonNull(plot, "plot");
        owners = List.copyOf(owners);
        types = List.copyOf(types);
        Objects.requireNonNull(totalDebt, "totalDebt");
        Objects.requireNonNull(prepayment, "prepayment");
        Objects.requireNonNull(issuer, "issuer");
    }

    /** {@code Д-2026-1}. */
    static String number(int year, long seq) {
        return "Д-" + year + "-" + seq;
    }

    /** The day of issue in Kyiv. */
    LocalDate issuedOn() {
        return DocumentFormat.dayOf(issuedAt);
    }

    /** Whether something is owed: the total debt is above zero. */
    boolean inDebt() {
        return totalDebt.signum() > 0;
    }

    /** The same certificate with its annulment. */
    CertificateDocument annulled(@Nullable Annulment annulment) {
        return new CertificateDocument(id, number, year, seq, asOf, issuedAt, cooperative, plot, owners, types,
                totalDebt, prepayment, issuer, annulment);
    }

    /**
     * The plot.
     *
     * @param areaM2 the area in square metres as stored (two decimals)
     */
    record Plot(UUID id, String number, @Nullable String street, BigDecimal areaM2) {

        Plot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(areaM2, "areaM2");
        }
    }

    /** A current owner with the share as stored. */
    record Owner(UUID memberId, String name, int numerator, int denominator) {

        Owner {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(name, "name");
        }

        /** {@code 1/2}. */
        String share() {
            return numerator + "/" + denominator;
        }
    }

    /** The debt of one charge type: its id, its code (a seeded type), the name printed and the debt. */
    record TypeDebt(UUID chargeTypeId, @Nullable String code, String name, Money debt) {

        TypeDebt {
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(debt, "debt");
        }
    }

    /**
     * Who issued the certificate.
     *
     * @param memberId the member; null when unknown
     * @param name     the full name as printed (personal data, sealed)
     * @param role     the role as printed: «Голова» for the chairman, otherwise the issuer's role
     */
    record Issuer(@Nullable UUID memberId, String name, String role) {

        Issuer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
        }
    }

    /** The annulment: when and why (user content). */
    record Annulment(Instant at, String reason) {

        Annulment {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
