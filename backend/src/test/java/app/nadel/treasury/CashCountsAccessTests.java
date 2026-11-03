package app.nadel.treasury;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Who may count the cash, see the counts and their acts, and book or reverse a difference (feature 018 §2.13; ADR
 * 0009): a count and its preview need {@code CASHBOOK_ENTER}, the list, the card and the act {@code CASHBOOK_VIEW},
 * booking a difference and reversing its line {@code CASHBOOK_APPROVE} (the treasurer is refused); a member and the
 * platform operator are 403; another cooperative's count, line and act are 404 even with every permission.
 */
class CashCountsAccessTests extends TreasuryApiSupport {

    String chairman;
    String treasurer;
    UUID cash;
    UUID count;
    UUID line;
    Map<String, Object> entry;

    @BeforeEach
    void countWithABookedDifference() throws Exception {
        chairman = person(tenantA, "CHAIRMAN");
        treasurer = person(tenantA, "TREASURER");
        cash = account(tenantA, "CASH");
        LocalDate opened = today().minusMonths(1).withDayOfMonth(1);
        setOpening(chairman, cash, "200.00", opened).andExpect(status().isOk());
        entry = count(kyiv(opened.plusDays(2), 10), "190.00", "Решта");
        count = saveCount(treasurer, entry);
        UUID unbooked = saveCount(treasurer, count(kyiv(opened.plusDays(3), 10), "180.00", "Ще решта"));
        JsonNode booked = body(bookDifference(chairman, count, "Нестача").andExpect(status().isOk()));
        line = UUID.fromString(booked.get("booking").get("movementId").asString());
        count = unbooked; // the treasurer's refused booking below is tried on a count that still has its difference
    }

    @Test
    void theTreasurerCountsAndSeesButNeitherBooksNorReverses() throws Exception {
        previewCount(treasurer, entry).andExpect(status().isOk());
        counts(treasurer).andExpect(status().isOk());
        countCard(treasurer, count).andExpect(status().isOk());
        countAct(treasurer, count).andExpect(status().isOk());

        bookDifference(treasurer, count, "Нестача").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        reverseMovement(treasurer, cash, line, "Помилка").andExpect(status().isForbidden());
    }

    @Test
    void aViewOnlyRoleSeesTheCountsAndTheActButCannotCount() throws Exception {
        String viewer = customRole(tenantA, "CASHBOOK_VIEW");

        counts(viewer).andExpect(status().isOk());
        countCard(viewer, count).andExpect(status().isOk());
        countAct(viewer, count).andExpect(status().isOk());
        previewCount(viewer, entry).andExpect(status().isForbidden());
        enterCount(viewer, entry).andExpect(status().isForbidden());
        bookDifference(viewer, count, "Нестача").andExpect(status().isForbidden());
    }

    @Test
    void aCustomRoleWithApproveBooksAndReverses() throws Exception {
        String approver = customRole(tenantA, "CASHBOOK_APPROVE");

        bookDifference(approver, count, "Нестача").andExpect(status().isOk());
        reverseMovement(approver, cash, line, "Помилка").andExpect(status().isOk());
    }

    @Test
    void aMemberAndThePlatformOperatorAreForbiddenAndNoTokenIsUnauthorized() throws Exception {
        String member = person(tenantA, "MEMBER");
        for (String bearer : new String[] {member, operator()}) {
            counts(bearer).andExpect(status().isForbidden());
            countCard(bearer, count).andExpect(status().isForbidden());
            countAct(bearer, count).andExpect(status().isForbidden());
            enterCount(bearer, count(Instant.now(), "1.00", "x")).andExpect(status().isForbidden());
            bookDifference(bearer, count, "x").andExpect(status().isForbidden());
            reverseMovement(bearer, cash, line, "x").andExpect(status().isForbidden());
        }
        counts(null).andExpect(status().isUnauthorized());
        countAct(null, count).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherCooperativeFindsNothingEvenWithEveryPermission() throws Exception {
        String other = person(tenantB, "CHAIRMAN");

        countCard(other, count).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        countAct(other, count).andExpect(status().isNotFound());
        bookDifference(other, count, "Нестача").andExpect(status().isNotFound());
        reverseMovement(other, cash, line, "Помилка").andExpect(status().isNotFound());
        reverseMovement(other, account(tenantB, "CASH"), line, "Помилка").andExpect(status().isNotFound());
        counts(other).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
    }
}
