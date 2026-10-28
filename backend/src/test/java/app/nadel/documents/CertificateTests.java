package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Debt certificates (feature 016 §2.7, §2.11, technical considerations 2.7): the debt and the no-debt texts, the
 * figures equal to the plot balances report for the same day, the number of the year, a future day refused, the plots
 * that get none, the annulment (reason required, once, the register and the PDF marked), byte-identical PDFs and the
 * audit entries.
 */
class CertificateTests extends CertificateTestSupport {

    /** {@code 05.11.2026}: a day as the documents print it. */
    private static String kyivDay(LocalDate day) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy").format(day);
    }

    /** The row of the plot in the sheet of the plot balances report, cells by column key. */
    private static Map<String, String> reportRow(JsonNode sheet, String number) {
        List<String> keys = new ArrayList<>();
        sheet.get("columns").forEach(column -> keys.add(column.get("key").asString()));
        for (JsonNode row : sheet.get("rows")) {
            JsonNode cells = row.get("cells");
            if (cells.get(0).get("value").asString().equals(number)) {
                Map<String, String> byKey = new LinkedHashMap<>();
                for (int i = 0; i < keys.size(); i++) {
                    byKey.put(keys.get(i), cells.get(i).has("value") ? cells.get(i).get("value").asString() : "-");
                }
                return byKey;
            }
        }
        throw new AssertionError("The report has no row of plot " + number);
    }

    private Map<String, String> plotBalances(LocalDate asOf, String number) throws Exception {
        JsonNode report = body(send(chairman, get("/api/v1/reports/plot-balances").param("asOf", asOf.toString()),
                null).andExpect(status().isOk()));
        return reportRow(report.get("sheets").get(0), number);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshotOf(UUID certificate) throws Exception {
        String text = owner().queryForObject("select snapshot::text from certificate where id = ?", String.class,
                certificate);
        return json.readValue(text, Map.class);
    }

    @Test
    void plot9OwingSaysHowMuchWithThePerTypeAmountsAndIsInTheRegister() throws Exception {
        UUID plot = memberPlot("9");
        charge(plot, "MEMBERSHIP", "300.00", previousMonth());
        charge(plot, "ELECTRICITY", "113.26", previousMonth());

        JsonNode row = certificate(plot, null);

        String number = "Д-" + kyivYear() + "-1";
        assertThat(row.get("number").asString()).isEqualTo(number);
        assertThat(row.get("totalDebt").asString()).isEqualTo("413.26");
        assertThat(row.get("status").asString()).isEqualTo("ISSUED");
        assertThat(row.get("asOf").asString()).isEqualTo(today().toString());
        assertThat(row.get("plotNumber").asString()).isEqualTo("9");
        assertThat(row.get("issuedByName").asString()).isEqualTo("Бондаренко Іван Іванович");
        String text = pdfText(idOf(row));
        assertThat(text).contains("Довідка № " + number, "Заборгованість становить 413,26 ₴",
                "Членський внесок", "300,00 ₴", "Електроенергія", "113,26 ₴", "Ділянка № 9, Садова",
                "Площа: 600,00 м² (6,00 сотки)", MEMBER_NAME + " (1/1)", "Голова: Бондаренко Іван Іванович",
                "(підпис)", "М. П.", "Станом на " + kyivDay(today()),
                "Садівниче товариство «Документи»", "Код ЄДРПОУ: 12345678")
                .doesNotContain("Заборгованість відсутня", "АНУЛЬОВАНО");

        register(treasurer, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].number").value(number))
                .andExpect(jsonPath("$.items[0].totalDebt").value("413.26"))
                .andExpect(jsonPath("$.items[0].status").value("ISSUED"));
    }

    @Test
    void aPlotWithoutDebtSaysThereIsNone() throws Exception {
        UUID plot = memberPlot("9");

        JsonNode row = certificate(plot, null);

        assertThat(row.get("totalDebt").asString()).isEqualTo("0.00");
        assertThat(pdfText(idOf(row))).contains("Заборгованість відсутня", "Аванс (передоплата): 0,00 ₴")
                .doesNotContain("Заборгованість становить");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFiguresEqualThePlotBalancesReportForTheDay() throws Exception {
        UUID plot = memberPlot("9");
        String twoMonthsAgo = YearMonth.from(today()).minusMonths(2).toString();
        charge(plot, "MEMBERSHIP", "200.00", twoMonthsAgo);
        charge(plot, "ELECTRICITY", "80.00", previousMonth());
        pay(plot, "CASH", "50.00", null);
        LocalDate endOfLastMonth = YearMonth.from(today()).minusMonths(1).atEndOfMonth();

        for (LocalDate day : List.of(today(), endOfLastMonth)) {
            UUID certificate = idOf(certificate(plot, day));
            Map<String, String> report = plotBalances(day, "9");
            Map<String, Object> snapshot = snapshotOf(certificate);
            Map<String, Object> totals = (Map<String, Object>) snapshot.get("totals");

            assertThat(totals.get("debt")).as("total debt as of %s", day).isEqualTo(report.get("total"));
            assertThat(totals.get("prepayment")).as("prepayment as of %s", day).isEqualTo(report.get("prepayment"));
            for (Object type : (List<?>) snapshot.get("types")) {
                Map<String, Object> t = (Map<String, Object>) type;
                assertThat(t.get("debt")).as("debt of %s as of %s", t.get("code"), day)
                        .isEqualTo(report.get("debt." + t.get("chargeTypeId")));
            }
            assertThat(owner().queryForObject("select total_debt::text from certificate where id = ?",
                    String.class, certificate)).isEqualTo(report.get("total"));
        }
    }

    @Test
    void theNamesAreSealedInTheSnapshot() throws Exception {
        UUID plot = memberPlot("9");

        UUID certificate = idOf(certificate(plot, null));

        String stored = owner().queryForObject("select snapshot::text from certificate where id = ?", String.class,
                certificate);
        assertThat(stored).doesNotContain("Шевченко", "Бондаренко").contains("\"persons\"");
        assertThat(pdfText(certificate)).contains(MEMBER_NAME, "Бондаренко Іван Іванович");
    }

    @Test
    void aDayAfterTodayIsRefusedAndTakesNoNumber() throws Exception {
        UUID plot = memberPlot("9");

        issueCertificate(chairman, plot, today().plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("asOf"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));

        assertThat(lastCertificate(kyivYear())).isZero();
        assertThat(certificate(plot, today().minusDays(30)).get("number").asString())
                .isEqualTo("Д-" + kyivYear() + "-1");
    }

    @Test
    void commonLandAnArchivedPlotAnUnknownPlotAndMissingRequisitesGetNoCertificate() throws Exception {
        UUID archived = memberPlot("11");
        owner().update("update plot set archived_at = now() where id = ?", archived);
        UUID common = plot("12", "Садова");
        owner().update("update plot set kind = 'COMMON' where id = ?", common);

        issueCertificate(chairman, common, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));
        issueCertificate(chairman, archived, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));
        issueCertificate(chairman, UUID.randomUUID(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));

        UUID plot = memberPlot("9");
        owner().update("update cooperative_settings set iban = null where tenant_id = ?", tenant);
        issueCertificate(chairman, plot, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUISITES_MISSING"))
                .andExpect(jsonPath("$.missing[0]").value("IBAN"));

        assertThat(owner().queryForObject("select count(*) from certificate where tenant_id = ?", Integer.class,
                tenant)).isZero();
        assertThat(lastCertificate(kyivYear())).isZero();
    }

    @Test
    void aPlotWithoutAnOwnerGetsACertificateWithoutOwners() throws Exception {
        UUID plot = plot("10", "Садова");

        UUID certificate = idOf(certificate(plot, null));

        assertThat(pdfText(certificate)).contains("Власники: не зазначено");
    }

    @Test
    void anAnnulmentNeedsAReasonOfAtMost500Characters() throws Exception {
        UUID certificate = idOf(certificate(memberPlot("9"), null));

        annul(chairman, certificate, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        annul(chairman, certificate, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        annul(chairman, certificate, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        annul(chairman, certificate, "я".repeat(501)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));

        assertThat(owner().queryForObject("select annulled_at is null from certificate where id = ?", Boolean.class,
                certificate)).isTrue();
        annul(chairman, certificate, "я".repeat(500)).andExpect(status().isOk());
    }

    @Test
    void anAnnulledCertificateIsMarkedStaysInTheRegisterAndCannotBeAnnulledAgain() throws Exception {
        UUID plot = memberPlot("9");
        charge(plot, "MEMBERSHIP", "75.00", previousMonth());
        UUID certificate = idOf(certificate(plot, null));

        annul(chairman, certificate, "  Помилка в даті  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANNULLED"))
                .andExpect(jsonPath("$.annulReason").value("Помилка в даті"))
                .andExpect(jsonPath("$.annulledByName").value("Бондаренко Іван Іванович"))
                .andExpect(jsonPath("$.annulledAt").exists());
        annul(chairman, certificate, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_ALREADY_ANNULLED"));
        annul(chairman, UUID.randomUUID(), "Немає такої").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(owner().queryForObject("select annul_reason from certificate where id = ?", String.class,
                certificate)).isEqualTo("Помилка в даті");
        assertThat(pdfText(certificate)).contains("АНУЛЬОВАНО " + kyivDay(today()),
                "Причина: Помилка в даті", "Заборгованість становить 75,00 ₴");
        register(treasurer, "status=ANNULLED").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(certificate.toString()))
                .andExpect(jsonPath("$.items[0].annulReason").value("Помилка в даті"));
        register(treasurer, "status=ISSUED").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        JsonNode next = certificate(plot, null);
        assertThat(next.get("number").asString()).isEqualTo("Д-" + kyivYear() + "-2");
        register(treasurer, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].number").value("Д-" + kyivYear() + "-2"))
                .andExpect(jsonPath("$.items[1].status").value("ANNULLED"));
    }

    @Test
    void theRegisterPagesNewestFirst() throws Exception {
        UUID plot = memberPlot("9");
        for (int i = 0; i < 3; i++) {
            certificate(plot, null);
        }

        register(treasurer, "page=0&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].number").value("Д-" + kyivYear() + "-3"))
                .andExpect(jsonPath("$.items[1].number").value("Д-" + kyivYear() + "-2"));
        register(treasurer, "page=1&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].number").value("Д-" + kyivYear() + "-1"));
    }

    @Test
    void theSameCertificateGivesTheSameBytesBeforeAndAfterItsAnnulment() throws Exception {
        UUID plot = memberPlot("9");
        charge(plot, "MEMBERSHIP", "75.00", previousMonth());
        UUID certificate = idOf(certificate(plot, null));

        byte[] first = pdfOf(certificate);
        assertThat(pdfOf(certificate)).isEqualTo(first);
        assertThat(new String(first, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        annul(chairman, certificate, "Помилка").andExpect(status().isOk());
        byte[] annulled = pdfOf(certificate);
        assertThat(annulled).isNotEqualTo(first);
        assertThat(pdfOf(certificate)).isEqualTo(annulled);
        assertThat(certificatePdf(chairman, certificate).andReturn().getResponse().getHeader("Content-Disposition"))
                .contains("certificate_D-" + kyivYear() + "-1.pdf");
    }

    @Test
    void theIssueAndTheAnnulmentAreAudited() throws Exception {
        UUID plot = memberPlot("9");
        charge(plot, "MEMBERSHIP", "75.00", previousMonth());
        UUID certificate = idOf(certificate(plot, null));
        annul(chairman, certificate, "Помилка в даті").andExpect(status().isOk());
        String number = "Д-" + kyivYear() + "-1";

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and entity_type = 'CERTIFICATE'",
                Integer.class, tenant) == 2);
        assertThat(owner().queryForObject("select entity_id || '|' || entity_label from audit_entry "
                + "where tenant_id = ? and action = 'CERTIFICATE_ISSUED'", String.class, tenant))
                .isEqualTo(certificate + "|" + number);
        assertThat(owner().queryForObject("select changes::text from audit_entry where tenant_id = ? "
                + "and action = 'CERTIFICATE_ISSUED'", String.class, tenant))
                .contains("\"plot\"", "\"9\"", "\"number\"", number, "\"asOf\"", today().toString(),
                        "\"totalDebt\"", "75.00")
                .doesNotContain("Шевченко", "Бондаренко");
        assertThat(owner().queryForObject("select changes::text from audit_entry where tenant_id = ? "
                + "and action = 'CERTIFICATE_ANNULLED'", String.class, tenant))
                .contains("\"number\"", number, "\"reason\"", "Помилка в даті");
    }
}
