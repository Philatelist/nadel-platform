package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import nadel.contract.model.AuditAction;
import nadel.contract.model.AuditEntityType;
import org.junit.jupiter.api.Test;

/**
 * Every action and entity type the documents write to the audit log is a value of the contract's enums: the audit
 * listing maps a stored entry with {@code AuditAction.fromValue}, so an action missing from {@code api/openapi.yaml}
 * would make the listing fail as soon as such an entry exists.
 */
class DocumentsAuditActionsTests {

    @Test
    void everyDocumentsActionIsAnAuditActionOfTheContract() {
        var contract = Arrays.stream(AuditAction.values()).map(AuditAction::getValue).toList();

        assertThat(Arrays.stream(InvoiceAuditPublisher.Action.values()).map(Enum::name)).isSubsetOf(contract);
    }

    @Test
    void theInvoiceEntityTypeIsAnAuditEntityTypeOfTheContract() {
        assertThat(AuditEntityType.fromValue(InvoiceAuditPublisher.INVOICE)).isEqualTo(AuditEntityType.INVOICE);
    }

    @Test
    void everyCertificateActionAndItsEntityTypeAreValuesOfTheContract() {
        var contract = Arrays.stream(AuditAction.values()).map(AuditAction::getValue).toList();

        assertThat(Arrays.stream(CertificateAuditPublisher.Action.values()).map(Enum::name)).isSubsetOf(contract);
        assertThat(AuditEntityType.fromValue(CertificateAuditPublisher.CERTIFICATE))
                .isEqualTo(AuditEntityType.CERTIFICATE);
    }
}
