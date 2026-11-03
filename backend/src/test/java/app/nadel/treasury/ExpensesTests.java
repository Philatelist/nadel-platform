package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Expenses (feature 018 §2.2, §2.3, §2.8, §2.14; technical considerations 2.5, 2.6): an expense with a receipt photo
 * takes money out of its account and keeps the photo without GPS or camera data; the file limits (5 MB, five files, the
 * type by content); the opening balance and the no-negative-cash rule; a closed month books the expense in the earliest
 * open month; «Сторнувати і внести заново» reverses once and re-enters with the old files; the counterparty and the
 * description are never stored, audited or published in the clear.
 */
class ExpensesTests extends TreasuryApiSupport {

    String chairman;
    String treasurer;
    UUID treasurerId;
    UUID cash;
    UUID bank;
    UUID materials;
    LocalDate opened;

    @BeforeEach
    void cashBook() throws Exception {
        chairman = person(tenantA, "CHAIRMAN");
        treasurer = person(tenantA, "TREASURER");
        treasurerId = lastPerson;
        cash = account(tenantA, "CASH");
        bank = account(tenantA, "BANK");
        materials = category(tenantA, "EXPENSE", "MATERIALS");
        opened = today().minusMonths(1).withDayOfMonth(1);
        setOpening(chairman, cash, "1000.00", opened).andExpect(status().isOk());
    }

    @Test
    void aCashExpenseWithAPhotoIsRecordedListedAndItsPhotoDownloadedWithoutGpsOrCameraData() throws Exception {
        byte[] photo = TestImages.cameraJpeg(false);
        Map<String, Object> input = with(with(with(with(expense("CASH", today(), "300.00", materials),
                "counterparty", "  ФОП Петренко  "), "description", "Цвяхи і фарба"), "documentNumber", "№ 15"),
                "documentDate", today().toString());

        JsonNode card = body(createExpense(treasurer, input, file("чек 06.10.jpg", "image/jpeg", photo))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.startsWith("/api/v1/expenses/"))));

        UUID id = UUID.fromString(card.get("id").asString());
        assertThat(card.get("account").asString()).isEqualTo("CASH");
        assertThat(card.get("date").asString()).isEqualTo(today().toString());
        assertThat(card.get("amount").asString()).isEqualTo("300.00");
        assertThat(card.get("bookingMonth").asString()).isEqualTo(YearMonth.from(today()).toString());
        assertThat(card.get("category").get("id").asString()).isEqualTo(materials.toString());
        assertThat(card.get("category").get("systemCode").asString()).isEqualTo("MATERIALS");
        assertThat(card.get("counterparty").asString()).isEqualTo("ФОП Петренко");
        assertThat(card.get("description").asString()).isEqualTo("Цвяхи і фарба");
        assertThat(card.get("documentNumber").asString()).isEqualTo("№ 15");
        assertThat(card.get("documentDate").asString()).isEqualTo(today().toString());
        assertThat(card.get("reversed").asBoolean()).isFalse();
        assertThat(card.has("reversal")).isFalse();
        assertThat(card.get("createdByName").asString()).startsWith("Скарбник Тест");
        assertThat(card.get("attachments")).singleElement().satisfies(attachment -> {
            assertThat(attachment.get("n").asInt()).isEqualTo(1);
            assertThat(attachment.get("fileName").asString()).isEqualTo("чек 06.10.jpg");
            assertThat(attachment.get("contentType").asString()).isEqualTo("image/jpeg");
            assertThat(attachment.get("sizeBytes").asLong()).isLessThan(photo.length);
        });
        // the cash goes down by the amount; the journal shows the expense line
        accounts(treasurer).andExpect(jsonPath("$[0].balance").value("700.00"));
        assertThat(storedMovements(tenantA)).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("account", "CASH").containsEntry("kind", "EXPENSE")
                    .containsEntry("amount", "-300.00").containsEntry("moved_on", today().toString())
                    .containsEntry("source_type", "EXPENSE").containsEntry("source_id", id)
                    .containsEntry("created_by", treasurerId);
        });
        // the list and the card
        JsonNode list = body(send(treasurer, get(EXPENSES), null).andExpect(status().isOk()));
        assertThat(list.get("totalItems").asLong()).isEqualTo(1);
        assertThat(list.get("items")).singleElement().satisfies(item -> {
            assertThat(item.get("id").asString()).isEqualTo(id.toString());
            assertThat(item.get("attachmentCount").asInt()).isEqualTo(1);
            assertThat(item.get("counterparty").asString()).isEqualTo("ФОП Петренко");
            assertThat(item.get("category").get("systemCode").asString()).isEqualTo("MATERIALS");
            assertThat(item.get("reversed").asBoolean()).isFalse();
        });
        expenseCard(treasurer, id).andExpect(status().isOk()).andExpect(jsonPath("$.attachments.length()").value(1));
        // the downloaded photo: no GPS position, no camera data, still the right way up
        byte[] downloaded = download(treasurer, id, 1).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"___ 06.10.jpg\"; filename*=UTF-8''%D1%87%D0%B5%D0%BA%2006.10.jpg"))
                .andReturn().getResponse().getContentAsByteArray();
        for (String marker : TestImages.MARKERS) {
            assertThat(TestImages.contains(downloaded, marker)).as(marker).isFalse();
        }
        assertThat(TestImages.contains(downloaded, "Exif")).as("the orientation is kept").isTrue();
        assertThat(ownerJdbc().queryForMap("select size_bytes, sha256 = sha256(content) as digest, content_type "
                + "from expense_attachment where expense_id = ?", id)).containsEntry("size_bytes", downloaded.length)
                .containsEntry("digest", true).containsEntry("content_type", "image/jpeg");
        download(treasurer, id, 2).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        download(treasurer, UUID.randomUUID(), 1).andExpect(status().isNotFound());
        expenseCard(treasurer, UUID.randomUUID()).andExpect(status().isNotFound());
        // audited with the amount, the account and the category
        Map<String, Object> entry = awaitAudit(id, "EXPENSE_RECORDED", 1).getFirst();
        assertThat(entry).containsEntry("entity_type", "EXPENSE").containsEntry("entity_label", today().toString())
                .containsEntry("actor_member_id", treasurerId);
        assertThat((String) entry.get("changes")).contains("\"amount\"", "300.00", "\"account\"", "CASH",
                "\"category\"", materials.toString(), "\"date\"", "\"counterparty\"", "\"description\"", "\"enc\"");
    }

    @Test
    void aBankExpenseMayTakeTheBankBelowZeroAndAPdfIsStoredAsReceived() throws Exception {
        setOpening(chairman, bank, "100.00", opened).andExpect(status().isOk());
        byte[] pdf = TestImages.pdf();

        UUID id = recordExpense(treasurer, expense("BANK", today(), "150.00", category(tenantA, "EXPENSE",
                "BANK_FEES")), file("statement.pdf", "application/pdf", pdf));

        accounts(treasurer).andExpect(jsonPath("$[1].balance").value("-50.00"))
                .andExpect(jsonPath("$[1].negative").value(true));
        assertThat(download(treasurer, id, 1).andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(pdf);
        expenseCard(treasurer, id).andExpect(jsonPath("$.account").value("BANK"))
                .andExpect(jsonPath("$.counterparty").doesNotExist())
                .andExpect(jsonPath("$.attachments[0].contentType").value("application/pdf"));
    }

    @Test
    void theFileLimitsAreCheckedByContentAndSizeAndNothingIsStored() throws Exception {
        Map<String, Object> input = expense("CASH", today(), "10.00", materials);
        byte[] sixMegabytes = Arrays.copyOf(TestImages.jpeg(false), 6 * 1024 * 1024);

        createExpense(treasurer, input, file("big.jpg", "image/jpeg", sixMegabytes))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.errors[0].field").value("files[0]"))
                .andExpect(jsonPath("$.errors[0].code").value("FILE_TOO_LARGE"));
        MockMultipartFile pdf = file("a.pdf", "application/pdf", TestImages.pdf());
        createExpense(treasurer, input, pdf, pdf, pdf, pdf, pdf, pdf)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY"))
                .andExpect(jsonPath("$.errors[0].field").value("files"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_MANY"));
        createExpense(treasurer, input, pdf, file("receipt.jpg", "image/jpeg", "Not a photo at all".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.errors[0].field").value("files[1]"))
                .andExpect(jsonPath("$.errors[0].code").value("FILE_TYPE_NOT_ALLOWED"));
        createExpense(treasurer, input, file("broken.png", "image/png", Arrays.copyOf(TestImages.png(), 40)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));

        assertThat(ownerJdbc().queryForObject("select count(*) from expense where tenant_id = ?", Integer.class,
                tenantA)).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from expense_attachment where tenant_id = ?",
                Integer.class, tenantA)).isZero();
        assertThat(storedMovements(tenantA)).isEmpty();
        // five files are fine
        UUID id = recordExpense(treasurer, input, pdf, pdf, pdf, pdf, pdf);
        expenseCard(treasurer, id).andExpect(jsonPath("$.attachments[*].n").value(Matchers.contains(1,
                2, 3, 4, 5)));
    }

    @Test
    void theFieldsAreValidated() throws Exception {
        UUID income = category(tenantA, "INCOME", "BANK_INTEREST");
        UUID waste = category(tenantA, "EXPENSE", "WASTE");
        ownerJdbc().update("update expense_category set archived = true where id = ?", waste);

        expectField(expense("CASH", today(), "0.00", materials), "amount", "AMOUNT_NOT_POSITIVE");
        expectField(expense("CASH", today(), "-5.00", materials), "amount", "AMOUNT_NOT_POSITIVE");
        expectField(expense("CASH", today(), "1000000000000.00", materials), "amount", "INVALID");
        expectField(expense("CASH", today().plusDays(1), "5.00", materials), "date", "DATE_IN_FUTURE");
        expectField(expense("CASH", today(), "5.00", income), "categoryId", "INVALID");
        expectField(expense("CASH", today(), "5.00", UUID.randomUUID()), "categoryId", "INVALID");
        expectField(expense("CASH", today(), "5.00", waste), "categoryId", "ARCHIVED");
        expectField(with(expense("CASH", today(), "5.00", materials), "counterparty", "х".repeat(201)),
                "counterparty", "TOO_LONG");
        expectField(with(expense("CASH", today(), "5.00", materials), "reuseAttachments",
                List.of(Map.of("expenseId", UUID.randomUUID().toString(), "n", 1))), "reuseAttachments[0]",
                "INVALID");
        Map<String, Object> noAmount = expense("CASH", today(), "5.00", materials);
        noAmount.remove("amount");
        expectField(noAmount, "amount", "REQUIRED");
        expectField(expense("CASH", today(), "5", materials), "amount", "INVALID");
        // not JSON at all, and no expense part
        send(treasurer, multipart(EXPENSES).part(new MockPart("expense", "{not json".getBytes())), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        send(treasurer, multipart(EXPENSES).file(file("a.pdf", "application/pdf", TestImages.pdf())), null)
                .andExpect(status().isBadRequest());
        // the part may also come as application/json
        MockPart jsonPart = new MockPart("expense", json.writeValueAsBytes(expense("CASH", today(), "5.00",
                materials)));
        jsonPart.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        send(treasurer, multipart(EXPENSES).part(jsonPart), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments.length()").value(0));
    }

    @Test
    void anExpenseNeedsTheOpeningBalanceAndIsNeverDatedBeforeIt() throws Exception {
        createExpense(treasurer, expense("BANK", today(), "5.00", materials))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        createExpense(treasurer, expense("CASH", opened.minusDays(1), "5.00", materials))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BEFORE_OPENING_BALANCE"))
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        createExpense(treasurer, expense("CASH", opened, "5.00", materials)).andExpect(status().isCreated());
    }

    @Test
    void theCashNeverGoesNegativeOnTheExpenseDayOrALaterDay() throws Exception {
        LocalDate first = opened.plusDays(1);
        LocalDate second = opened.plusDays(2);
        recordExpense(treasurer, expense("CASH", first, "300.00", materials));
        recordExpense(treasurer, expense("CASH", second, "500.00", materials));

        // example 4: more than the 200,00 left on the day
        createExpense(treasurer, expense("CASH", second, "250.00", materials))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"))
                .andExpect(jsonPath("$.balance").value("200.00"))
                .andExpect(jsonPath("$.date").value(second.toString()));
        // example 5: the earlier day stays positive (300,00), the later one would be -200,00
        createExpense(treasurer, expense("CASH", first, "400.00", materials))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.balance").value("200.00"))
                .andExpect(jsonPath("$.date").value(second.toString()));
        createExpense(treasurer, expense("CASH", first, "200.00", materials)).andExpect(status().isCreated());
        accounts(treasurer).andExpect(jsonPath("$[0].balance").value("0.00"));
    }

    @Test
    void anExpenseDatedInAClosedMonthBelongsToTheEarliestOpenMonthAndKeepsItsDate() throws Exception {
        LocalDate lastOfClosed = today().withDayOfMonth(1).minusDays(1);
        YearMonth closed = YearMonth.from(lastOfClosed);
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?) "
                + "on conflict (tenant_id, month) do nothing", tenantA, closed.atDay(1));
        ownerJdbc().update("update billing_month set status = 'CLOSED', closed_at = now() where tenant_id = ? "
                + "and month = ?", tenantA, closed.atDay(1));

        UUID id = recordExpense(treasurer, expense("CASH", lastOfClosed, "40.00", materials));

        expenseCard(treasurer, id).andExpect(jsonPath("$.date").value(lastOfClosed.toString()))
                .andExpect(jsonPath("$.bookingMonth").value(YearMonth.from(today()).toString()));
        assertThat(storedMovements(tenantA)).singleElement().satisfies(line -> assertThat(line)
                .containsEntry("moved_on", lastOfClosed.toString())
                .containsEntry("booking_month", today().withDayOfMonth(1).toString()));
    }

    @Test
    void aReversedExpenseIsReEnteredWithItsOldFilesAndTheCashIsRestored() throws Exception {
        String chairmanName = "Скарбник";
        byte[] pdf = TestImages.pdf();
        UUID old = recordExpense(treasurer, with(expense("CASH", today(), "300.00", materials), "counterparty",
                "ФОП Петренко"), file("photo.jpg", "image/jpeg", TestImages.cameraJpeg(false)),
                file("invoice.pdf", "application/pdf", pdf));
        byte[] oldPhoto = download(treasurer, old, 1).andReturn().getResponse().getContentAsByteArray();
        UUID oldMovement = UUID.fromString(storedMovements(tenantA).getFirst().get("id").toString());

        reverseExpense(chairman, old, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        JsonNode reversed = body(reverseExpense(chairman, old, " Невірна сума ").andExpect(status().isOk()));

        assertThat(reversed.get("reversed").asBoolean()).isTrue();
        assertThat(reversed.get("reversal").get("date").asString()).isEqualTo(today().toString());
        assertThat(reversed.get("reversal").get("reason").asString()).isEqualTo("Невірна сума");
        assertThat(reversed.get("reversal").get("createdByName").asString()).startsWith(chairmanName);
        assertThat(reversed.get("amount").asString()).isEqualTo("300.00");
        accounts(treasurer).andExpect(jsonPath("$[0].balance").value("1000.00"));
        List<Map<String, Object>> journal = storedMovements(tenantA);
        assertThat(journal).hasSize(2);
        assertThat(journal.get(1)).containsEntry("kind", "EXPENSE").containsEntry("amount", "300.00")
                .containsEntry("moved_on", today().toString()).containsEntry("reverses_movement_id", oldMovement)
                .containsEntry("reason", "Невірна сума").containsEntry("source_type", "EXPENSE");
        reverseExpense(chairman, old, "Ще раз").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));
        UUID reversalRow = ownerJdbc().queryForObject("select id from expense where reverses_expense_id = ?",
                UUID.class, old);
        expenseCard(treasurer, reversalRow).andExpect(status().isNotFound());
        reverseExpense(chairman, reversalRow, "Сторно сторно").andExpect(status().isNotFound());
        reverseExpense(chairman, UUID.randomUUID(), "Немає").andExpect(status().isNotFound());

        // «внести заново»: the old values with the old files, corrected
        UUID again = recordExpense(treasurer, with(with(expense("CASH", today(), "250.00", materials),
                "counterparty", "ФОП Петренко"), "reuseAttachments", List.of(Map.of("expenseId", old.toString(),
                "n", 2), Map.of("expenseId", old.toString(), "n", 1))),
                file("extra.png", "image/png", TestImages.png()));

        JsonNode card = body(expenseCard(treasurer, again).andExpect(status().isOk()));
        assertThat(card.get("attachments").findValuesAsString("fileName")).containsExactly("invoice.pdf",
                "photo.jpg", "extra.png");
        assertThat(download(treasurer, again, 1).andReturn().getResponse().getContentAsByteArray()).isEqualTo(pdf);
        assertThat(download(treasurer, again, 2).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(oldPhoto);
        accounts(treasurer).andExpect(jsonPath("$[0].balance").value("750.00"));
        JsonNode list = body(send(treasurer, get(EXPENSES), null));
        assertThat(list.get("totalItems").asLong()).as("a reversal is no line of its own").isEqualTo(2);
        assertThat(list.get("items").get(0).get("id").asString()).isEqualTo(again.toString());
        assertThat(list.get("items").get(0).get("attachmentCount").asInt()).isEqualTo(3);
        assertThat(list.get("items").get(1).get("reversed").asBoolean()).isTrue();
        // the reversal is audited about the reversed expense
        Map<String, Object> entry = awaitAudit(old, "EXPENSE_REVERSED", 1).getFirst();
        assertThat((String) entry.get("changes")).contains("\"reason\"", "Невірна сума", "\"reversedOn\"",
                today().toString(), "\"counterparty\"", "\"enc\"").doesNotContain("Петренко");
    }

    @Test
    void theListFiltersByPeriodAccountAndCategoryNewestFirst() throws Exception {
        setOpening(chairman, bank, "0.00", opened).andExpect(status().isOk());
        UUID water = category(tenantA, "EXPENSE", "WATER");
        UUID a = recordExpense(treasurer, expense("CASH", opened.plusDays(1), "10.00", materials));
        UUID b = recordExpense(treasurer, expense("BANK", opened.plusDays(2), "20.00", water));
        UUID c = recordExpense(treasurer, expense("CASH", opened.plusDays(3), "30.00", water));

        assertThat(ids(send(treasurer, get(EXPENSES), null))).containsExactly(c, b, a);
        assertThat(ids(send(treasurer, get(EXPENSES).param("account", "CASH"), null))).containsExactly(c, a);
        assertThat(ids(send(treasurer, get(EXPENSES).param("categoryId", water.toString()), null)))
                .containsExactly(c, b);
        assertThat(ids(send(treasurer, get(EXPENSES).param("from", opened.plusDays(2).toString())
                .param("to", opened.plusDays(2).toString()), null))).containsExactly(b);
        JsonNode page = body(send(treasurer, get(EXPENSES).param("page", "1").param("size", "2"), null));
        assertThat(page.get("totalItems").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("items")).singleElement().satisfies(item -> {
            assertThat(item.get("id").asString()).isEqualTo(a.toString());
            assertThat(item.get("attachmentCount").asInt()).isZero();
            assertThat(item.has("counterparty")).isFalse();
        });
        send(treasurer, get(EXPENSES).param("from", today().toString()).param("to", opened.toString()), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    @Test
    void theCounterpartyAndDescriptionAreNeverStoredAuditedOrPublishedInTheClear() throws Exception {
        String counterparty = "ФОП Петренко-Унікальний-Тест";
        String description = "Опис-Унікальний-Тест";
        // completed publications are deleted: a copy of every row written to the outbox is kept for the check
        ownerJdbc().execute("create table expense_outbox_copy (serialized_event text)");
        ownerJdbc().execute("""
                create function expense_outbox_copy() returns trigger language plpgsql security definer as $$
                begin
                    insert into expense_outbox_copy values (new.serialized_event);
                    return new;
                end;
                $$""");
        ownerJdbc().execute("create trigger expense_outbox_copy after insert on event_publication for each row "
                + "execute function expense_outbox_copy()");
        try {
            UUID id = recordExpense(treasurer, with(with(expense("CASH", today(), "15.00", materials),
                    "counterparty", counterparty), "description", description));
            reverseExpense(chairman, id, "Помилка").andExpect(status().isOk());
            awaitAudit(id, "EXPENSE_REVERSED", 1);

            Map<String, Object> stored = ownerJdbc().queryForMap("select counterparty_enc is not null as c, "
                    + "description_enc is not null as d, position(convert_to(?, 'UTF8') in counterparty_enc) as pc, "
                    + "position(convert_to(?, 'UTF8') in description_enc) as pd from expense where id = ?",
                    counterparty, description, id);
            assertThat(stored).containsEntry("c", true).containsEntry("d", true).containsEntry("pc", 0)
                    .containsEntry("pd", 0);
            for (String text : List.of(counterparty, description, "Петренко")) {
                assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? and "
                        + "cast(changes as text) like ?", Integer.class, tenantA, "%" + text + "%")).isZero();
                assertThat(ownerJdbc().queryForObject("select count(*) from expense_outbox_copy where "
                        + "serialized_event like ?", Integer.class, "%" + text + "%")).isZero();
                assertThat(ownerJdbc().queryForObject("select count(*) from event_publication where "
                        + "serialized_event like ?", Integer.class, "%" + text + "%")).isZero();
            }
            assertThat(ownerJdbc().queryForObject("select count(*) from expense_outbox_copy where serialized_event "
                    + "like ? and serialized_event like '%EXPENSE_RECORDED%'", Integer.class, "%" + id + "%"))
                    .isOne();
            // the log opens them for a reader who may see members' personal data, others see them hidden
            JsonNode opened = auditChanges(chairman, id, "EXPENSE_RECORDED");
            assertThat(value(opened, "counterparty").get("newValue").asString()).isEqualTo(counterparty);
            assertThat(value(opened, "description").get("newValue").asString()).isEqualTo(description);
            assertThat(value(opened, "amount").get("newValue").asString()).isEqualTo("15.00");
            JsonNode hidden = auditChanges(customRole(tenantA, "AUDIT_VIEW"), id, "EXPENSE_RECORDED");
            assertThat(value(hidden, "counterparty").get("hidden").asBoolean()).isTrue();
            assertThat(value(hidden, "counterparty").has("newValue")).isFalse();
            assertThat(value(hidden, "description").get("hidden").asBoolean()).isTrue();
            assertThat(value(hidden, "category").get("newValue").asString()).isEqualTo(materials.toString());
        } finally {
            ownerJdbc().execute("drop trigger if exists expense_outbox_copy on event_publication");
            ownerJdbc().execute("drop function if exists expense_outbox_copy()");
            ownerJdbc().execute("drop table if exists expense_outbox_copy");
        }
    }

    // ---- helpers

    private void expectField(Map<String, Object> input, String field, String code) throws Exception {
        createExpense(treasurer, input).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.hasItem(field)))
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].code")
                        .value(Matchers.hasItem(code)));
    }

    private List<UUID> ids(ResultActions list) throws Exception {
        List<UUID> ids = new ArrayList<>();
        body(list.andExpect(status().isOk())).get("items")
                .forEach(item -> ids.add(UUID.fromString(item.get("id").asString())));
        return ids;
    }

    private JsonNode auditChanges(String bearer, UUID expense, String action) throws Exception {
        JsonNode page = body(send(bearer, get("/api/v1/audit").param("entityType", "EXPENSE")
                .param("entityId", expense.toString()), null).andExpect(status().isOk()));
        for (JsonNode item : page.get("items")) {
            if (item.get("action").asString().equals(action)) {
                return item.get("changes");
            }
        }
        throw new AssertionError("No " + action + " entry");
    }

    private static JsonNode value(JsonNode changes, String field) {
        for (JsonNode change : changes) {
            if (change.get("field").asString().equals(field)) {
                return change;
            }
        }
        throw new AssertionError("No change of " + field);
    }
}
