package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Correcting tariff versions with a recalculation (feature 012 §2.4, slice 6): {@code POST /recalculations} with the
 * causes {@code TARIFF_VERSION_ADD}, {@code _UPDATE} and {@code _DELETE} applies the change and plans in one
 * transaction; a preview rolls both back, a booking saves the change and the recalculation together (or neither); a
 * plan without lines saves the change alone, without a reason. The tariff endpoints keep refusing a used version (409
 * {@code TARIFF_VERSION_IN_USE}) except for a correction of its note. Months are relative to the current month in
 * Europe/Kyiv: the previous month ("September") is closed, the current one ("October") open. Membership per sotka per
 * year on a 600 m2 plot: 60.00 is 30.00 a month, 50.00 is 25.00.
 */
class TariffRecalculationTests extends FreshCooperativeSupport {

    private static final String RECALCULATIONS = "/api/v1/recalculations";
    private static final String TARIFF = "/api/v1/tariffs/%s";
    private static final String VERSION = "/api/v1/tariffs/%s/versions/%s";
    private static final String REASON = "Виправлено ставку членського внеску";

    private String september;
    private String october;
    private UUID plot7;

    @BeforeEach
    void plot7() {
        september = ym(1);
        october = ym(0);
        plot7 = plot("7", "600.00");
    }

    /** The month run of September and October booked, then September closed. */
    private void bookSeptemberAndOctoberThenCloseSeptember() throws Exception {
        send(chairman, post(RUN.formatted(september, false)), null).andExpect(status().isOk());
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        closeMonth(september);
    }

    private static Map<String, Object> terms(String validFrom, String rate, String note) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("validFrom", validFrom);
        terms.put("basis", "PER_AREA_SOTKA");
        terms.put("frequency", "YEARLY");
        terms.put("rate", rate);
        if (note != null) {
            terms.put("note", note);
        }
        return terms;
    }

    private static Map<String, Object> cause(String type, UUID tariff, UUID version, Map<String, Object> terms,
            String reason, String digest) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("tariffId", tariff);
        if (version != null) {
            change.put("versionId", version);
        }
        if (terms != null) {
            change.put("version", terms);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cause", Map.of("type", type, "tariffVersion", change));
        if (reason != null) {
            body.put("reason", reason);
        }
        if (digest != null) {
            body.put("digest", digest);
        }
        return body;
    }

    private JsonNode preview(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "true"), body).andExpect(status().isOk()));
    }

    private JsonNode book(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "false"), body)
                .andExpect(status().isOk()));
    }

    private int recalculationCount() {
        return owner().queryForObject("select count(*) from recalculation where tenant_id = ?", Integer.class,
                tenant);
    }

    private String storedRate(UUID version) {
        return owner().queryForObject("select rate::text from tariff_version where id = ?", String.class, version);
    }

    private String membershipDebt() throws Exception {
        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(plot7)), null).andExpect(status().isOk()));
        for (JsonNode debt : account.get("debts")) {
            if ("MEMBERSHIP".equals(debt.get("chargeTypeCode").asString())) {
                return debt.get("debt").asString();
            }
        }
        return "0.00";
    }

    private static List<String> actions(JsonNode result) {
        return result.get("lines").valueStream().map(line -> line.get("action").asString()).toList();
    }

    private List<Map<String, Object>> auditEntries(String action, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = ?", Integer.class, tenant, action))
                .isEqualTo(count));
        return owner().queryForList("select entity_type, entity_id, cast(changes as text) as changes from audit_entry "
                + "where tenant_id = ? and action = ?", tenant, action);
    }

    private Map<String, String[]> changes(Map<String, Object> entry) throws Exception {
        Map<String, String[]> values = new LinkedHashMap<>();
        json.readTree((String) entry.get("changes")).forEach(change -> values.put(change.get("field").asString(),
                new String[] {value(change, "old"), value(change, "new")}));
        return values;
    }

    private static String value(JsonNode change, String key) {
        return change.has(key) && !change.get(key).isNull() ? change.get(key).asString() : null;
    }

    /** A token of a fresh member of the cooperative whose only role is a custom role with the permissions. */
    private String customRole(String... permissions) {
        UUID member = UUID.randomUUID();
        String email = "custom-" + member + "@run.test";
        owner().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, 'Test', 'Custom')",
                member, tenant);
        insertContacts(tenant, member, email);
        UUID role = UUID.randomUUID();
        owner().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, "Custom " + role);
        for (String permission : permissions) {
            owner().update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)", tenant,
                    role, permission);
        }
        owner().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant, member,
                role);
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    // ---- 60 -> 50, end to end

    @Test
    void correctingTheRateFrom60To50AdjustsTheClosedMonthAndReversesAndChargesTheOpenOne() throws Exception {
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "60.0000",
                september + "-01");
        bookSeptemberAndOctoberThenCloseSeptember();
        assertThat(membershipDebt()).isEqualTo("60.00");
        int before = chargeCount();
        // without the recalculation step the used version is still refused
        send(chairman, put(VERSION.formatted(membership.id(), membership.version())),
                terms(september + "-01", "50", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_VERSION_IN_USE"));

        JsonNode preview = preview(cause("TARIFF_VERSION_UPDATE", membership.id(), membership.version(),
                terms(september + "-01", "50", null), null, null));

        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.get("booked").asBoolean()).isFalse();
        assertThat(preview.get("changeApplied").asBoolean()).isFalse();
        assertThat(preview.get("cause").asString()).isEqualTo("TARIFF_VERSION");
        assertThat(preview.get("causeType").asString()).isEqualTo("TARIFF_VERSION_UPDATE");
        assertThat(preview.get("scope").get("tariffId").asString()).isEqualTo(membership.id().toString());
        assertThat(preview.get("scope").get("tariffVersionId").asString())
                .isEqualTo(membership.version().toString());
        assertThat(preview.get("scope").get("months").valueStream().map(JsonNode::asString).toList())
                .containsExactly(september, october);
        assertThat(actions(preview)).containsExactly("ADJUST", "REVERSE_AND_RECHARGE");
        JsonNode adjust = preview.get("lines").get(0);
        assertThat(adjust.get("serviceFrom").asString()).isEqualTo(september + "-01");
        assertThat(adjust.get("effective").asString()).isEqualTo("30.00");
        assertThat(adjust.get("expected").asString()).isEqualTo("25.00");
        assertThat(adjust.get("difference").asString()).isEqualTo("-5.00");
        assertThat(adjust.get("bookedIn").asString()).isEqualTo(october);
        assertThat(adjust.get("reasons").valueStream().map(JsonNode::asString).toList())
                .containsExactly("TARIFF_VERSION");
        JsonNode recharge = preview.get("lines").get(1);
        assertThat(recharge.get("booked").asString()).isEqualTo("30.00");
        assertThat(recharge.get("expected").asString()).isEqualTo("25.00");
        assertThat(recharge.get("bookedIn").asString()).isEqualTo(october);
        assertThat(preview.get("total").asString()).isEqualTo("-10.00");
        // a preview changes neither the version nor the ledger
        assertThat(storedRate(membership.version())).isEqualTo("60.0000");
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();

        JsonNode booked = book(cause("TARIFF_VERSION_UPDATE", membership.id(), membership.version(),
                terms(september + "-01", "50", null), REASON, preview.get("digest").asString()));

        assertThat(booked.get("booked").asBoolean()).isTrue();
        assertThat(booked.get("changeApplied").asBoolean()).isTrue();
        assertThat(booked.get("digest").asString()).isEqualTo(preview.get("digest").asString());
        UUID recalculation = UUID.fromString(booked.get("id").asString());
        // September: an adjustment of -5.00 in October; October: reversal -30.00 and a new charge of 25.00
        assertThat(chargeCount()).isEqualTo(before + 3);
        UUID adjustment = UUID.fromString(booked.get("lines").get(0).get("adjustmentChargeId").asString());
        Map<String, Object> adjustmentRow = owner().queryForMap("select c.kind, c.amount::text as amount, c.reason, "
                + "c.service_from, m.month as billing_month from charge c join billing_month m "
                + "on m.id = c.billing_month_id where c.id = ?", adjustment);
        assertThat(adjustmentRow.get("kind")).isEqualTo("ADJUSTMENT");
        assertThat(adjustmentRow.get("amount")).isEqualTo("-5.00");
        assertThat(adjustmentRow.get("reason")).isEqualTo(REASON);
        assertThat(date(adjustmentRow.get("service_from"))).isEqualTo(YearMonth.parse(september).atDay(1));
        assertThat(date(adjustmentRow.get("billing_month"))).isEqualTo(YearMonth.parse(october).atDay(1));
        JsonNode bookedRecharge = booked.get("lines").get(1);
        assertThat(owner().queryForObject("select amount::text from charge where id = ?", String.class,
                UUID.fromString(bookedRecharge.get("reversalChargeId").asString()))).isEqualTo("-30.00");
        assertThat(owner().queryForObject("select amount::text from charge where id = ?", String.class,
                UUID.fromString(bookedRecharge.get("newChargeId").asString()))).isEqualTo("25.00");
        assertThat(membershipDebt()).isEqualTo("50.00");

        // the version shows 50.00 now
        assertThat(storedRate(membership.version())).isEqualTo("50.0000");
        JsonNode card = body(send(chairman, get(TARIFF.formatted(membership.id())), null).andExpect(status().isOk()));
        assertThat(card.get("versions")).singleElement()
                .satisfies(version -> assertThat(version.get("rate").asString()).isEqualTo("50.0000"));

        // the record: cause TARIFF_VERSION, the version as its cause
        Map<String, Object> record = owner().queryForMap("select cause, cause_ref_id, reason from recalculation "
                + "where id = ?", recalculation);
        assertThat(record.get("cause")).isEqualTo("TARIFF_VERSION");
        assertThat(record.get("cause_ref_id")).isEqualTo(membership.version());
        assertThat(record.get("reason")).isEqualTo(REASON);
        JsonNode stored = body(send(chairman, get(RECALCULATIONS + "/" + recalculation), null)
                .andExpect(status().isOk()));
        assertThat(stored.get("causeType").asString()).isEqualTo("TARIFF_VERSION_UPDATE");
        assertThat(stored.get("changeApplied").asBoolean()).isTrue();
        assertThat(stored.get("scope").get("tariffVersionId").asString()).isEqualTo(membership.version().toString());

        // the audit log: the version change (old and new values, the recalculation) and RECALCULATION_BOOKED
        Map<String, Object> updated = auditEntries("TARIFF_VERSION_UPDATED", 1).getFirst();
        assertThat(updated.get("entity_id")).isEqualTo(membership.id());
        Map<String, String[]> versionChanges = changes(updated);
        assertThat(versionChanges.keySet()).containsExactly("rate", "recalculationId");
        assertThat(versionChanges.get("rate")).containsExactly("60.0000", "50.0000");
        assertThat(versionChanges.get("recalculationId")).containsExactly(null, recalculation.toString());
        Map<String, Object> bookedEntry = auditEntries("RECALCULATION_BOOKED", 1).getFirst();
        assertThat(bookedEntry.get("entity_id")).isEqualTo(recalculation);
        Map<String, String[]> recalculationChanges = changes(bookedEntry);
        assertThat(recalculationChanges.get("cause")[1]).isEqualTo("TARIFF_VERSION_UPDATE");
        assertThat(recalculationChanges.get("from")[1]).isEqualTo(september);
        assertThat(recalculationChanges.get("to")[1]).isEqualTo(october);
        assertThat(recalculationChanges.get("reason")[1]).isEqualTo(REASON);
        assertThat(recalculationChanges.get("total")[1]).isEqualTo("-10.00");

        // the same correction again changes nothing
        JsonNode again = preview(cause("TARIFF_VERSION_UPDATE", membership.id(), membership.version(),
                terms(september + "-01", "50", null), null, null));
        assertThat(again.get("lines")).isEmpty();
    }

    // ---- back-dated new versions

    @Test
    void aNewVersionFromTheFirstOfTheOpenMonthReversesAndChargesItAndLeavesTheClosedMonth() throws Exception {
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000",
                "2020-01-01");
        bookSeptemberAndOctoberThenCloseSeptember();

        JsonNode preview = preview(cause("TARIFF_VERSION_ADD", membership.id(), null,
                terms(october + "-01", "60", null), null, null));

        assertThat(actions(preview)).containsExactly("REVERSE_AND_RECHARGE");
        JsonNode line = preview.get("lines").get(0);
        assertThat(line.get("serviceFrom").asString()).isEqualTo(october + "-01");
        assertThat(line.get("booked").asString()).isEqualTo("25.00");
        assertThat(line.get("expected").asString()).isEqualTo("30.00");
        assertThat(preview.get("scope").get("months").valueStream().map(JsonNode::asString).toList())
                .containsExactly(october);
        // the new version's id is generated anew by the booking: the preview does not name it
        assertThat(preview.get("scope").has("tariffVersionId")).isFalse();
        assertThat(owner().queryForObject("select count(*) from tariff_version where tariff_id = ?", Integer.class,
                membership.id())).isEqualTo(1);

        JsonNode booked = book(cause("TARIFF_VERSION_ADD", membership.id(), null, terms(october + "-01", "60", null),
                REASON, preview.get("digest").asString()));

        // the digest does not depend on the generated ids: the booking matches the preview
        assertThat(booked.get("digest").asString()).isEqualTo(preview.get("digest").asString());
        UUID added = owner().queryForObject("select id from tariff_version where tariff_id = ? and valid_from = ?::date",
                UUID.class, membership.id(), october + "-01");
        assertThat(booked.get("scope").get("tariffVersionId").asString()).isEqualTo(added.toString());
        assertThat(owner().queryForObject("select cause_ref_id from recalculation where id = ?", UUID.class,
                UUID.fromString(booked.get("id").asString()))).isEqualTo(added);
        assertThat(booked.get("lines").get(0).get("expected").asString()).isEqualTo("30.00");
        assertThat(membershipDebt()).isEqualTo("55.00");
        assertThat(owner().queryForObject("select count(*) from charge where tenant_id = ? and kind = 'ADJUSTMENT'",
                Integer.class, tenant)).isZero();
        Map<String, String[]> added01 = changes(auditEntries("TARIFF_VERSION_ADDED", 1).getFirst());
        assertThat(added01.get("validFrom")[1]).isEqualTo(october + "-01");
        assertThat(added01.get("rate")[1]).isEqualTo("60.0000");
        assertThat(added01.get("recalculationId")[1]).isEqualTo(booked.get("id").asString());
    }

    @Test
    void aNewVersionFromThe16thOfTheClosedMonthAdjustsItAndRechargesTheOpenOne() throws Exception {
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000",
                "2020-01-01");
        bookSeptemberAndOctoberThenCloseSeptember();
        // 15 days at 50.00 and the rest at 60.00 per sotka per year, 6 sotkas, over the days of the month (one
        // rounding): 27.50 for a month of 30 days
        int days = YearMonth.parse(september).lengthOfMonth();
        BigDecimal expected = BigDecimal.valueOf(4500 + 360L * (days - 15))
                .divide(BigDecimal.valueOf(12L * days), 2, RoundingMode.HALF_UP);
        String difference = expected.subtract(new BigDecimal("25.00")).toPlainString();

        JsonNode preview = preview(cause("TARIFF_VERSION_ADD", membership.id(), null,
                terms(september + "-16", "60", null), null, null));

        assertThat(actions(preview)).containsExactly("ADJUST", "REVERSE_AND_RECHARGE");
        JsonNode adjust = preview.get("lines").get(0);
        assertThat(adjust.get("expected").asString()).isEqualTo(expected.toPlainString());
        assertThat(adjust.get("difference").asString()).isEqualTo(difference);
        assertThat(adjust.get("bookedIn").asString()).isEqualTo(october);
        JsonNode recharge = preview.get("lines").get(1);
        assertThat(recharge.get("booked").asString()).isEqualTo("25.00");
        assertThat(recharge.get("expected").asString()).isEqualTo("30.00");

        JsonNode booked = book(cause("TARIFF_VERSION_ADD", membership.id(), null,
                terms(september + "-16", "60", null), REASON, preview.get("digest").asString()));

        assertThat(booked.get("booked").asBoolean()).isTrue();
        assertThat(membershipDebt()).isEqualTo(expected.add(new BigDecimal("30.00")).toPlainString());
        if (days == 30) {
            assertThat(expected.toPlainString()).isEqualTo("27.50");
            assertThat(difference).isEqualTo("2.50");
        }
    }

    // ---- deleting a used version

    @Test
    void deletingTheOnlyVersionOfTheOpenMonthReversesItsChargeAndDeletesTheVersionSoftly() throws Exception {
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000",
                october + "-01");
        UUID next = version(membership.id(), "PER_AREA_SOTKA", "YEARLY", "50.0000", ym(-1) + "-01", null);
        bookSeptemberAndOctoberThenCloseSeptember();
        assertThat(membershipDebt()).isEqualTo("25.00");
        // the 011 refusal of a plain delete still holds
        send(chairman, delete(VERSION.formatted(membership.id(), membership.version())), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_VERSION_IN_USE"));

        JsonNode preview = preview(cause("TARIFF_VERSION_DELETE", membership.id(), membership.version(), null, null,
                null));

        assertThat(actions(preview)).containsExactly("REVERSE");
        assertThat(preview.get("lines").get(0).get("serviceFrom").asString()).isEqualTo(october + "-01");
        assertThat(preview.get("lines").get(0).get("difference").asString()).isEqualTo("-25.00");
        assertThat(preview.get("scope").get("months").valueStream().map(JsonNode::asString).toList())
                .containsExactly(october);

        JsonNode booked = book(cause("TARIFF_VERSION_DELETE", membership.id(), membership.version(), null, REASON,
                preview.get("digest").asString()));

        assertThat(booked.get("booked").asBoolean()).isTrue();
        assertThat(membershipDebt()).isEqualTo("0.00");
        // deleted softly: the row stays for the charge parts, the card no longer shows it
        assertThat(owner().queryForObject("select deleted_at is not null from tariff_version where id = ?",
                Boolean.class, membership.version())).isTrue();
        JsonNode card = body(send(chairman, get(TARIFF.formatted(membership.id())), null).andExpect(status().isOk()));
        assertThat(card.get("versions").valueStream().map(v -> v.get("id").asString()).toList())
                .containsExactly(next.toString());
        Map<String, String[]> deleted = changes(auditEntries("TARIFF_VERSION_DELETED", 1).getFirst());
        assertThat(deleted.get("rate")).containsExactly("50.0000", null);
        assertThat(deleted.get("recalculationId")[1]).isEqualTo(booked.get("id").asString());
    }

    // ---- changes that change no amount

    @Test
    void aNoteOnlyCorrectionOfAUsedVersionIsSavedThroughTheTariffEndpointWithoutARecalculation() throws Exception {
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000",
                "2020-01-01");
        bookSeptemberAndOctoberThenCloseSeptember();
        int before = chargeCount();

        send(chairman, put(VERSION.formatted(membership.id(), membership.version())),
                terms("2020-01-01", "50", "Рішення загальних зборів"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Рішення загальних зборів"));

        assertThat(owner().queryForObject("select note from tariff_version where id = ?", String.class,
                membership.version())).isEqualTo("Рішення загальних зборів");
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();
    }

    @Test
    void aCorrectionThatChangesNoAmountIsSavedWithoutARecalculationAndWithoutAReason() throws Exception {
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000",
                "2020-01-01");
        bookSeptemberAndOctoberThenCloseSeptember();
        int before = chargeCount();
        Map<String, Object> noteOnly = terms("2020-01-01", "50.0000", "Рішення загальних зборів");

        JsonNode preview = preview(cause("TARIFF_VERSION_UPDATE", membership.id(), membership.version(), noteOnly,
                null, null));
        assertThat(preview.get("lines")).isEmpty();
        assertThat(preview.get("total").asString()).isEqualTo("0.00");

        JsonNode saved = book(cause("TARIFF_VERSION_UPDATE", membership.id(), membership.version(), noteOnly, null,
                preview.get("digest").asString()));

        assertThat(saved.get("preview").asBoolean()).isFalse();
        assertThat(saved.get("booked").asBoolean()).isFalse();
        assertThat(saved.get("changeApplied").asBoolean()).isTrue();
        assertThat(saved.has("id")).isFalse();
        assertThat(saved.get("lines")).isEmpty();
        assertThat(owner().queryForObject("select note from tariff_version where id = ?", String.class,
                membership.version())).isEqualTo("Рішення загальних зборів");
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();
        Map<String, String[]> updated = changes(auditEntries("TARIFF_VERSION_UPDATED", 1).getFirst());
        assertThat(updated.keySet()).containsExactly("note");
        assertThat(owner().queryForObject("select count(*) from audit_entry where tenant_id = ? "
                + "and action = 'RECALCULATION_BOOKED'", Integer.class, tenant)).isZero();
    }

    // ---- refusals

    @Test
    void aStalePreviewAndAMissingReasonAreRefusedAndTheVersionStaysAsItWas() throws Exception {
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "60.0000",
                september + "-01");
        bookSeptemberAndOctoberThenCloseSeptember();
        Map<String, Object> to50 = terms(september + "-01", "50", null);
        String digest = preview(cause("TARIFF_VERSION_UPDATE", membership.id(), membership.version(), to50, null,
                null)).get("digest").asString();
        int before = chargeCount();

        send(chairman, post(RECALCULATIONS).param("preview", "false"),
                cause("TARIFF_VERSION_UPDATE", membership.id(), membership.version(), to50, null, digest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        owner().update("update plot set area_m2 = 700 where id = ?", plot7);
        send(chairman, post(RECALCULATIONS).param("preview", "false"),
                cause("TARIFF_VERSION_UPDATE", membership.id(), membership.version(), to50, REASON, digest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));

        assertThat(storedRate(membership.version())).isEqualTo("60.0000");
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();
    }

    @Test
    void theFieldsOfTheChangeAreCheckedLikeOnTheTariffEndpoint() throws Exception {
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000",
                "2020-01-01");
        Map<String, Object> oneOffWithoutDueDate = terms(october + "-01", "50", null);
        oneOffWithoutDueDate.put("frequency", "ONE_OFF");

        send(chairman, post(RECALCULATIONS).param("preview", "true"),
                cause("TARIFF_VERSION_ADD", membership.id(), null, oneOffWithoutDueDate, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cause.tariffVersion.version.frequency"))
                .andExpect(jsonPath("$.errors[0].code").value("FREQUENCY_BASIS_MISMATCH"));
        send(chairman, post(RECALCULATIONS).param("preview", "true"),
                cause("TARIFF_VERSION_UPDATE", membership.id(), null, null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cause.tariffVersion.versionId"))
                .andExpect(jsonPath("$.errors[1].field").value("cause.tariffVersion.version"));
        send(chairman, post(RECALCULATIONS).param("preview", "true"),
                cause("TARIFF_VERSION_ADD", membership.id(), null, terms("2020-01-01", "60", null), null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_VERSION_DATE_TAKEN"));
        // a version of another tariff is not found
        Tariff other = tariff("LAND_TAX", "Земельний податок", "PER_AREA_SOTKA", "YEARLY", "35.0000", "2020-01-01");
        send(chairman, post(RECALCULATIONS).param("preview", "true"),
                cause("TARIFF_VERSION_DELETE", membership.id(), other.version(), null, null, null))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAHolderOfBothPermissionsMayCorrectAUsedVersion() throws Exception {
        Tariff membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "60.0000",
                september + "-01");
        bookSeptemberAndOctoberThenCloseSeptember();
        String tariffsOnly = customRole("TARIFFS_VIEW", "TARIFFS_MANAGE");
        String recalculationsOnly = customRole("CHARGES_VIEW", "RECALCULATION_RUN");
        Map<String, Object> to50 = terms(september + "-01", "50", null);

        for (String bearer : new String[] {treasurer, member, tariffsOnly, recalculationsOnly}) {
            for (String preview : new String[] {"true", "false"}) {
                send(bearer, post(RECALCULATIONS).param("preview", preview),
                        cause("TARIFF_VERSION_UPDATE", membership.id(), membership.version(), to50, REASON, null))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            }
        }
        // the tariff manager without the recalculation permission gets the 011 refusal on the tariff endpoint
        send(tariffsOnly, put(VERSION.formatted(membership.id(), membership.version())), to50)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARIFF_VERSION_IN_USE"));

        assertThat(storedRate(membership.version())).isEqualTo("60.0000");
        assertThat(recalculationCount()).isZero();
    }
}
