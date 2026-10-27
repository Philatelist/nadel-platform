package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import app.nadel.members.MemberImport;
import app.nadel.members.MemberImportAction;
import app.nadel.members.MemberImportItem;
import app.nadel.members.MemberImportRow;
import app.nadel.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Planning member rows of an import against the members of the cooperative: matching, overlay, rules, duplicates. */
class MemberImportPlanTests extends IntegrationTest {

    @Autowired
    MemberImport memberImport;

    @Autowired
    PlatformTransactionManager transactions;

    private final List<UUID> created = new ArrayList<>();
    /** Makes names, phones and e-mails of one test unlike those of every other test, so matches are deliberate. */
    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private int counter;

    @AfterEach
    void removeFixtures() {
        for (UUID id : created) {
            ownerJdbc().update("delete from member where id = ?", id);
        }
        created.clear();
    }

    // ---- fixtures

    private String last(String name) {
        return name + tag;
    }

    private String email(String local) {
        return local + "." + tag + "@import.example.com";
    }

    /** A phone that no other fixture has: +38067 followed by the tag digits and a counter. */
    private String phone() {
        return "+380" + (670_000_000 + Math.abs(tag.hashCode() % 1_000_000) * 10 + (++counter) % 10);
    }

    private UUID member(String lastName, String firstName, String middleName, String email, String address,
            String channel, String telegram, String viber, boolean archived, String... phones) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name, middle_name) "
                + "values (?, ?, ?, ?, ?)", id, DEMO_TENANT, lastName, firstName, middleName);
        created.add(id);
        insertContacts(DEMO_TENANT, id, email, phones);
        setContact(DEMO_TENANT, id, "postal_address", address);
        setContact(DEMO_TENANT, id, "telegram_username", telegram);
        setContact(DEMO_TENANT, id, "viber_phone", viber);
        setContact(DEMO_TENANT, id, "notes", "secret note");
        // the channel after the contacts it needs (channel rule on the ciphertext), the archive state last
        ownerJdbc().update("update member set delivery_channel = ?, archived_at = "
                + (archived ? "now()" : "null") + " where id = ?", channel, id);
        return id;
    }

    private UUID simpleMember(String lastName, String firstName, String email, String... phones) {
        return member(lastName, firstName, null, email, null, "PAPER", null, null, false, phones);
    }

    private static MemberImportRow row(int row, String key, String lastName, String firstName, String email,
            String... phones) {
        return new MemberImportRow(row, key, lastName, firstName, null, List.of(phones), email, null, null, null,
                null);
    }

    private static MemberImportRow rowWithChannel(int row, String key, String lastName, String firstName,
            String email, String channel, String telegram, String viber, String... phones) {
        return new MemberImportRow(row, key, lastName, firstName, null, List.of(phones), email, null, channel,
                telegram, viber);
    }

    private List<MemberImportItem> plan(UUID importId, MemberImportRow... rows) {
        return TenantContext.runAs(DEMO_TENANT, () ->
                new TransactionTemplate(transactions).execute(status ->
                        memberImport.plan(DEMO_TENANT, importId, List.of(rows)).items()));
    }

    private List<MemberImportItem> plan(MemberImportRow... rows) {
        return plan(UUID.randomUUID(), rows);
    }

    private static List<String> problems(MemberImportItem item) {
        return item.problems().stream().map(p -> p.field() + ":" + p.code()).toList();
    }

    // ---- new

    @Test
    void aMemberNobodyHasIsNewWithAnIdFromTheImportAndTheKey() {
        UUID importId = UUID.randomUUID();

        MemberImportItem item = plan(importId, row(7, "M-1", last("Nova"), "Oksana", email("nova"))).getFirst();
        MemberImportItem again = plan(importId, row(9, "m-1", last("Other"), "Ivan", email("other"))).getFirst();

        assertThat(item.row()).isEqualTo(7);
        assertThat(item.action()).isEqualTo(MemberImportAction.NEW);
        assertThat(item.problems()).isEmpty();
        assertThat(item.changedFields()).isEmpty();
        assertThat(item.memberId()).isEqualTo(
                UUID.nameUUIDFromBytes((importId + "/member/m-1").getBytes(StandardCharsets.UTF_8)));
        // the same import and key (whatever the letter case) always give the same id; another import another id
        assertThat(again.memberId()).isEqualTo(item.memberId());
        assertThat(plan(UUID.randomUUID(), row(7, "M-1", last("Nova"), "Oksana", email("nova"))).getFirst()
                .memberId()).isNotEqualTo(item.memberId());
    }

    @Test
    void aNewMemberMayHaveOnlyAPhone() {
        MemberImportItem item = plan(row(2, "M-1", last("Phoneonly"), "Petro", null, "0671110001")).getFirst();

        assertThat(item.action()).isEqualTo(MemberImportAction.NEW);
        assertThat(item.problems()).isEmpty();
    }

    // ---- matching by e-mail

    @Test
    void aRowWithAnEmailMatchesTheActiveMemberWithThatEmailIgnoringCase() {
        String email = email("match");
        UUID existing = simpleMember(last("Old"), "Name", email, phone());

        MemberImportItem item = plan(row(2, "M-1", last("New"), "Name", email.toUpperCase(), phone())).getFirst();

        assertThat(item.action()).isEqualTo(MemberImportAction.UPDATE);
        assertThat(item.memberId()).isEqualTo(existing);
        assertThat(item.changedFields()).containsExactly("lastName", "phones");
        assertThat(item.problems()).isEmpty();
    }

    @Test
    void aMemberAlreadyAsInTheFileIsUnchangedAndACaseChangeIsAnUpdate() {
        String email = email("same");
        String phone = phone();
        UUID existing = simpleMember(last("Same"), "Person", email, phone);

        MemberImportItem item = plan(row(2, "M-1", last("Same").toUpperCase(), "Person", email, phone)).getFirst();

        // a last name that differs in letter case only changes the stored text, as it would in the member form ...
        assertThat(item.action()).isEqualTo(MemberImportAction.UPDATE);
        assertThat(item.changedFields()).containsExactly("lastName");
        // ... and the exact values are unchanged
        MemberImportItem exact = plan(row(2, "M-1", last("Same"), "Person", email, phone)).getFirst();
        assertThat(exact.action()).isEqualTo(MemberImportAction.UNCHANGED);
        assertThat(exact.memberId()).isEqualTo(existing);
        assertThat(exact.changedFields()).isEmpty();
    }

    @Test
    void thePhoneOrderAndSpellingDoNotMakeAChange() {
        String email = email("order");
        UUID existing = simpleMember(last("Order"), "Person", email, "+380671110011", "+380931110012");

        MemberImportItem item = plan(row(2, "M-1", last("Order"), "Person", email, "093 111 00 12",
                "(067) 111-00-11")).getFirst();

        assertThat(item.action()).isEqualTo(MemberImportAction.UNCHANGED);
        assertThat(item.memberId()).isEqualTo(existing);
    }

    @Test
    void anArchivedMemberIsNeverMatched() {
        String email = email("archived");
        String phone = phone();
        member(last("Gone"), "Person", null, email, null, "PAPER", null, null, true, phone);

        MemberImportItem byEmail = plan(row(2, "M-1", last("Gone"), "Person", email)).getFirst();
        MemberImportItem byPhone = plan(row(3, "M-2", last("Gone"), "Person", null, phone)).getFirst();

        assertThat(byEmail.action()).isEqualTo(MemberImportAction.NEW);
        assertThat(byPhone.action()).isEqualTo(MemberImportAction.NEW);
    }

    @Test
    void aMemberOfAnotherCooperativeIsNeverMatched() {
        String email = email("other.coop");
        newMember(OTHER_TENANT, last("Other"), "Person", null, email, "+380671110020");

        MemberImportItem byEmail = plan(row(2, "M-1", last("Other"), "Person", email)).getFirst();
        MemberImportItem byPhone = plan(row(3, "M-2", last("Other"), "Person", null, "0671110020")).getFirst();

        assertThat(byEmail.action()).isEqualTo(MemberImportAction.NEW);
        assertThat(byPhone.action()).isEqualTo(MemberImportAction.NEW);
    }

    // ---- matching by name and first phone

    @Test
    void aRowWithoutAnEmailMatchesByNameAndFirstPhoneInAnyPhoneSpelling() {
        UUID existing = simpleMember(last("Ivanenko"), "Petro", null, "+380671110031", "+380931110032");

        MemberImportItem national = plan(row(2, "M-1", last("Ivanenko"), "Petro", null, "0671110031",
                "093 111 00 32")).getFirst();
        MemberImportItem international = plan(row(3, "M-2", last("IVANENKO").toLowerCase(), "PETRO", null,
                "+380 (67) 111-00-31")).getFirst();
        MemberImportItem secondPhoneOfTheMember = plan(row(4, "M-3", last("Ivanenko"), "Petro", null,
                "380931110032")).getFirst();

        assertThat(national.action()).isEqualTo(MemberImportAction.UNCHANGED);
        assertThat(national.memberId()).isEqualTo(existing);
        // a phone cell that is filled replaces the phones: the second phone is gone
        assertThat(international.action()).isEqualTo(MemberImportAction.UPDATE);
        assertThat(international.memberId()).isEqualTo(existing);
        assertThat(international.changedFields()).containsExactly("lastName", "firstName", "phones");
        assertThat(secondPhoneOfTheMember.memberId()).isEqualTo(existing);
        assertThat(secondPhoneOfTheMember.changedFields()).containsExactly("phones");
    }

    @Test
    void theSamePhoneWithAnotherNameIsAnotherMember() {
        String phone = phone();
        simpleMember(last("Family"), "Husband", null, phone);

        MemberImportItem item = plan(row(2, "M-1", last("Family"), "Wife", null, phone)).getFirst();

        assertThat(item.action()).isEqualTo(MemberImportAction.NEW);
    }

    @Test
    void aNameAndFirstPhoneOfTwoMembersIsAmbiguous() {
        String phone = phone();
        simpleMember(last("Twin"), "Person", null, phone);
        simpleMember(last("Twin"), "Person", null, phone, "+380931110041");

        MemberImportItem item = plan(row(2, "M-1", last("Twin"), "Person", null, phone)).getFirst();

        assertThat(item.action()).isNull();
        assertThat(item.memberId()).isNull();
        assertThat(problems(item)).containsExactly("phones:MEMBER_AMBIGUOUS");
    }

    @Test
    void aRowWithNeitherEmailNorPhoneHasNoKey() {
        MemberImportItem item = plan(row(2, "M-1", last("Nokey"), "Person", null)).getFirst();

        assertThat(item.action()).isNull();
        assertThat(problems(item)).containsExactly("email:MEMBER_KEY_MISSING");
    }

    @Test
    void anUnreadablePhoneIsThePhoneProblemNotAMissingKey() {
        MemberImportItem item = plan(row(2, "M-1", last("Badphone"), "Person", null, "12-34")).getFirst();

        assertThat(problems(item)).containsExactly("phones:PHONE_INVALID");
    }

    @Test
    void anEmailOfOneMemberWithTheNameAndPhoneOfAnotherIsTaken() {
        String email = email("taken");
        String phone = phone();
        simpleMember(last("Holder"), "Email", email, phone());
        simpleMember(last("Wanted"), "Person", null, phone);

        MemberImportItem item = plan(row(2, "M-1", last("Wanted"), "Person", email, phone)).getFirst();

        assertThat(item.action()).isNull();
        assertThat(problems(item)).containsExactly("email:EMAIL_TAKEN");
    }

    @Test
    void anEmailAndAPhoneOfTheSameMemberAreFine() {
        String email = email("both");
        String phone = phone();
        UUID existing = simpleMember(last("Both"), "Person", email, phone);

        MemberImportItem item = plan(row(2, "M-1", last("Both"), "Person", email, phone)).getFirst();

        assertThat(item.action()).isEqualTo(MemberImportAction.UNCHANGED);
        assertThat(item.memberId()).isEqualTo(existing);
    }

    // ---- duplicates in the file

    @Test
    void aKeyThatAppearsTwiceIgnoringCaseIsADuplicateOnTheLaterRow() {
        List<MemberImportItem> items = plan(
                row(2, "Key-1", last("A"), "One", email("a")),
                row(3, "KEY-1", last("B"), "Two", email("b")),
                row(4, "key-2", last("C"), "Three", email("c")));

        assertThat(items.get(0).action()).isEqualTo(MemberImportAction.NEW);
        assertThat(items.get(1).action()).isNull();
        assertThat(problems(items.get(1))).containsExactly("externalId:DUPLICATE_IN_FILE");
        assertThat(items.get(2).action()).isEqualTo(MemberImportAction.NEW);
    }

    @Test
    void anEmailThatAppearsTwiceIgnoringCaseIsADuplicate() {
        String email = email("dup");

        List<MemberImportItem> items = plan(
                row(2, "M-1", last("A"), "One", email),
                row(3, "M-2", last("B"), "Two", email.toUpperCase()));

        assertThat(items.get(0).action()).isEqualTo(MemberImportAction.NEW);
        assertThat(problems(items.get(1))).containsExactly("email:DUPLICATE_IN_FILE");
    }

    @Test
    void aNameAndFirstPhoneThatAppearTwiceAreADuplicate() {
        List<MemberImportItem> items = plan(
                row(2, "M-1", last("Same"), "Person", null, "0671110051"),
                row(3, "M-2", last("SAME"), "person", null, "+380671110051", "0931110052"));

        assertThat(items.get(0).action()).isEqualTo(MemberImportAction.NEW);
        assertThat(problems(items.get(1))).containsExactly("phones:DUPLICATE_IN_FILE");
    }

    @Test
    void twoRowsThatMatchTheSameExistingMemberAreADuplicate() {
        String email = email("twice");
        String phone = phone();
        UUID existing = simpleMember(last("Twice"), "Person", email, phone);

        List<MemberImportItem> items = plan(
                row(2, "M-1", last("Twice"), "Person", email),
                row(3, "M-2", last("Twice"), "Person", null, phone));

        assertThat(items.get(0).memberId()).isEqualTo(existing);
        assertThat(items.get(1).action()).isNull();
        assertThat(problems(items.get(1))).containsExactly("phones:DUPLICATE_IN_FILE");
    }

    // ---- blank cells and rules

    @Test
    void blankCellsLeaveTheValuesOfAnExistingMemberAlone() {
        String email = email("overlay");
        String phone = phone();
        UUID existing = member(last("Overlay"), "Person", "Middle", email, "1 Garden St", "EMAIL", "@tg", "+380671110061",
                false, phone);

        MemberImportItem blank = plan(new MemberImportRow(2, "M-1", null, null, null, List.of(), email, null, null,
                null, null)).getFirst();
        MemberImportItem partial = plan(new MemberImportRow(3, "M-1", null, null, "Other", List.of(), email,
                "2 Orchard St", null, null, null)).getFirst();

        assertThat(blank.action()).isEqualTo(MemberImportAction.UNCHANGED);
        assertThat(blank.memberId()).isEqualTo(existing);
        assertThat(partial.action()).isEqualTo(MemberImportAction.UPDATE);
        assertThat(partial.changedFields()).containsExactly("middleName", "postalAddress");
    }

    @Test
    void aBlankLastNameIsRequiredOnlyForANewMember() {
        MemberImportItem item = plan(row(2, "M-1", null, "Person", email("noname"))).getFirst();

        assertThat(problems(item)).containsExactly("lastName:REQUIRED");
    }

    @Test
    void aMissingKeyIsRequired() {
        MemberImportItem item = plan(row(2, null, last("Nokey"), "Person", email("nokey"))).getFirst();

        assertThat(problems(item)).containsExactly("externalId:REQUIRED");
    }

    @Test
    void anEmailThatIsNotAnAddressAndPhonesThatAreNotNumbersAreProblems() {
        MemberImportItem item = plan(new MemberImportRow(2, "M-1", last("Bad"), "Person", null,
                List.of("0671110071", "abc", "12"), "not-an-email", null, null, null, "oops")).getFirst();

        assertThat(problems(item)).containsExactlyInAnyOrder("phones:PHONE_INVALID", "email:INVALID",
                "viberPhone:PHONE_INVALID");
    }

    @Test
    void tooLongTextAndTooManyPhonesAreProblems() {
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("+38067111" + String.format("%04d", i));
        }

        MemberImportItem longName = plan(row(2, "M-1", "x".repeat(101), "Person", email("long"))).getFirst();
        MemberImportItem manyPhones = plan(new MemberImportRow(3, "M-2", last("Many"), "Person", null, eleven, null,
                null, null, null, null)).getFirst();

        assertThat(problems(longName)).containsExactly("lastName:TOO_LONG");
        assertThat(problems(manyPhones)).containsExactly("phones:TOO_LONG");
    }

    @Test
    void oneProblemIsReportedPerFieldAndEveryFieldIsReported() {
        MemberImportItem item = plan(row(2, "M-1", " ", "", "nope", "bad")).getFirst();

        assertThat(problems(item)).containsExactly("lastName:REQUIRED", "firstName:REQUIRED", "phones:PHONE_INVALID",
                "email:INVALID");
    }

    // ---- delivery channel

    @Test
    void theEmailChannelNeedsAnEmailOfTheRowOrOfTheMember() {
        String email = email("channel");
        simpleMember(last("Channel"), "Has", email, phone());

        MemberImportItem missing = plan(rowWithChannel(2, "M-1", last("Channel"), "New", null, "email", null, null,
                "0671110081")).getFirst();
        MemberImportItem fromTheRow = plan(rowWithChannel(3, "M-2", last("Channel"), "Row", email("row"), "EMAIL",
                null, null)).getFirst();
        MemberImportItem fromTheMember = plan(rowWithChannel(4, "M-3", last("Channel"), "Has", email, "EMAIL", null,
                null)).getFirst();

        assertThat(problems(missing)).containsExactly("email:CHANNEL_CONTACT_MISSING");
        assertThat(fromTheRow.action()).isEqualTo(MemberImportAction.NEW);
        assertThat(fromTheMember.action()).isEqualTo(MemberImportAction.UPDATE);
        assertThat(fromTheMember.changedFields()).containsExactly("deliveryChannel");
    }

    @Test
    void theTelegramChannelNeedsAUsername() {
        MemberImportItem without = plan(rowWithChannel(2, "M-1", last("Tg"), "Without", email("tg1"), "TELEGRAM",
                null, null)).getFirst();
        MemberImportItem with = plan(rowWithChannel(3, "M-2", last("Tg"), "With", email("tg2"), "Telegram",
                "@someone", null)).getFirst();

        assertThat(problems(without)).containsExactly("telegramUsername:CHANNEL_CONTACT_MISSING");
        assertThat(with.action()).isEqualTo(MemberImportAction.NEW);
        assertThat(with.problems()).isEmpty();
    }

    @Test
    void theViberChannelNeedsAViberPhoneAndPaperNeedsNothing() {
        MemberImportItem without = plan(rowWithChannel(2, "M-1", last("Vb"), "Without", email("vb1"), "VIBER", null,
                null)).getFirst();
        MemberImportItem with = plan(rowWithChannel(3, "M-2", last("Vb"), "With", email("vb2"), "viber", null,
                "0671110091")).getFirst();
        MemberImportItem paper = plan(rowWithChannel(4, "M-3", last("Vb"), "Paper", email("vb3"), "PAPER", null,
                null)).getFirst();

        assertThat(problems(without)).containsExactly("viberPhone:CHANNEL_CONTACT_MISSING");
        assertThat(with.action()).isEqualTo(MemberImportAction.NEW);
        assertThat(paper.action()).isEqualTo(MemberImportAction.NEW);
    }

    @Test
    void anUnknownChannelIsInvalid() {
        MemberImportItem item = plan(rowWithChannel(2, "M-1", last("Ch"), "Person", email("unknown"), "pigeon", null,
                null)).getFirst();

        assertThat(problems(item)).containsExactly("deliveryChannel:INVALID");
    }

    @Test
    void aBlankChannelKeepsTheChannelOfAnExistingMember() {
        String email = email("keepchannel");
        member(last("Keep"), "Channel", null, email, null, "TELEGRAM", "@keep", null, false);

        MemberImportItem item = plan(row(2, "M-1", last("Keep"), "Channel", email)).getFirst();

        assertThat(item.action()).isEqualTo(MemberImportAction.UNCHANGED);
    }

    // ---- bulk lookups

    @Test
    void manyRowsAreMatchedWithoutAQueryPerRow() {
        List<MemberImportRow> rows = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            rows.add(row(i + 2, "M-" + i, last("Bulk") + i, "Person", "bulk" + i + "." + tag + "@import.example.com"));
        }
        UUID existing = simpleMember(last("Bulk") + 1100, "Person", "bulk1100." + tag + "@import.example.com");

        List<MemberImportItem> items = plan(rows.toArray(MemberImportRow[]::new));

        assertThat(items).hasSize(1200);
        assertThat(items.stream().filter(item -> item.action() == MemberImportAction.NEW).count()).isEqualTo(1199);
        assertThat(items.get(1100).action()).isEqualTo(MemberImportAction.UNCHANGED);
        assertThat(items.get(1100).memberId()).isEqualTo(existing);
    }
}
