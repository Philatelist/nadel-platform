package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.members.MemberImport;
import app.nadel.members.MemberImportAction;
import app.nadel.members.MemberImportItem;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link MemberImport#apply} over sealed contacts: a new member is stored sealed with its e-mail blind index and phone
 * index, a matched member found by the blind index of its e-mail gets its new phone sealed and indexed (the old index
 * row goes), and what the import leaves blank (the notes) stays as stored.
 */
class MemberImportApplyTests extends IntegrationTest {

    @Autowired
    MemberImport memberImport;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    PersonalDataCipher cipher;

    private UUID imported;

    @AfterEach
    void removeImported() {
        if (imported != null) {
            ownerJdbc().update("delete from member where id = ?", imported);
        }
    }

    @Test
    void newAndMatchedMembersAreStoredSealedAndIndexed() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String existingEmail = "apply." + tag + "@import.example.com";
        UUID existing = newMember(DEMO_TENANT, "Applyzz" + tag, "Existing", null, existingEmail, "+380931230001");
        setContact(DEMO_TENANT, existing, "notes", "kept note");
        List<MemberImportRow> rows = List.of(
                new MemberImportRow(2, "A-1", "Applyzz" + tag, "New", null, List.of("093 123 00 02"),
                        "New." + tag + "@Import.Example.com", null, null, null, null),
                new MemberImportRow(3, "A-2", null, null, null, List.of("+380931230003"),
                        existingEmail.toUpperCase(), null, null, null, null));

        List<AuditEvent> events = TenantContext.runAs(DEMO_TENANT, () ->
                new TransactionTemplate(transactions).execute(status -> {
                    MemberImportPlan plan = memberImport.plan(DEMO_TENANT, UUID.randomUUID(), rows);
                    memberImport.lock(plan);
                    imported = plan.items().getFirst().memberId();
                    assertThat(plan.items()).extracting(MemberImportItem::action)
                            .containsExactly(MemberImportAction.NEW, MemberImportAction.UPDATE);
                    return memberImport.apply(DEMO_TENANT, rows, plan, new Actor(null, "import-test", "Import"));
                }));

        Contacts created = decryptedContacts(DEMO_TENANT, imported);
        assertThat(created.email()).isEqualTo("new." + tag + "@import.example.com");
        assertThat(created.phones()).containsExactly("+380931230002");
        assertThat(ownerJdbc().queryForObject("select email_bidx from member where id = ?", byte[].class, imported))
                .isEqualTo(index(IndexKind.EMAIL, "new." + tag + "@import.example.com"));
        assertThat(phoneIndex(imported)).containsExactly(index(IndexKind.PHONE, "+380931230002"));

        Contacts updated = decryptedContacts(DEMO_TENANT, existing);
        assertThat(updated.phones()).containsExactly("+380931230003");
        assertThat(updated.email()).isEqualTo(existingEmail);
        assertThat(updated.notes()).isEqualTo("kept note");
        assertThat(phoneIndex(existing)).containsExactly(index(IndexKind.PHONE, "+380931230003"));

        assertThat(events).extracting(AuditEvent::action).containsExactly("MEMBER_CREATED", "MEMBER_UPDATED");
        // the import's audit events carry the personal values sealed at their place in the entry (ADR 0016)
        FieldChange phones = events.get(1).changes().getFirst();
        assertThat(events.get(1).changes()).hasSize(1);
        assertThat(phones.field()).isEqualTo("phones");
        assertThat(phones.sealed()).isTrue();
        UUID entry = events.get(1).eventId();
        assertThat(openAudit(FieldChange.oldValueRef(entry, "phones"), phones.oldValue())).isEqualTo("+380931230001");
        assertThat(openAudit(FieldChange.newValueRef(entry, "phones"), phones.newValue())).isEqualTo("+380931230003");
    }

    private String openAudit(FieldRef ref, String base64) {
        byte[] sealed = Base64.getDecoder().decode(base64);
        return TenantContext.runAs(DEMO_TENANT, () -> cipher.decrypt(DEMO_TENANT, ref, sealed));
    }

    private byte[] index(IndexKind kind, String value) {
        return TenantContext.runAs(DEMO_TENANT, () -> cipher.blindIndex(DEMO_TENANT, kind, value));
    }

    private List<byte[]> phoneIndex(UUID member) {
        return ownerJdbc().queryForList("select phone_bidx from member_phone_index where member_id = ?",
                byte[].class, member);
    }
}
