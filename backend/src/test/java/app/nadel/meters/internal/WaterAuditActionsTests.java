package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import nadel.contract.model.AuditAction;
import nadel.contract.model.AuditEntityType;
import org.junit.jupiter.api.Test;

/**
 * Every action and entity type the water connections write to the audit log is a value of the contract's enums: the
 * audit listing maps a stored entry with {@code AuditAction.fromValue}, so an action missing from
 * {@code api/openapi.yaml} makes the listing fail as soon as such an entry exists.
 */
class WaterAuditActionsTests {

    @Test
    void everyWaterActionIsAnAuditActionOfTheContract() {
        var contract = Arrays.stream(AuditAction.values()).map(AuditAction::getValue).toList();

        assertThat(Arrays.stream(MeterAuditPublisher.WaterAction.values()).map(Enum::name)).isSubsetOf(contract);
    }

    @Test
    void theWaterEntityTypeIsAnAuditEntityTypeOfTheContract() {
        var contract = Arrays.stream(AuditEntityType.values()).map(AuditEntityType::getValue).toList();

        assertThat(contract).contains(MeterAuditPublisher.PLOT);
    }
}
