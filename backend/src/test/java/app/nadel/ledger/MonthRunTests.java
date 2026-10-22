package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The month run of recurring tariffs through the API (feature 011 §2.3 and the month-run rows of §3): preview, booking,
 * rerun, a plot added later, 0.00, a reversed charge, conflicts to the queue naming both tariffs, a stale preview, a
 * closed and a future month, scopes, inactive types and tariffs charged by readings, the audit entry, and who may run.
 * The runs are of the current month in Europe/Kyiv (or the one before), in a cooperative of the test's own.
 */
class MonthRunTests extends FreshCooperativeSupport {

    private String month;
    private UUID plot7;
    private UUID plot9;
    private Tariff membership;
    private Tariff landTax;

    @BeforeEach
    void membershipAndLandTax() {
        month = ym(0);
        plot7 = plot("7", "600.00");
        plot9 = plot("9", "612.00");
        // 50 per sotka per year and 35 per sotka per year, billed monthly
        membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2026-01-01");
        landTax = tariff("LAND_TAX", "Земельний податок", "PER_AREA_SOTKA", "YEARLY", "35.0000", "2026-01-01");
    }

    private JsonNode run(String bearer, String month, boolean preview, Object body) throws Exception {
        return body(send(bearer, post(RUN.formatted(month, preview)), body).andExpect(status().isOk()));
    }

    private JsonNode preview() throws Exception {
        return run(chairman, month, true, null);
    }

    private JsonNode book() throws Exception {
        return run(chairman, month, false, null);
    }

    // ---- preview and booking

    @Test
    void thePreviewShowsThePlotsAndTheTotalsPerChargeTypeAndWritesNothing() throws Exception {
        JsonNode preview = preview();

        assertThat(preview.get("month").asString()).isEqualTo(month);
        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.get("plots").asInt()).isEqualTo(2);
        // plot 7: 25.00 + 17.50; plot 9 (612 m2): 25.50 + 17.85
        assertThat(total(preview, "MEMBERSHIP")).isEqualTo("50.50");
        assertThat(total(preview, "LAND_TAX")).isEqualTo("35.35");
        assertThat(preview.get("totals").get(0).get("chargeTypeCode").asString()).isEqualTo("MEMBERSHIP");
        assertThat(preview.get("total").asString()).isEqualTo("85.85");
        assertThat(preview.get("notCharged")).isEmpty();
        assertThat(preview.get("created").asInt()).isZero();
        assertThat(preview.get("digest").asString()).hasSize(64);
        assertThat(chargeCount()).isZero();
        assertThat(owner().queryForObject("select count(*) from billing_month where tenant_id = ?", Integer.class,
                tenant)).isZero();
    }

    @Test
    void bookingChargesPlot7TwentyFiveForTheWholeMonthWithItsVersion() throws Exception {
        String digest = preview().get("digest").asString();

        JsonNode booked = run(chairman, month, false, digest(digest));

        assertThat(booked.get("preview").asBoolean()).isFalse();
        assertThat(booked.get("created").asInt()).isEqualTo(4);
        assertThat(booked.get("total").asString()).isEqualTo("85.85");
        assertThat(booked.get("digest").asString()).isEqualTo(digest);
        YearMonth ym = YearMonth.parse(month);
        List<Map<String, Object>> charges = charges(plot7);
        assertThat(charges).hasSize(2);
        Map<String, Object> fee = charges.stream().filter(c -> membership.id().equals(c.get("tariff_id")))
                .findFirst().orElseThrow();
        assertThat(fee.get("amount")).isEqualTo("25.00");
        assertThat(fee.get("kind")).isEqualTo("REGULAR");
        assertThat(fee.get("source")).isEqualTo("TARIFF");
        assertThat(date(fee.get("service_from"))).isEqualTo(ym.atDay(1));
        assertThat(date(fee.get("service_to"))).isEqualTo(ym.atEndOfMonth());
        assertThat(date(fee.get("accrued_on"))).isEqualTo(ym.atDay(1));
        assertThat(date(fee.get("billing_month"))).isEqualTo(ym.atDay(1));
        assertThat(fee.get("source_key")).isEqualTo("TARIFF:" + membership.id() + ":" + plot7 + ":" + month);
        assertThat(fee.get("created_by")).isNotNull();
        assertThat(owner().queryForList("select tariff_version_id, days, units_num::text as units, "
                + "rate::text as rate from charge_part where charge_id = ?", fee.get("id")))
                .singleElement().satisfies(part -> {
                    assertThat(part.get("tariff_version_id")).isEqualTo(membership.version());
                    assertThat(part.get("days")).isEqualTo(ym.lengthOfMonth());
                    assertThat(new java.math.BigDecimal((String) part.get("units"))).isEqualByComparingTo("6");
                    assertThat(part.get("rate")).isEqualTo("50.0000");
                });

        // the account shows the charge with its calculation
        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(plot7)), null).andExpect(status().isOk()));
        JsonNode charge = null;
        for (JsonNode c : account.get("charges")) {
            if (c.get("chargeType").get("code").asString().equals("MEMBERSHIP")) {
                charge = c;
            }
        }
        assertThat(charge).isNotNull();
        assertThat(charge.get("amount").asString()).isEqualTo("25.00");
        assertThat(charge.get("source").asString()).isEqualTo("TARIFF");
        assertThat(charge.get("billingMonth").asString()).isEqualTo(month);
        JsonNode part = charge.get("calculation").get("parts").get(0);
        assertThat(part.get("tariffVersionId").asString()).isEqualTo(membership.version().toString());
        assertThat(part.get("basis").asString()).isEqualTo("PER_AREA_SOTKA");
        assertThat(part.get("frequency").asString()).isEqualTo("YEARLY");
        assertThat(part.get("quantity").asString()).isEqualTo("6.00");
        assertThat(part.get("unit").asString()).isEqualTo("SOTKA");
        assertThat(part.get("rate").asString()).isEqualTo("50.0000");
        assertThat(part.get("days").asInt()).isEqualTo(ym.lengthOfMonth());
        assertThat(part.get("from").asString()).isEqualTo(ym.atDay(1).toString());
        assertThat(part.get("to").asString()).isEqualTo(ym.atEndOfMonth().toString());
        assertThat(charge.get("calculation").get("amount").asString()).isEqualTo("25.00");
        assertThat(account.get("total").asString()).isEqualTo("42.50");

        // the month is run and its totals show the charges
        JsonNode page = body(send(chairman, get(MONTH_PAGE.formatted(month)), null).andExpect(status().isOk()));
        assertThat(page.get("runDone").asBoolean()).isTrue();
        assertThat(page.get("checklist").get("runDone").asBoolean()).isTrue();
        assertThat(page.get("checklist").get("notChargedCount").asInt()).isZero();
        assertThat(total(page, "MEMBERSHIP")).isEqualTo("50.50");
    }

    @Test
    void aBookingWithoutADigestIsAccepted() throws Exception {
        assertThat(book().get("created").asInt()).isEqualTo(4);
    }

    @Test
    void aSecondRunCreatesNothingNew() throws Exception {
        book();

        JsonNode again = preview();
        JsonNode booked = book();

        assertThat(again.get("plots").asInt()).isZero();
        assertThat(again.get("totals")).isEmpty();
        assertThat(again.get("total").asString()).isEqualTo("0.00");
        assertThat(booked.get("created").asInt()).isZero();
        assertThat(chargeCount()).isEqualTo(4);
    }

    @Test
    void aPlotAddedAfterTheRunGetsOnlyItsOwnChargesOnTheRerun() throws Exception {
        book();
        UUID plot11 = plot("11", "1000.00");

        JsonNode rerun = book();

        assertThat(rerun.get("created").asInt()).isEqualTo(2);
        assertThat(rerun.get("plots").asInt()).isEqualTo(1);
        assertThat(total(rerun, "MEMBERSHIP")).isEqualTo("41.67"); // 50 x 10 / 12 = 41.666...
        assertThat(charges(plot11)).hasSize(2);
        assertThat(charges(plot7)).hasSize(2);
        assertThat(chargeCount()).isEqualTo(6);
    }

    @Test
    void aReversedChargeIsNotCreatedAgainByARerun() throws Exception {
        book();
        UUID fee = (UUID) charges(plot7).stream().filter(c -> membership.id().equals(c.get("tariff_id"))).findFirst()
                .orElseThrow().get("id");
        send(chairman, post(REVERSE.formatted(fee)), Map.of("reason", "Помилкове нарахування"))
                .andExpect(status().isCreated());

        JsonNode rerun = book();

        assertThat(rerun.get("created").asInt()).isZero();
        assertThat(charges(plot7)).hasSize(3); // two charges and the reversal
    }

    @Test
    void aTariffAmountOfZeroCreatesNoCharge() throws Exception {
        tariff("WATER", "Вода", "PER_PLOT", "MONTHLY", "0.0000", "2026-01-01");
        owner().update("update charge_type set active = true where id = ?", typeId("WATER"));

        JsonNode booked = book();

        assertThat(booked.get("created").asInt()).isEqualTo(4);
        assertThat(total(booked, "WATER")).isNull();
        assertThat(booked.get("notCharged")).isEmpty();
    }

    @Test
    void aOneTimeFeeIsChargedInItsDueMonthAndCountsFromItsDueDate() throws Exception {
        LocalDate due = YearMonth.parse(month).atDay(10);
        UUID repair = owner().queryForObject("insert into charge_type (tenant_id, name, name_customized, position) "
                + "values (?, 'Ремонт дороги', true, 6) returning id", UUID.class, tenant);
        UUID tariffId = UUID.randomUUID();
        owner().update("insert into tariff (id, tenant_id, charge_type_id, name) values (?, ?, ?, 'Дорога')",
                tariffId, tenant, repair);
        version(tariffId, "FIXED_ONE_TIME", "ONE_OFF", "1200.0000", "2026-01-01", due.toString());

        book();

        Map<String, Object> fee = charges(plot7).stream().filter(c -> tariffId.equals(c.get("tariff_id"))).findFirst()
                .orElseThrow();
        assertThat(fee.get("amount")).isEqualTo("1200.00");
        assertThat(date(fee.get("accrued_on"))).isEqualTo(due);
        assertThat(date(fee.get("service_from"))).isEqualTo(due);
        assertThat(date(fee.get("service_to"))).isEqualTo(due);
    }

    @Test
    void excludedArchivedAndCommonPlotsInactiveTypesAndTariffsByReadingsAreNotChargedNorQueued() throws Exception {
        exclude(membership, plot9);
        plot("archived", "600.00", "PLOT", true);
        plot("road", "0.00", "COMMON", false);
        tariff("WATER", "Вода", "PER_PLOT", "YEARLY", "300.0000", "2026-01-01"); // the water type is inactive
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2026-01-01");

        JsonNode booked = book();

        // plot 7 membership and land tax, plot 9 land tax only
        assertThat(booked.get("created").asInt()).isEqualTo(3);
        assertThat(booked.get("plots").asInt()).isEqualTo(2);
        assertThat(total(booked, "MEMBERSHIP")).isEqualTo("25.00");
        assertThat(total(booked, "WATER")).isNull();
        assertThat(total(booked, "ELECTRICITY")).isNull();
        assertThat(booked.get("notCharged")).isEmpty();
        assertThat(owner().queryForObject("select count(*) from not_charged_item where tenant_id = ?",
                Integer.class, tenant)).isZero();
    }

    @Test
    void aBookingCreatesTheMonthRowAndMarksItRun() throws Exception {
        book();

        assertThat(owner().queryForObject("select run_at is not null from billing_month where tenant_id = ? "
                + "and month = ?::date and status = 'OPEN'", Boolean.class, tenant, month + "-01")).isTrue();
    }

    // ---- conflicts

    @Test
    void twoMembershipTariffsOnPlot12ChargeNeitherAndQueueThePlotNamingBoth() throws Exception {
        UUID plot12 = plot("12", "600.00");
        Tariff extra = tariff("MEMBERSHIP", "Додатковий внесок", "PER_PLOT", "MONTHLY", "100.0000", "2026-01-01");
        select(extra, plot12);

        JsonNode preview = preview();

        assertThat(preview.get("notCharged")).singleElement().satisfies(item -> {
            assertThat(item.has("id")).isFalse();
            assertThat(item.get("kind").asString()).isEqualTo("TARIFF_MONTH");
            assertThat(item.get("reason").asString()).isEqualTo("CONFLICTING_TARIFFS");
            assertThat(item.get("plotId").asString()).isEqualTo(plot12.toString());
            assertThat(item.get("plotNumber").asString()).isEqualTo("12");
            assertThat(item.get("chargeType").get("code").asString()).isEqualTo("MEMBERSHIP");
            assertThat(item.get("month").asString()).isEqualTo(month);
            List<String> names = new ArrayList<>();
            item.get("tariffs").forEach(t -> names.add(t.get("name").asString()));
            assertThat(names).containsExactly("Додатковий внесок", "Членський внесок");
        });
        // plot 12 still gets its land tax; plots 7 and 9 their membership
        assertThat(total(preview, "MEMBERSHIP")).isEqualTo("50.50");

        JsonNode booked = book();

        assertThat(charges(plot12)).extracting(c -> c.get("tariff_id")).containsExactly(landTax.id());
        UUID itemId = UUID.fromString(booked.get("notCharged").get(0).get("id").asString());
        JsonNode queue = body(send(chairman, get(NOT_CHARGED), null).andExpect(status().isOk()));
        assertThat(queue).singleElement().satisfies(item -> {
            assertThat(item.get("id").asString()).isEqualTo(itemId.toString());
            assertThat(item.get("tariffs")).hasSize(2);
            assertThat(item.get("from").asString()).isEqualTo(YearMonth.parse(month).atDay(1).toString());
            assertThat(item.has("createdAt")).isTrue();
        });
        JsonNode page = body(send(chairman, get(MONTH_PAGE.formatted(month)), null).andExpect(status().isOk()));
        assertThat(page.get("checklist").get("notChargedCount").asInt()).isEqualTo(1);

        // a rerun keeps the one item
        JsonNode rerun = book();
        assertThat(rerun.get("notCharged").get(0).get("id").asString()).isEqualTo(itemId.toString());
        assertThat(owner().queryForObject("select count(*) from not_charged_item where tenant_id = ?",
                Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void aConflictBetweenTariffsThatChargeNothingThisMonthIsNotQueued() throws Exception {
        UUID repair = owner().queryForObject("insert into charge_type (tenant_id, name, name_customized, position) "
                + "values (?, 'Цільові внески', true, 6) returning id", UUID.class, tenant);
        for (String name : List.of("Дорога", "Паркан")) {
            UUID tariffId = UUID.randomUUID();
            owner().update("insert into tariff (id, tenant_id, charge_type_id, name) values (?, ?, ?, ?)",
                    tariffId, tenant, repair, name);
            version(tariffId, "FIXED_ONE_TIME", "ONE_OFF", "1200.0000", "2026-01-01",
                    YearMonth.parse(month).plusMonths(2).atDay(1).toString());
        }

        assertThat(preview().get("notCharged")).isEmpty();
    }

    // ---- refusals

    @Test
    void aStalePreviewIsRefusedAndNothingIsWritten() throws Exception {
        String digest = preview().get("digest").asString();
        plot("11", "1000.00");

        send(chairman, post(RUN.formatted(month, false)), digest(digest)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));

        assertThat(chargeCount()).isZero();
        assertThat(owner().queryForObject("select count(*) from billing_month where tenant_id = ? "
                + "and run_at is not null", Integer.class, tenant)).isZero();
        String fresh = preview().get("digest").asString();
        assertThat(fresh).isNotEqualTo(digest);
        assertThat(run(chairman, month, false, digest(fresh)).get("created").asInt()).isEqualTo(6);
    }

    @Test
    void aClosedMonthIsRefused() throws Exception {
        closeMonth(ym(1));

        for (boolean preview : List.of(true, false)) {
            send(chairman, post(RUN.formatted(ym(1), preview)), null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
            // a month before the last closed one counts as closed
            send(chairman, post(RUN.formatted(ym(2), preview)), null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        }
        assertThat(chargeCount()).isZero();
    }

    @Test
    void aFutureMonthIsRefused() throws Exception {
        for (boolean preview : List.of(true, false)) {
            send(chairman, post(RUN.formatted(current().plusMonths(1), preview)), null)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_IN_FUTURE"));
        }
        assertThat(chargeCount()).isZero();
    }

    @Test
    void anEarlierOpenMonthCanBeRun() throws Exception {
        JsonNode booked = run(chairman, ym(1), false, null);

        assertThat(booked.get("created").asInt()).isEqualTo(4);
        assertThat(date(charges(plot7).getFirst().get("billing_month"))).isEqualTo(YearMonth.parse(ym(1)).atDay(1));
    }

    @Test
    void aMalformedMonthOrAMissingPreviewIsABadRequest() throws Exception {
        send(chairman, post("/api/v1/billing-months/2026-13/run?preview=true"), null)
                .andExpect(status().isBadRequest());
        send(chairman, post("/api/v1/billing-months/" + month + "/run"), null).andExpect(status().isBadRequest());
    }

    // ---- who may run

    @Test
    void onlyChargesRunMayPreviewOrBook() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        for (String bearer : List.of(treasurer, member, operator)) {
            for (boolean preview : List.of(true, false)) {
                send(bearer, post(RUN.formatted(month, preview)), null).andExpect(status().isForbidden());
            }
        }
        send(null, post(RUN.formatted(month, true)), null).andExpect(status().isUnauthorized());
        assertThat(chargeCount()).isZero();
    }

    @Test
    void theQueueIsForChargesViewOnly() throws Exception {
        send(treasurer, get(NOT_CHARGED), null).andExpect(status().isOk());
        send(member, get(NOT_CHARGED), null).andExpect(status().isForbidden());
    }

    // ---- audit and the plot status

    @Test
    void aBookingIsAuditedWithTheCountAndTheTotal() throws Exception {
        preview();
        book();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = 'BILLING_MONTH_RUN'", Integer.class,
                tenant)).isEqualTo(1));
        Map<String, Object> entry = owner().queryForMap("select entity_type, entity_label, "
                + "cast(changes as text) as changes from audit_entry where tenant_id = ? "
                + "and action = 'BILLING_MONTH_RUN'", tenant);
        assertThat(entry.get("entity_type")).isEqualTo("BILLING_MONTH");
        assertThat(entry.get("entity_label")).isEqualTo(month);
        JsonNode changes = json.readTree((String) entry.get("changes"));
        assertThat(changes.get(0).get("field").asString()).isEqualTo("count");
        assertThat(changes.get(0).get("new").asString()).isEqualTo("4");
        assertThat(changes.get(1).get("field").asString()).isEqualTo("total");
        assertThat(changes.get(1).get("new").asString()).isEqualTo("85.85");
    }

    @Test
    void theChargedPlotsShowTheirDebt() throws Exception {
        book();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner().queryForObject(
                "select debt::text from plot_status where plot_id = ?", String.class, plot7)).isEqualTo("42.50"));
    }
}
