package app.nadel.documents.internal;

import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.InvoiceLedger.LineKind;
import app.nadel.ledger.InvoiceLedger.LineSource;
import app.nadel.ledger.MonthStatus;
import app.nadel.money.Money;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored form of an invoice version (feature 016 §2.4, technical considerations 2.5): the snapshot, schema 1, in
 * the {@code invoice.snapshot} jsonb column. Codes, numbers and the texts printed (the cooperative's requisites, the
 * plot, the charge type names); every amount a string with two decimals, days ISO, months {@code YYYY-MM}. The names
 * of people (the owners, the chairmen, the treasurers) are one JSON object sealed with the cooperative's key at
 * {@code FieldRef("invoice", "snapshot.persons", invoiceId)} (ADR 0016) and stored as base64 under {@code persons}, so
 * a snapshot outlives a change of owner without plaintext names in the database. The payment QR code's link and
 * purpose are stored, so a later change of the code never changes an old version.
 * <p>
 * <b>Optional keys</b> (added after the first invoices were stored, so a snapshot without them still opens):
 * {@code payments}, the payment rows booked in the month ({@code paymentId}, {@code reverses} for a reversal,
 * {@code receivedOn}, {@code amount} with its sign, {@code receiptNumber} and {@code bankReference} when set), written
 * only when the month has any, so the canonical form of an invoice without payments in its month is what it was
 * before the key existed.
 * <p>
 * <b>Change detection</b>: the canonical form is the snapshot without {@code version}, {@code asOf},
 * {@code issuedAt}, {@code issuedOn} and {@code qr}, with the persons in plaintext, every object's keys sorted, written
 * as compact JSON; its content hash is the keyed blind index {@link IndexKind#DOCUMENT_CONTENT} of that text, so the
 * hash leaks no names. Two versions with the same figures, lines, owners and requisites have the same hash.
 */
@Component
class InvoiceSnapshots {

    /** The version of the snapshot format. */
    static final int SCHEMA = 1;

    static final String TABLE = "invoice";
    static final String PERSONS = "snapshot.persons";

    /** The keys left out of the canonical form: they change with every version whatever the content. */
    private static final Set<String> VOLATILE = Set.of("version", "asOf", "issuedAt", "issuedOn", "qr");

    /** The stored snapshot and its content hash. */
    record Sealed(Map<String, Object> snapshot, byte[] contentHash) {
    }

    private final PersonalDataCipher cipher;
    private final JsonMapper json;

    InvoiceSnapshots(PersonalDataCipher cipher, JsonMapper json) {
        this.cipher = cipher;
        this.json = json;
    }

    /** The snapshot to store, the persons sealed for the invoice row, and its content hash. */
    Sealed seal(UUID tenantId, InvoiceDocument document) {
        Map<String, Object> snapshot = body(document);
        byte[] sealed = cipher.encrypt(tenantId, field(document.id()), compact(persons(document)));
        snapshot.put("persons", Base64.getEncoder().encodeToString(sealed));
        return new Sealed(snapshot, contentHash(tenantId, document));
    }

    /** The keyed hash of the canonical form ({@link IndexKind#DOCUMENT_CONTENT}). */
    byte[] contentHash(UUID tenantId, InvoiceDocument document) {
        return cipher.blindIndex(tenantId, IndexKind.DOCUMENT_CONTENT, canonical(document));
    }

    /** The invoice of a stored snapshot, the persons opened with the cooperative's key. */
    InvoiceDocument open(UUID tenantId, UUID invoiceId, Map<String, Object> snapshot) {
        String persons = cipher.decrypt(tenantId, field(invoiceId),
                Base64.getDecoder().decode(string(snapshot, "persons")));
        return read(invoiceId, snapshot, json.readValue(persons, Map.class));
    }

    /**
     * The canonical form: the snapshot without the volatile keys, the persons in plaintext, keys sorted, compact JSON.
     */
    static String canonical(InvoiceDocument document) {
        Map<String, Object> body = body(document);
        VOLATILE.forEach(body::remove);
        body.put("persons", persons(document));
        return compact(body);
    }

    /** The whole snapshot with the persons in plaintext, keys sorted: the identity of the version's PDF file. */
    static String identity(InvoiceDocument document) {
        Map<String, Object> body = body(document);
        body.put("id", document.id().toString());
        body.put("persons", persons(document));
        return compact(body);
    }

    static FieldRef field(UUID invoiceId) {
        return new FieldRef(TABLE, PERSONS, invoiceId);
    }

    // ---- writing

    /** The snapshot without the persons. */
    static Map<String, Object> body(InvoiceDocument d) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("number", d.number());
        body.put("version", d.version());
        body.put("month", d.month().toString());
        body.put("monthStatus", d.monthStatus().name());
        body.put("asOf", d.asOf().toString());
        body.put("issuedAt", d.issuedAt().toString());
        body.put("issuedOn", d.issuedOn().toString());
        Map<String, Object> cooperative = new LinkedHashMap<>();
        cooperative.put("officialName", d.cooperative().officialName());
        putIfSet(cooperative, "edrpou", d.cooperative().edrpou());
        putIfSet(cooperative, "address", d.cooperative().address());
        putIfSet(cooperative, "bankName", d.cooperative().bankName());
        putIfSet(cooperative, "iban", d.cooperative().iban());
        body.put("cooperative", cooperative);
        Map<String, Object> plot = new LinkedHashMap<>();
        plot.put("id", d.plot().id().toString());
        plot.put("number", d.plot().number());
        putIfSet(plot, "street", d.plot().street());
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
            row.put("start", type.start().toString());
            row.put("charged", type.charged().toString());
            row.put("paid", type.paid().toString());
            row.put("end", type.end().toString());
            return row;
        }).toList());
        body.put("lines", d.lines().stream().map(InvoiceSnapshots::line).toList());
        if (!d.payments().isEmpty()) {
            body.put("payments", d.payments().stream().map(payment -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("paymentId", payment.paymentId().toString());
                putIfSet(row, "reverses", payment.reverses() == null ? null : payment.reverses().toString());
                row.put("receivedOn", payment.receivedOn().toString());
                row.put("amount", payment.amount().toString());
                putIfSet(row, "receiptNumber", payment.receiptNumber() == null ? null
                        : payment.receiptNumber().toString());
                putIfSet(row, "bankReference", payment.bankReference());
                return row;
            }).toList());
        }
        body.put("receivedAfter", d.receivedAfter().stream().map(payment -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paymentId", payment.paymentId().toString());
            row.put("receivedOn", payment.receivedOn().toString());
            row.put("bookedIn", payment.bookedIn().toString());
            row.put("amount", payment.amount().toString());
            return row;
        }).toList());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("endDebt", d.totals().endDebt().toString());
        totals.put("prepayment", d.totals().prepayment().toString());
        totals.put("receivedAfter", d.totals().receivedAfter().toString());
        totals.put("due", d.totals().due().toString());
        body.put("totals", totals);
        if (d.qr() != null) {
            body.put("qr", Map.of("link", d.qr().link(), "purpose", d.qr().purpose()));
        }
        return body;
    }

    private static Map<String, Object> line(InvoiceLedger.Line line) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chargeId", line.chargeId().toString());
        row.put("chargeTypeId", line.chargeTypeId().toString());
        row.put("kind", line.kind().name());
        row.put("source", line.source().name());
        row.put("amount", line.amount().toString());
        row.put("serviceFrom", line.serviceFrom().toString());
        row.put("serviceTo", line.serviceTo().toString());
        putIfSet(row, "corrects", line.corrects() == null ? null : line.corrects().toString());
        row.put("parts", line.parts().stream().map(part -> {
            Map<String, Object> p = new LinkedHashMap<>();
            putIfSet(p, "basis", part.basis());
            putIfSet(p, "frequency", part.frequency());
            putIfSet(p, "quantity", part.quantity());
            putIfSet(p, "unit", part.unit());
            putIfSet(p, "rate", part.rate());
            p.put("days", part.days());
            p.put("from", part.from().toString());
            p.put("to", part.to().toString());
            return p;
        }).toList());
        row.put("readings", line.readings().stream().map(reading -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("meterId", reading.meterId().toString());
            r.put("takenOn", reading.takenOn().toString());
            r.put("value", reading.value());
            return r;
        }).toList());
        putIfSet(row, "lossesPercent", line.lossesPercent());
        putIfSet(row, "consumption", line.consumption());
        putIfSet(row, "consumerName", line.consumerName());
        return row;
    }

    /** The names, in plaintext: what is sealed under {@code persons}. */
    static Map<String, Object> persons(InvoiceDocument d) {
        Map<String, Object> persons = new LinkedHashMap<>();
        persons.put("owners", d.owners().stream().map(owner -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", owner.memberId().toString());
            row.put("name", owner.name());
            return row;
        }).toList());
        persons.put("chairmen", d.chairmen());
        persons.put("treasurers", d.treasurers());
        return persons;
    }

    // ---- reading

    /** The invoice of a stored snapshot whose persons are already open. */
    @SuppressWarnings("unchecked")
    static InvoiceDocument read(UUID invoiceId, Map<String, Object> s, Map<String, Object> persons) {
        Map<String, String> ownerNames = new HashMap<>();
        for (Object row : list(persons, "owners")) {
            Map<String, Object> owner = (Map<String, Object>) row;
            ownerNames.put(string(owner, "memberId"), string(owner, "name"));
        }
        Map<String, Object> c = map(s, "cooperative");
        Map<String, Object> p = map(s, "plot");
        List<InvoiceDocument.Owner> owners = new ArrayList<>();
        for (Object row : list(s, "owners")) {
            Map<String, Object> owner = (Map<String, Object>) row;
            String memberId = string(owner, "memberId");
            owners.add(new InvoiceDocument.Owner(UUID.fromString(memberId),
                    Objects.requireNonNullElse(ownerNames.get(memberId), ""), integer(owner, "numerator"),
                    integer(owner, "denominator")));
        }
        List<InvoiceDocument.TypeRow> types = new ArrayList<>();
        for (Object row : list(s, "types")) {
            Map<String, Object> t = (Map<String, Object>) row;
            types.add(new InvoiceDocument.TypeRow(uuid(t, "chargeTypeId"), optional(t, "code"), string(t, "name"),
                    money(t, "start"), money(t, "charged"), money(t, "paid"), money(t, "end")));
        }
        List<InvoiceLedger.Line> lines = new ArrayList<>();
        for (Object row : list(s, "lines")) {
            lines.add(line((Map<String, Object>) row));
        }
        List<InvoiceLedger.MonthPayment> payments = new ArrayList<>();
        for (Object row : list(s, "payments")) {
            Map<String, Object> m = (Map<String, Object>) row;
            String reverses = optional(m, "reverses");
            String receipt = optional(m, "receiptNumber");
            payments.add(new InvoiceLedger.MonthPayment(uuid(m, "paymentId"),
                    reverses == null ? null : UUID.fromString(reverses), LocalDate.parse(string(m, "receivedOn")),
                    money(m, "amount"), receipt == null ? null : Long.valueOf(receipt), optional(m, "bankReference")));
        }
        List<InvoiceLedger.LaterPayment> later = new ArrayList<>();
        for (Object row : list(s, "receivedAfter")) {
            Map<String, Object> l = (Map<String, Object>) row;
            later.add(new InvoiceLedger.LaterPayment(uuid(l, "paymentId"), LocalDate.parse(string(l, "receivedOn")),
                    YearMonth.parse(string(l, "bookedIn")), money(l, "amount")));
        }
        Map<String, Object> totals = map(s, "totals");
        Object qr = s.get("qr");
        InvoiceDocument.Qr code = null;
        if (qr instanceof Map<?, ?> q) {
            Map<String, Object> link = (Map<String, Object>) q;
            code = new InvoiceDocument.Qr(string(link, "link"), string(link, "purpose"));
        }
        return new InvoiceDocument(invoiceId, integer(s, "version"), string(s, "number"),
                YearMonth.parse(string(s, "month")), MonthStatus.valueOf(string(s, "monthStatus")),
                Instant.parse(string(s, "asOf")), Instant.parse(string(s, "issuedAt")),
                LocalDate.parse(string(s, "issuedOn")),
                new InvoiceDocument.Cooperative(string(c, "officialName"), optional(c, "edrpou"),
                        optional(c, "address"), optional(c, "bankName"), optional(c, "iban")),
                new InvoiceDocument.Plot(uuid(p, "id"), string(p, "number"), optional(p, "street")), owners,
                strings(persons, "chairmen"), strings(persons, "treasurers"), types, lines, payments, later,
                new InvoiceDocument.Totals(money(totals, "endDebt"), money(totals, "prepayment"),
                        money(totals, "receivedAfter"), money(totals, "due")),
                code);
    }

    @SuppressWarnings("unchecked")
    private static InvoiceLedger.Line line(Map<String, Object> l) {
        List<InvoiceLedger.Part> parts = new ArrayList<>();
        for (Object row : list(l, "parts")) {
            Map<String, Object> p = (Map<String, Object>) row;
            parts.add(new InvoiceLedger.Part(optional(p, "basis"), optional(p, "frequency"), optional(p, "quantity"),
                    optional(p, "unit"), optional(p, "rate"), integer(p, "days"), LocalDate.parse(string(p, "from")),
                    LocalDate.parse(string(p, "to"))));
        }
        List<InvoiceLedger.Reading> readings = new ArrayList<>();
        for (Object row : list(l, "readings")) {
            Map<String, Object> r = (Map<String, Object>) row;
            readings.add(new InvoiceLedger.Reading(uuid(r, "meterId"), LocalDate.parse(string(r, "takenOn")),
                    string(r, "value")));
        }
        String corrects = optional(l, "corrects");
        return new InvoiceLedger.Line(uuid(l, "chargeId"), uuid(l, "chargeTypeId"),
                LineKind.valueOf(string(l, "kind")), LineSource.valueOf(string(l, "source")), money(l, "amount"),
                LocalDate.parse(string(l, "serviceFrom")), LocalDate.parse(string(l, "serviceTo")),
                corrects == null ? null : YearMonth.parse(corrects), parts, readings, optional(l, "lossesPercent"),
                optional(l, "consumption"), optional(l, "consumerName"));
    }

    // ---- helpers

    /** Compact JSON with every object's keys sorted (lists keep their order). */
    static String compact(Object value) {
        return new String(JsonMapper.shared().writeValueAsBytes(sorted(value)), StandardCharsets.UTF_8);
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), sorted(item)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(InvoiceSnapshots::sorted).toList();
        }
        return value;
    }

    private static void putIfSet(Map<String, Object> map, String key, @Nullable String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("The invoice snapshot has no object " + key);
        }
        return (Map<String, Object>) value;
    }

    private static List<?> list(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof List<?> list ? list : List.of();
    }

    private static List<String> strings(Map<String, Object> map, String key) {
        return list(map, key).stream().map(String::valueOf).toList();
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalStateException("The invoice snapshot has no value " + key);
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

    private static UUID uuid(Map<String, Object> map, String key) {
        return UUID.fromString(string(map, key));
    }

    private static Money money(Map<String, Object> map, String key) {
        return Money.parse(string(map, key));
    }
}
