package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import nadel.contract.model.AuditAction;
import nadel.contract.model.AuditEntityType;
import org.junit.jupiter.api.Test;

/**
 * Every action and entity type the treasury writes to the audit log is a value of the contract's enums: the audit
 * listing maps a stored entry with {@code AuditAction.fromValue}, so an action missing from {@code api/openapi.yaml}
 * would make the listing fail as soon as such an entry exists.
 */
class TreasuryAuditActionsTests {

    @Test
    void everyTreasuryActionIsAnAuditActionOfTheContract() {
        var contract = Arrays.stream(AuditAction.values()).map(AuditAction::getValue).toList();

        assertThat(Arrays.stream(TreasuryAuditPublisher.Action.values()).map(Enum::name)).isSubsetOf(contract)
                .contains("OPENING_BALANCE_SET", "EXPENSE_RECORDED", "EXPENSE_REVERSED", "CASHBOOK_CATEGORY_ADDED",
                        "CASHBOOK_CATEGORY_RENAMED", "CASHBOOK_CATEGORY_REORDERED", "CASHBOOK_CATEGORY_ARCHIVED",
                        "HANDOVER_RECORDED", "HANDOVER_REVERSED", "OTHER_INCOME_RECORDED", "OTHER_INCOME_REVERSED",
                        "CASH_COUNTED", "COUNT_DIFFERENCE_BOOKED", "COUNT_DIFFERENCE_REVERSED");
    }

    @Test
    void theTreasuryEntityTypesAreAuditEntityTypesOfTheContract() {
        assertThat(AuditEntityType.fromValue(TreasuryAuditPublisher.MONEY_ACCOUNT))
                .isEqualTo(AuditEntityType.MONEY_ACCOUNT);
        assertThat(AuditEntityType.fromValue(TreasuryAuditPublisher.EXPENSE)).isEqualTo(AuditEntityType.EXPENSE);
        assertThat(AuditEntityType.fromValue(TreasuryAuditPublisher.CASHBOOK_CATEGORY))
                .isEqualTo(AuditEntityType.CASHBOOK_CATEGORY);
        assertThat(AuditEntityType.fromValue(TreasuryAuditPublisher.HANDOVER)).isEqualTo(AuditEntityType.HANDOVER);
        assertThat(AuditEntityType.fromValue(TreasuryAuditPublisher.OTHER_INCOME))
                .isEqualTo(AuditEntityType.OTHER_INCOME);
        assertThat(AuditEntityType.fromValue(TreasuryAuditPublisher.CASH_COUNT))
                .isEqualTo(AuditEntityType.CASH_COUNT);
    }
}
