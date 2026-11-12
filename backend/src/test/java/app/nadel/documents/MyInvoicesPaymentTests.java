package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Paying from the phone (feature 021 §2.4, technical considerations 2.5): each row of {@code GET /me/invoices} carries
 * {@code payment {link, iban, purpose}} taken from the stored invoice version, the same values as
 * {@code GET /invoices/{id}} ({@code qr.link}, {@code qr.purpose}, {@code cooperative.iban}) and so the same as the
 * PDF; a later change of the cooperative's IBAN does not change an issued version's details. A version with nothing
 * due has no QR code and no {@code payment}. Another member's invoices are not listed, and the board's list stays
 * without payment details.
 */
class MyInvoicesPaymentTests extends DocumentsTestSupport {

    private static final String MY_INVOICES = "/api/v1/me/invoices";

    private UUID nine;
    private UUID nineThisMonth;
    private UUID foreignInvoice;

    @BeforeEach
    void invoices() throws Exception {
        nine = plot("9", "Садова");
        own(memberId, nine);
        charge(nine, "MEMBERSHIP", "75.00", previousMonth());
        charge(nine, "MEMBERSHIP", "75.00", currentMonth());
        nineThisMonth = idOf(issued(nine, currentMonth()));

        UUID foreign = plot("7", "Садова");
        UUID foreignMember = memberNamed("Іваненко", "Ігор", "Олегович");
        own(foreignMember, foreign);
        charge(foreign, "MEMBERSHIP", "75.00", currentMonth());
        foreignInvoice = idOf(issued(foreign, currentMonth()));
    }

    private JsonNode mine(String bearer) throws Exception {
        return body(send(bearer, get(MY_INVOICES), null).andExpect(status().isOk()));
    }

    private JsonNode snapshot(UUID invoice) throws Exception {
        return body(invoice(member, invoice).andExpect(status().isOk()));
    }

    private static JsonNode rowOf(JsonNode rows, UUID invoice) {
        for (JsonNode row : rows) {
            if (row.get("id").asString().equals(invoice.toString())) {
                return row;
            }
        }
        throw new AssertionError("Invoice " + invoice + " is not listed");
    }

    @Test
    void theRowCarriesTheLinkIbanAndPurposeOfTheStoredVersion() throws Exception {
        JsonNode payment = rowOf(mine(member), nineThisMonth).get("payment");
        JsonNode snapshot = snapshot(nineThisMonth);

        assertThat(payment).isNotNull();
        assertThat(payment.get("link").asString()).startsWith("https://qr.bank.gov.ua/")
                .isEqualTo(snapshot.get("qr").get("link").asString());
        assertThat(payment.get("purpose").asString()).isEqualTo(snapshot.get("qr").get("purpose").asString());
        assertThat(payment.get("iban").asString()).isEqualTo(snapshot.get("cooperative").get("iban").asString())
                .isEqualTo("UA850000000000000000000123456");
    }

    @Test
    void aLaterChangeOfTheIbanDoesNotChangeAnIssuedVersion() throws Exception {
        owner().update("update cooperative_settings set iban = 'UA210000000000000000000654321' where tenant_id = ?",
                tenant);

        JsonNode payment = rowOf(mine(member), nineThisMonth).get("payment");

        assertThat(payment.get("iban").asString()).isEqualTo("UA850000000000000000000123456")
                .isEqualTo(snapshot(nineThisMonth).get("cooperative").get("iban").asString());
    }

    @Test
    void aVersionWithNothingDueHasNoPaymentDetails() throws Exception {
        UUID eleven = plot("11", "Садова");
        own(memberId, eleven);
        charge(eleven, "MEMBERSHIP", "75.00", currentMonth());
        pay(eleven, "CASH", "75.00", null);
        UUID paidUp = idOf(issued(eleven, currentMonth()));

        JsonNode row = rowOf(mine(member), paidUp);

        assertThat(row.get("due").asString()).isEqualTo("0.00");
        assertThat(row.has("payment")).isFalse();
        assertThat(snapshot(paidUp).has("qr")).isFalse();
        // the plot with something due keeps its details
        assertThat(rowOf(mine(member), nineThisMonth).has("payment")).isTrue();
    }

    @Test
    void anotherMembersInvoicesAreNotListed() throws Exception {
        JsonNode rows = mine(member);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id").asString()).isEqualTo(nineThisMonth.toString());
        for (JsonNode row : rows) {
            assertThat(row.get("id").asString()).isNotEqualTo(foreignInvoice.toString());
        }
    }

    @Test
    void theBoardsListHasNoPaymentDetails() throws Exception {
        JsonNode page = body(send(treasurer, get(INVOICES), null).andExpect(status().isOk()));

        assertThat(page.get("items")).hasSize(2);
        for (JsonNode row : page.get("items")) {
            assertThat(row.has("payment")).isFalse();
        }
    }
}
