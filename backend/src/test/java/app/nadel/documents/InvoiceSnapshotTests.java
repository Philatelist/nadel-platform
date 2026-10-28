package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The stored snapshot of an invoice version (feature 016 §2.4, technical considerations 2.5): the names of the owners,
 * the chairman and the treasurer are sealed (never plaintext in the database) and come back opened; a change of owner
 * after the issue keeps the old names on the old version; the same version downloads to the same bytes; the keyed
 * content hash is the same for two versions with the same content and differs once a figure changes.
 */
class InvoiceSnapshotTests extends DocumentsTestSupport {

    private UUID ownedPlot() {
        UUID plot = plot("9", "Садова");
        own(memberId, plot);
        charge(plot, "MEMBERSHIP", "75.00", currentMonth());
        return plot;
    }

    private JsonNode snapshot(UUID invoice) throws Exception {
        return body(invoice(treasurer, invoice).andExpect(status().isOk()));
    }

    private byte[] contentHash(UUID invoice) {
        return owner().queryForObject("select content_hash from invoice where id = ?", byte[].class, invoice);
    }

    @Test
    void theNamesAreSealedInTheDatabaseAndOpenedForTheReader() throws Exception {
        UUID invoice = idOf(issued(ownedPlot(), null));

        String stored = owner().queryForObject("select snapshot::text from invoice where id = ?", String.class,
                invoice);
        assertThat(stored).doesNotContain("Шевченко", "Тарас", "Бондаренко", "Коваленко", "Олена")
                .contains("\"persons\"", "\"schema\": 1", "Садівниче товариство «Документи»");

        JsonNode snapshot = snapshot(invoice);
        assertThat(snapshot.get("owners").get(0).get("name").asString()).isEqualTo(MEMBER_NAME);
        assertThat(snapshot.get("owners").get(0).get("share").asString()).isEqualTo("1/1");
        assertThat(snapshot.get("chairmen").get(0).asString()).isEqualTo("Бондаренко Іван Іванович");
        assertThat(snapshot.get("treasurers").get(0).asString()).isEqualTo(TREASURER_NAME);
        assertThat(snapshot.get("cooperative").get("iban").asString()).isEqualTo("UA850000000000000000000123456");
        assertThat(snapshot.get("cooperative").get("edrpou").asString()).isEqualTo("12345678");
        assertThat(snapshot.get("qr").get("link").asString()).startsWith("https://qr.bank.gov.ua/");
        assertThat(snapshot.get("qr").get("purpose").asString()).contains("Ділянка 9", currentMonth() + "/9");
        assertThat(text(invoicePdf(treasurer, invoice).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray()).replace('\u00A0', ' ')).contains(MEMBER_NAME, TREASURER_NAME,
                        "Бондаренко Іван Іванович");
    }

    @Test
    void anOwnerChangeAfterTheIssueKeepsTheOldNamesOnTheOldVersion() throws Exception {
        UUID plot = ownedPlot();
        UUID first = idOf(issued(plot, null));
        person("MEMBER", "Петренко", "Петро", "Петрович");
        UUID buyer = lastPerson();
        owner().update("update ownership set valid_to = current_date - 1 where plot_id = ?", plot);
        owner().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from) values (?, ?, ?, ?, 1, 1, current_date)", UUID.randomUUID(), tenant, plot, buyer);
        UUID second = idOf(issued(plot, null));

        assertThat(snapshot(first).get("owners").get(0).get("name").asString()).isEqualTo(MEMBER_NAME);
        assertThat(snapshot(second).get("owners").get(0).get("name").asString())
                .isEqualTo("Петренко Петро Петрович");
        assertThat(text(invoicePdf(treasurer, first).andReturn().getResponse().getContentAsByteArray()))
                .contains("Шевченко").doesNotContain("Петренко");
    }

    @Test
    void theSameVersionDownloadsToTheSameBytes() throws Exception {
        UUID invoice = idOf(issued(ownedPlot(), null));

        byte[] first = invoicePdf(treasurer, invoice).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        byte[] second = invoicePdf(chairman, invoice).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();

        assertThat(first).isEqualTo(second);
        assertThat(new String(first, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void theContentHashIsTheSameForTheSameContentAndChangesWithAFigure() throws Exception {
        UUID plot = ownedPlot();
        UUID first = idOf(issued(plot, null));
        UUID second = idOf(issued(plot, null));
        pay(plot, "CASH", "25.00", null);
        UUID third = idOf(issued(plot, null));

        assertThat(contentHash(first)).hasSize(32).isEqualTo(contentHash(second));
        assertThat(contentHash(third)).isNotEqualTo(contentHash(first));
    }
}
