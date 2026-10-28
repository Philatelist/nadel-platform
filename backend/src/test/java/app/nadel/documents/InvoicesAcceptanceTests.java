package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Acceptance tests of the whole feature "Invoices with the NBU QR code, debt certificates and cash receipts"
 * (functional-spec.md of 016) through the public HTTP API. The per-case rules stay in the other tests of this package
 * ({@code InvoiceCalculatorTests}, {@code NbuQrPayloadTests}, {@code AmountInWordsTests}, the {@code *AccessTests},
 * {@code DocumentsTenantIsolationTests}, ...); {@code acceptance-coverage.md} of the spec maps every criterion to its
 * tests. These tests walk the criteria across the slices, in a cooperative of their own (the ledger is append-only, so
 * the demo cooperative is left alone): the worked example 1 of §2.3 as the board lives it (preview, build, rebuild, a
 * payment, the new version, what the member sees), the certificate with its register and annulment (§2.7), the cash
 * receipt in words (§2.8), the audit entries of §2.11, the requisites of §2.2 and the permission matrix of §2.10.
 * <p>
 * It lives in the package of the documents tests because their fixtures are package-private.
 * <pre>
 * @layer: integration
 * @spec: context/spec/016-invoices-and-documents
 * @regression
 * </pre>
 */
@Tag("regression")
class InvoicesAcceptanceTests extends CertificateTestSupport {

    private static final String PNG_MAGIC = "\u0089PNG";

    private static void isPdf(MockHttpServletResponse response) {
        byte[] content = response.getContentAsByteArray();
        assertThat(response.getContentType()).startsWith("application/pdf");
        assertThat(content.length).isGreaterThan(1000);
        assertThat(new String(content, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    private static String plain(String text) {
        return text.replace('\u00A0', ' ').replace('\u202F', ' ');
    }

    private String invoiceText(String bearer, UUID invoice) throws Exception {
        MockHttpServletResponse response = invoicePdf(bearer, invoice).andExpect(status().isOk()).andReturn()
                .getResponse();
        isPdf(response);
        return plain(text(response.getContentAsByteArray()));
    }

    private List<JsonNode> audit(String action, int atLeast) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            found.clear();
            JsonNode page = body(send(chairman, get("/api/v1/audit?size=100"), null).andExpect(status().isOk()));
            for (JsonNode entry : page.get("items")) {
                if (entry.get("action").asString().equals(action)) {
                    found.add(entry);
                }
            }
            return found.size() >= atLeast;
        });
        return found;
    }

    private static Map<String, String> changesOf(JsonNode entry) {
        Map<String, String> byField = new java.util.LinkedHashMap<>();
        for (JsonNode change : entry.get("changes")) {
            byField.put(change.get("field").asString(), change.get("newValue").asString());
        }
        return byField;
    }

    /**
     * §2.3 example 1, §2.1, §2.4, §2.5, §2.6, §2.9 end to end: plot 9 owes 75,00 + 338,26 for the closed month; the
     * treasurer previews, builds, rebuilds; the invoice says 413,26 and carries the QR code; a payment and a rebuild
     * give version 2 for that plot only; the member sees version 2 only and downloads it; version 1 still opens.
     */
    @Test
    void plot9TheBoardBuildsTheClosedMonthAPaymentGivesVersion2AndTheMemberSeesTheLatest() throws Exception {
        String month = previousMonth();
        UUID nine = memberPlot("9");
        UUID ten = memberPlot("10");
        charge(nine, "MEMBERSHIP", "75.00", month);
        charge(nine, "ELECTRICITY", "338.26", month);
        UUID tenCharge = charge(ten, "MEMBERSHIP", "75.00", month);
        assertThat(tenCharge).isNotNull();
        closeMonth(month);

        // §2.5 preview: two plots to build, nothing existing, nothing written
        JsonNode preview = body(buildMonth(treasurer, month, true, null).andExpect(status().isOk()));
        assertThat(preview.get("invoiceable").asInt()).isEqualTo(2);
        assertThat(preview.get("toBuild").asInt()).isEqualTo(2);
        assertThat(preview.get("existing").asInt()).isZero();
        assertThat(invoiceCount()).isZero();

        // §2.5 build: one invoice per plot, then nothing new on a rebuild
        JsonNode result = built(month, false);
        assertThat(result.get("built").asInt()).isEqualTo(2);
        assertThat(result.get("skipped").asInt()).isZero();
        JsonNode again = built(month, false);
        assertThat(again.get("built").asInt()).isZero();
        assertThat(again.get("newVersions").asInt()).isZero();
        assertThat(again.get("unchanged").asInt()).isEqualTo(2);
        assertThat(invoiceCount()).isEqualTo(2);

        // §2.3 example 1: start 0,00; charged 413,26; paid 0,00; end 413,26; due 413,26
        JsonNode list = body(send(treasurer, get(INVOICES + "?month=" + month + "&plotId=" + nine), null)
                .andExpect(status().isOk()));
        assertThat(list.get("totalItems").asInt()).isEqualTo(1);
        JsonNode row = list.get("items").get(0);
        UUID version1 = idOf(row);
        assertThat(row.get("number").asString()).isEqualTo(month + "/9");
        assertThat(row.get("version").asInt()).isEqualTo(1);
        assertThat(row.get("due").asString()).isEqualTo("413.26");
        JsonNode snapshot = body(invoice(treasurer, version1).andExpect(status().isOk()));
        assertThat(snapshot.get("totals").get("due").asString()).isEqualTo("413.26");
        assertThat(snapshot.get("totals").get("endDebt").asString()).isEqualTo("413.26");
        assertThat(snapshot.get("monthStatus").asString()).isEqualTo("CLOSED");
        double charged = 0;
        for (JsonNode type : snapshot.get("types")) {
            assertThat(type.get("start").asString()).isEqualTo("0.00");
            assertThat(type.get("paid").asString()).isEqualTo("0.00");
            charged += type.get("charged").asDouble();
        }
        assertThat(charged).isEqualTo(413.26);

        // §2.1: the QR link of the NBU, the image, and the QR block with its caption on the PDF
        assertThat(snapshot.get("qr").get("link").asString()).startsWith("https://qr.bank.gov.ua/");
        MockHttpServletResponse qr = invoiceQr(treasurer, version1).andExpect(status().isOk()).andReturn()
                .getResponse();
        assertThat(qr.getContentType()).isEqualTo("image/png");
        assertThat(new String(qr.getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo(PNG_MAGIC);

        // §2.3 the PDF: Ukrainian text readable, the figures, the QR caption; §2.4 the same bytes twice
        String version1Text = invoiceText(treasurer, version1);
        assertThat(version1Text).contains("Рахунок № " + month + "/9", "До сплати", "Ділянка", "413,26 ₴",
                "Відскануйте QR-код у застосунку свого банку", "Садівниче товариство «Документи»", MEMBER_NAME);
        byte[] first = invoicePdf(treasurer, version1).andReturn().getResponse().getContentAsByteArray();
        byte[] second = invoicePdf(chairman, version1).andReturn().getResponse().getContentAsByteArray();
        assertThat(second).isEqualTo(first);

        // §2.4 a payment, then a rebuild: only plot 9 gets version 2
        pay(nine, "CASH", "100.00", null);
        JsonNode rebuilt = built(month, false);
        assertThat(rebuilt.get("newVersions").asInt()).isEqualTo(1);
        assertThat(rebuilt.get("unchanged").asInt()).isEqualTo(1);
        assertThat(rebuilt.get("built").asInt()).isZero();
        JsonNode versions = body(send(treasurer, get(INVOICES + "?month=" + month + "&plotId=" + nine), null)
                .andExpect(status().isOk()));
        assertThat(versions.get("totalItems").asInt()).isEqualTo(2);
        JsonNode newest = versions.get("items").get(0);
        UUID version2 = idOf(newest);
        assertThat(newest.get("version").asInt()).isEqualTo(2);
        assertThat(newest.get("number").asString()).isEqualTo(month + "/9");
        assertThat(newest.get("due").asString()).isEqualTo("313.26");
        assertThat(newest.get("latest").asBoolean()).isTrue();

        // version 1 still opens with its old numbers
        assertThat(invoiceText(treasurer, version1)).isEqualTo(version1Text);
        assertThat(body(invoice(treasurer, version1)).get("totals").get("due").asString()).isEqualTo("413.26");

        // §2.9 the member sees the latest version of their plots only and downloads it
        JsonNode mine = body(send(member, get("/api/v1/me/invoices"), null).andExpect(status().isOk()));
        assertThat(mine.size()).isEqualTo(2);
        List<String> mineIds = new ArrayList<>();
        mine.forEach(item -> mineIds.add(item.get("id").asString()));
        assertThat(mineIds).contains(version2.toString()).doesNotContain(version1.toString());
        isPdf(invoicePdf(member, version2).andExpect(status().isOk()).andReturn().getResponse());
        invoicePdf(member, version1).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // §2.9 another plot's invoice is the same "not found" as a plot that does not exist
        String foreignMember = person("MEMBER", "Іваненко", "Ігор", "Олегович");
        UUID foreignPlot = plot("77", "Садова");
        own(lastPerson(), foreignPlot);
        charge(foreignPlot, "MEMBERSHIP", "75.00", month);
        UUID foreignInvoice = idOf(issued(foreignPlot, month));
        String missing = invoicePdf(member, UUID.randomUUID()).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString();
        String foreign = invoicePdf(member, foreignInvoice).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString();
        assertThat(foreign).isEqualTo(missing);
        assertThat(foreignMember).isNotBlank();

        // §2.4 / §2.6 the board's list of the plot keeps both versions, newest first
        send(treasurer, get(INVOICES + "?plotId=" + nine), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[1].version").value(1))
                .andExpect(jsonPath("$.items[1].latest").value(false));

        // §2.11 the month builds are audited with their counts, in codes
        List<JsonNode> builds = audit("INVOICES_ISSUED", 3);
        JsonNode firstBuild = builds.get(builds.size() - 1);
        assertThat(firstBuild.get("entityType").asString()).isEqualTo("BILLING_MONTH");
        assertThat(firstBuild.get("entityLabel").asString()).isEqualTo(month);
        assertThat(changesOf(firstBuild)).containsEntry("month", month).containsEntry("built", "2")
                .containsEntry("skipped", "0");
    }

    /**
     * §2.7, §2.11: the chairman issues a certificate for plot 9 (the number of the year, the debt, the register), the
     * annulment needs a reason and happens once, the next certificate takes the next number, and the audit tells it.
     */
    @Test
    void theChairmanIssuesAndAnnulsCertificatesTheRegisterKeepsThemAndTheAuditTellsIt() throws Exception {
        UUID nine = memberPlot("9");
        charge(nine, "MEMBERSHIP", "300.00", previousMonth());
        charge(nine, "ELECTRICITY", "113.26", previousMonth());
        int year = kyivYear();

        JsonNode issued = certificate(nine, null);
        UUID first = idOf(issued);
        assertThat(issued.get("number").asString()).isEqualTo("Д-" + year + "-1");
        String text = pdfText(first);
        assertThat(text).contains("Довідка № Д-" + year + "-1", "Заборгованість становить 413,26 ₴",
                MEMBER_NAME, "Бондаренко Іван Іванович");

        // the register lists it for the treasurer, and the PDF opens
        register(treasurer, null).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].number").value("Д-" + year + "-1"))
                .andExpect(jsonPath("$.items[0].status").value("ISSUED"));

        // annulment: no reason is refused, a reason works once, a second time is told so
        annul(chairman, first, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        annul(chairman, first, "Помилка в даті").andExpect(status().isOk());
        annul(chairman, first, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_ALREADY_ANNULLED"));
        assertThat(pdfText(first)).contains("АНУЛЬОВАНО", "Помилка в даті");
        register(chairman, "status=ANNULLED").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));

        // the next certificate still takes the next number; no gap
        assertThat(certificate(nine, null).get("number").asString()).isEqualTo("Д-" + year + "-2");
        assertThat(lastCertificate(year)).isEqualTo(2);

        // a plot with no debt says so
        UUID clean = memberPlot("10");
        assertThat(pdfText(idOf(certificate(clean, null)))).contains("Заборгованість відсутня");

        // §2.11 the audit log shows issue and annulment with their details, as codes
        List<JsonNode> issues = audit("CERTIFICATE_ISSUED", 3);
        JsonNode firstIssue = issues.get(issues.size() - 1);
        assertThat(firstIssue.get("entityType").asString()).isEqualTo("CERTIFICATE");
        assertThat(firstIssue.get("entityLabel").asString()).isEqualTo("Д-" + year + "-1");
        assertThat(changesOf(firstIssue)).containsEntry("plot", "9").containsEntry("asOf", today().toString())
                .containsEntry("totalDebt", "413.26");
        JsonNode annulled = audit("CERTIFICATE_ANNULLED", 1).get(0);
        assertThat(changesOf(annulled)).containsEntry("reason", "Помилка в даті");
    }

    /** §2.8: the cash receipt in words with the treasurer's name; a bank payment has none; the member gets their own. */
    @Test
    void theTreasurerPrintsACashReceiptInWordsAndTheMemberOpensTheirOwn() throws Exception {
        UUID nine = memberPlot("9");
        charge(nine, "MEMBERSHIP", "100.00", previousMonth());
        JsonNode cash = pay(nine, "CASH", "100.00", null);
        UUID cashPayment = firstPayment(cash);

        MockHttpServletResponse response = receipt(treasurer, cashPayment).andExpect(status().isOk()).andReturn()
                .getResponse();
        isPdf(response);
        String text = plain(text(response.getContentAsByteArray()));
        assertThat(text).contains("Квитанція № " + cash.get("payments").get(0).get("receiptNumber").asLong(),
                "сто гривень 00 копійок", "Прийняв(ла): " + TREASURER_NAME, "№ 9", "100,00 ₴");
        isPdf(receipt(member, cashPayment).andExpect(status().isOk()).andReturn().getResponse());

        UUID bank = firstPayment(pay(nine, "BANK", "10.00", null));
        receipt(treasurer, bank).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_NOT_AVAILABLE"));
    }

    /** §2.2: without the IBAN or the ЄДРПОУ nothing is built and the answer names the field; the banner reads it. */
    @Test
    void withoutTheIbanOrTheEdrpouNothingIsBuiltAndTheMessageNamesTheField() throws Exception {
        UUID nine = memberPlot("9");
        charge(nine, "MEMBERSHIP", "75.00", previousMonth());

        send(chairman, get(INVOICES + "/readiness"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.missing.length()").value(0));

        owner().update("update cooperative_settings set iban = null where tenant_id = ?", tenant);
        send(chairman, get(INVOICES + "/readiness"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.missing.length()").value(1))
                .andExpect(jsonPath("$.missing[0]").value("IBAN"));
        for (ResultActions attempt : List.of(buildMonth(treasurer, previousMonth(), false, null),
                buildMonth(treasurer, previousMonth(), true, null), issue(treasurer, nine, null),
                issueCertificate(chairman, nine, null))) {
            attempt.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUISITES_MISSING"))
                    .andExpect(jsonPath("$.missing[0]").value("IBAN"));
        }

        owner().update("update cooperative_settings set iban = 'UA850000000000000000000123456' "
                + "where tenant_id = ?", tenant);
        owner().update("update tenant set edrpou = null where id = ?", tenant);
        send(treasurer, get(INVOICES + "/readiness"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.missing.length()").value(1))
                .andExpect(jsonPath("$.missing[0]").value("EDRPOU"));
        buildMonth(treasurer, previousMonth(), false, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.missing[0]").value("EDRPOU"));

        assertThat(invoiceCount()).isZero();
        assertThat(lastCertificate(kyivYear())).isZero();
    }

    /**
     * §2.10: the two new permissions are on the roles screen's list; the chairman holds both, the treasurer only the
     * invoices one (so «Видати довідку» is refused for the treasurer); a role with only "View charges" lists invoices
     * and the register but cannot build, print or issue; a member is refused the register and every build.
     */
    @Test
    void thePermissionMatrixOfTheTwoNewPermissions() throws Exception {
        UUID nine = memberPlot("9");
        charge(nine, "MEMBERSHIP", "75.00", previousMonth());

        JsonNode permissions = body(send(chairman, get("/api/v1/permissions"), null).andExpect(status().isOk()));
        List<String> codes = new ArrayList<>();
        permissions.forEach(p -> codes.add(p.get("code").asString()));
        assertThat(codes).contains("INVOICES_ISSUE", "CERTIFICATES_ISSUE");

        JsonNode roles = body(send(chairman, get("/api/v1/roles"), null).andExpect(status().isOk()));
        for (JsonNode role : roles) {
            List<String> held = new ArrayList<>();
            role.get("permissions").forEach(p -> held.add(p.asString()));
            String base = role.has("baseCode") ? role.get("baseCode").asString() : "";
            switch (base) {
                case "CHAIRMAN" -> assertThat(held).contains("INVOICES_ISSUE", "CERTIFICATES_ISSUE");
                case "TREASURER" -> assertThat(held).contains("INVOICES_ISSUE").doesNotContain("CERTIFICATES_ISSUE");
                case "MEMBER" -> assertThat(held).doesNotContain("INVOICES_ISSUE", "CERTIFICATES_ISSUE");
                default -> { }
            }
        }

        // the treasurer builds and prints; the certificate is refused and nothing takes a number
        buildMonth(treasurer, previousMonth(), false, null).andExpect(status().isOk());
        printMonth(treasurer, previousMonth(), "ALL").andExpect(status().isOk());
        issueCertificate(treasurer, nine, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(lastCertificate(kyivYear())).isZero();
        issueCertificate(chairman, nine, null).andExpect(status().isCreated());

        // a viewer sees the lists, but no build, print or certificate action
        String viewer = customRole("CHARGES_VIEW");
        send(viewer, get(INVOICES + "?month=" + previousMonth()), null).andExpect(status().isOk());
        register(viewer, null).andExpect(status().isOk());
        for (ResultActions refused : List.of(buildMonth(viewer, previousMonth(), true, null),
                buildMonth(viewer, previousMonth(), false, null), printMonth(viewer, previousMonth(), "ALL"),
                issue(viewer, nine, null), issueCertificate(viewer, nine, null))) {
            refused.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        // a member: no register, no build
        register(member, null).andExpect(status().isForbidden());
        buildMonth(member, previousMonth(), false, null).andExpect(status().isForbidden());
        send(member, post(PLOT_CERTIFICATES.formatted(nine)), null).andExpect(status().isForbidden());
    }
}
