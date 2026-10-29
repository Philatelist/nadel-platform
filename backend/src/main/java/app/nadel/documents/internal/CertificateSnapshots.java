package app.nadel.documents.internal;

import app.nadel.money.Money;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored form of a debt certificate (feature 016 §2.7, technical considerations 2.7): the snapshot, schema 1, in the
 * {@code certificate.snapshot} jsonb column. Codes, numbers and the texts printed (the requisites, the plot, the charge
 * type names, the issuer's role); every amount a string with two decimals, days ISO. The names of people (the owners,
 * the issuer) are one JSON object sealed with the cooperative's key at
 * {@code FieldRef("certificate", "snapshot.persons", certificateId)} (ADR 0016) and stored as base64 under
 * {@code persons}, so a certificate outlives a change of owner without plaintext names in the database. The snapshot is
 * written once and never changes (the database refuses any change but the annulment columns).
 */
@Component
class CertificateSnapshots {

    /** The version of the snapshot format. */
    static final int SCHEMA = 1;

    static final String TABLE = "certificate";
    static final String PERSONS = "snapshot.persons";

    private final PersonalDataCipher cipher;
    private final JsonMapper json;

    CertificateSnapshots(PersonalDataCipher cipher, JsonMapper json) {
        this.cipher = cipher;
        this.json = json;
    }

    /** The names sealed for the certificate row, as base64: what {@link #snapshot} stores under {@code persons}. */
    String sealPersons(UUID tenantId, CertificateDocument document) {
        byte[] sealed = cipher.encrypt(tenantId, field(document.id()),
                InvoiceSnapshots.compact(persons(document)));
        return Base64.getEncoder().encodeToString(sealed);
    }

    /** The snapshot to store: the body with the sealed persons. */
    static Map<String, Object> snapshot(CertificateDocument document, String sealedPersons) {
        Map<String, Object> snapshot = body(document);
        snapshot.put("persons", Objects.requireNonNull(sealedPersons, "sealedPersons"));
        return snapshot;
    }

    /** The certificate of a stored snapshot, the persons opened with the cooperative's key. */
    CertificateDocument open(UUID tenantId, UUID certificateId, Map<String, Object> snapshot,
            CertificateDocument.@Nullable Annulment annulment) {
        String persons = cipher.decrypt(tenantId, field(certificateId),
                Base64.getDecoder().decode(string(snapshot, "persons")));
        @SuppressWarnings("unchecked")
        Map<String, Object> open = json.readValue(persons, Map.class);
        return read(certificateId, snapshot, open).annulled(annulment);
    }

    /**
     * The whole certificate with the persons in plaintext and the annulment, keys sorted: the identity of its PDF file
     * (the same certificate in the same state gives the same {@code /ID}).
     */
    static String identity(CertificateDocument document) {
        Map<String, Object> body = body(document);
        body.put("id", document.id().toString());
        body.put("persons", persons(document));
        if (document.annulment() != null) {
            body.put("annulment", Map.of("at", document.annulment().at().toString(), "reason",
                    document.annulment().reason()));
        }
        return InvoiceSnapshots.compact(body);
    }

    static FieldRef field(UUID certificateId) {
        return new FieldRef(TABLE, PERSONS, certificateId);
    }

    // ---- writing

    /** The snapshot without the persons. */
    static Map<String, Object> body(CertificateDocument d) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("number", d.number());
        body.put("year", d.year());
        body.put("seq", d.seq());
        body.put("asOf", d.asOf().toString());
        body.put("issuedAt", d.issuedAt().toString());
        body.put("issuedOn", d.issuedOn().toString());
        Map<String, Object> cooperative = new LinkedHashMap<>();
        cooperative.put("name", d.cooperative().name());
        putIfSet(cooperative, "edrpou", d.cooperative().edrpou());
        putIfSet(cooperative, "address", d.cooperative().address());
        putIfSet(cooperative, "iban", d.cooperative().iban());
        putIfSet(cooperative, "bank", d.cooperative().bank());
        body.put("cooperative", cooperative);
        Map<String, Object> plot = new LinkedHashMap<>();
        plot.put("id", d.plot().id().toString());
        plot.put("number", d.plot().number());
        putIfSet(plot, "street", d.plot().street());
        plot.put("areaM2", d.plot().areaM2().toPlainString());
        body.put("plot", plot);
        body.put("owners", d.owners().stream().map(owner -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", owner.memberId().toString());
            row.put("numerator", owner.numerator());
            row.put("denominator", owner.denominator());
            return row;
        }).toList());
        body.put("types", d.types().stream().map(type -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chargeTypeId", type.chargeTypeId().toString());
            putIfSet(row, "code", type.code());
            row.put("name", type.name());
            row.put("debt", type.debt().toString());
            return row;
        }).toList());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("debt", d.totalDebt().toString());
        totals.put("prepayment", d.prepayment().toString());
        body.put("totals", totals);
        Map<String, Object> issuer = new LinkedHashMap<>();
        putIfSet(issuer, "memberId", d.issuer().memberId() == null ? null : d.issuer().memberId().toString());
        issuer.put("role", d.issuer().role());
        body.put("issuer", issuer);
        return body;
    }

    /** The names, in plaintext: what is sealed under {@code persons}. */
    static Map<String, Object> persons(CertificateDocument d) {
        Map<String, Object> persons = new LinkedHashMap<>();
        persons.put("owners", d.owners().stream().map(owner -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", owner.memberId().toString());
            row.put("name", owner.name());
            return row;
        }).toList());
        persons.put("issuer", d.issuer().name());
        return persons;
    }

    // ---- reading

    /** The certificate of a stored snapshot whose persons are already open (without the annulment). */
    @SuppressWarnings("unchecked")
    static CertificateDocument read(UUID certificateId, Map<String, Object> s, Map<String, Object> persons) {
        Map<String, String> ownerNames = new HashMap<>();
        for (Object row : list(persons, "owners")) {
            Map<String, Object> owner = (Map<String, Object>) row;
            ownerNames.put(string(owner, "memberId"), string(owner, "name"));
        }
        Map<String, Object> c = map(s, "cooperative");
        Map<String, Object> p = map(s, "plot");
        List<CertificateDocument.Owner> owners = new ArrayList<>();
        for (Object row : list(s, "owners")) {
            Map<String, Object> owner = (Map<String, Object>) row;
            String memberId = string(owner, "memberId");
            owners.add(new CertificateDocument.Owner(UUID.fromString(memberId),
                    Objects.requireNonNullElse(ownerNames.get(memberId), ""), integer(owner, "numerator"),
                    integer(owner, "denominator")));
        }
        List<CertificateDocument.TypeDebt> types = new ArrayList<>();
        for (Object row : list(s, "types")) {
            Map<String, Object> t = (Map<String, Object>) row;
            types.add(new CertificateDocument.TypeDebt(UUID.fromString(string(t, "chargeTypeId")),
                    optional(t, "code"), string(t, "name"), Money.parse(string(t, "debt"))));
        }
        Map<String, Object> totals = map(s, "totals");
        Map<String, Object> issuer = map(s, "issuer");
        String issuerId = optional(issuer, "memberId");
        Object issuerName = persons.get("issuer");
        return new CertificateDocument(certificateId, string(s, "number"), integer(s, "year"), integer(s, "seq"),
                LocalDate.parse(string(s, "asOf")), Instant.parse(string(s, "issuedAt")),
                new ReceiptContent.Requisites(string(c, "name"), optional(c, "edrpou"), optional(c, "address"),
                        optional(c, "iban"), optional(c, "bank")),
                new CertificateDocument.Plot(UUID.fromString(string(p, "id")), string(p, "number"),
                        optional(p, "street"), new BigDecimal(string(p, "areaM2"))),
                owners, types, Money.parse(string(totals, "debt")), Money.parse(string(totals, "prepayment")),
                new CertificateDocument.Issuer(issuerId == null ? null : UUID.fromString(issuerId),
                        issuerName == null ? "" : issuerName.toString(), string(issuer, "role")),
                null);
    }

    // ---- helpers

    private static void putIfSet(Map<String, Object> map, String key, @Nullable String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("The certificate snapshot has no object " + key);
        }
        return (Map<String, Object>) value;
    }

    private static List<?> list(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalStateException("The certificate snapshot has no value " + key);
        }
        return value.toString();
    }

    private static @Nullable String optional(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static int integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(string(map, key));
    }
}
