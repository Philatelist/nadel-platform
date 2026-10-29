package app.nadel.documents.internal;

import app.nadel.ledger.InvoiceLedger;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The longest invoices of the demo cooperative for October 2026, as their snapshots were stored (test resources
 * {@code documents/invoice-2026-10-*.json}, the body without the sealed names): plot 15 (two electricity reading
 * intervals, two common consumer shares, a cash payment of 1 000,00 with receipt 7) and plot 2 (ten lines: an
 * adjustment, a reversal, two reading intervals, losses, two common consumer shares; a cash payment with receipt 2).
 * The names are made up here.
 */
final class DemoInvoices {

    static final UUID PLOT_15 = UUID.fromString("0a0b0c0d-0000-4000-8000-000000000015");
    static final UUID PLOT_2 = UUID.fromString("0a0b0c0d-0000-4000-8000-000000000002");

    private DemoInvoices() {
    }

    /** Invoice 2026-10/15. */
    static InvoiceDocument plot15() {
        return InvoiceSnapshots.read(PLOT_15, body("invoice-2026-10-15.json"),
                persons("aaaaaaaa-0000-0000-0000-000000000003", "Мельниченко-Задорожна Оксана Володимирівна"));
    }

    /** Invoice 2026-10/2. */
    static InvoiceDocument plot2() {
        return InvoiceSnapshots.read(PLOT_2, body("invoice-2026-10-2.json"),
                persons("aaaaaaaa-0000-0000-0000-000000000012", "Ткаченко Василь Миколайович"));
    }

    /** The stored body of a demo invoice, as the jsonb column gives it back. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> body(String resource) {
        try (InputStream in = DemoInvoices.class.getResourceAsStream("/documents/" + resource)) {
            return new LinkedHashMap<>(JsonMapper.shared().readValue(in, Map.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The persons of a demo invoice: its one owner, a chairman and a treasurer. */
    static Map<String, Object> persons(String ownerId, String ownerName) {
        Map<String, Object> persons = new LinkedHashMap<>();
        persons.put("owners", List.of(Map.of("memberId", ownerId, "name", ownerName)));
        persons.put("chairmen", List.of("Бондаренко Іван Іванович"));
        persons.put("treasurers", List.of("Коваленко Олена Петрівна"));
        return persons;
    }

    /** The invoice with its charges repeated {@code times} times: longer than any page holds. */
    static InvoiceDocument repeated(InvoiceDocument invoice, int times) {
        List<InvoiceLedger.Line> lines = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            lines.addAll(invoice.lines());
        }
        return new InvoiceDocument(invoice.id(), invoice.version(), invoice.number(), invoice.month(),
                invoice.monthStatus(), invoice.asOf(), invoice.issuedAt(), invoice.issuedOn(), invoice.cooperative(),
                invoice.plot(), invoice.owners(), invoice.chairmen(), invoice.treasurers(), invoice.types(), lines,
                invoice.payments(), invoice.receivedAfter(), invoice.totals(), invoice.qr());
    }
}
