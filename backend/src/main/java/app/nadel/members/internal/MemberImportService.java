package app.nadel.members.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.members.MemberImport;
import app.nadel.members.MemberImportAction;
import app.nadel.members.MemberImportItem;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.privacy.IndexKind;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.problem.ValidationProblems;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.MemberInput;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

/**
 * Plans the member rows of an Excel import. Reads with a few bulk queries (active members by the blind index of the
 * e-mail, active members by the blind index of a phone in {@code member_phone_index}, 500 values per query), never one
 * query per row, and writes nothing. The contacts of the matched members are opened through {@link MemberCrypto}.
 * <p>
 * Matching, among active members only: a row with an e-mail matches the member with that e-mail; a row without one
 * matches the member with the same last name, first name and first phone (case ignored, phones in international
 * format). A row with neither is {@code MEMBER_KEY_MISSING} because a later import could not recognise it; a name and
 * phone shared by two members is {@code MEMBER_AMBIGUOUS}. A row whose e-mail belongs to one member while its name and
 * first phone identify another is {@code EMAIL_TAKEN}. A key, an e-mail or a name and first phone that appears twice
 * in the file, or two rows that match the same member, are {@code DUPLICATE_IN_FILE} on the later row.
 * <p>
 * A blank value leaves the value of the matched member unchanged; a filled phone cell replaces the phones. The rules
 * are those of {@link MemberData}, plus the constraints of the API contract ({@code MemberInput}: lengths, e-mail
 * format). Personal data: never log it.
 */
@Service
class MemberImportService implements MemberImport {

    /** Upper bound of one {@code IN (...)} list. */
    private static final int CHUNK = 500;

    private static final String EMAIL_INDEX = "member_tenant_email_bidx_uq";

    private final MemberRepository members;
    private final SpringValidatorAdapter validator;
    private final MemberAuditPublisher auditPublisher;
    private final MemberCrypto crypto;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    MemberImportService(MemberRepository members, Validator validator, MemberAuditPublisher audit,
            MemberCrypto crypto, Clock clock) {
        this.members = members;
        this.validator = new SpringValidatorAdapter(validator);
        this.auditPublisher = audit;
        this.crypto = crypto;
        this.clock = clock;
    }

    /** An active member with its opened contacts. */
    private record Existing(Member member, OpenedContacts contacts) {

        UUID id() {
            return member.getId();
        }
    }

    /** What is known while the rows are walked in file order. */
    private record Context(Map<String, Existing> byEmail, Map<String, Map<UUID, Existing>> byPhone,
            Set<String> seenKeys, Set<String> seenEmails, Set<List<String>> seenNamePhones, Set<UUID> matched) {
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MemberImportPlan plan(UUID tenantId, UUID importId, List<MemberImportRow> rows) {
        Context context = new Context(activeByEmail(tenantId, rows), activeByPhone(tenantId, rows),
                new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
        List<MemberImportItem> items = new ArrayList<>(rows.size());
        for (MemberImportRow row : rows) {
            items.add(planRow(row, importId, context));
        }
        return new MemberImportPlan(items);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(MemberImportPlan plan) {
        List<UUID> ids = plan.items().stream()
                .filter(item -> item.action() == MemberImportAction.UPDATE
                        || item.action() == MemberImportAction.UNCHANGED)
                .map(MemberImportItem::memberId)
                .sorted(UuidOrder.POSTGRES)
                .toList();
        // ids are sorted as a whole and each chunk is read in id order, so rows are locked in id order
        for (List<UUID> chunk : chunks(ids)) {
            members.lockAllForUpdate(chunk);
        }
    }

    /**
     * New members are persisted with the ids of the plan (no SELECT per row, inserts batched by Hibernate); matched
     * members are loaded with a few bulk queries. A row whose outcome differs from the plan, or a unique violation of
     * the e-mail index at the final flush, means the data changed since the plan: 409 {@code IMPORT_STALE}.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AuditEvent> apply(UUID tenantId, List<MemberImportRow> rows, MemberImportPlan plan, Actor actor) {
        Instant now = clock.instant();
        List<AuditEvent> entries = new ArrayList<>();
        // the phone index refers to the member rows, so it is written after the final flush
        Map<Member, List<String>> phoneIndex = new LinkedHashMap<>();
        Map<UUID, Existing> matched = loadMatched(plan);
        for (int index = 0; index < rows.size(); index++) {
            MemberImportItem item = plan.items().get(index);
            MemberImportAction action = item.action();
            if (action == MemberImportAction.NEW) {
                MemberData data = dataOf(rows.get(index), null);
                Member member = Member.create(item.memberId(), tenantId, data, now);
                OpenedContacts contacts = crypto.seal(member, OpenedContacts.NONE, data);
                entityManager.persist(member);
                if (!contacts.phones().isEmpty()) {
                    phoneIndex.put(member, contacts.phones());
                }
                entries.add(auditPublisher.event(tenantId, actor, "MEMBER_CREATED", member,
                        MemberSnapshot.diff(MemberSnapshot.NONE, MemberSnapshot.of(member, contacts))));
            } else if (action == MemberImportAction.UPDATE) {
                Existing existing = matched.get(item.memberId());
                if (existing == null || existing.member().isArchived()) {
                    throw stale();
                }
                Member member = existing.member();
                MemberData data = dataOf(rows.get(index), existing);
                MemberSnapshot before = MemberSnapshot.of(member, existing.contacts());
                member.apply(data);
                OpenedContacts contacts = crypto.seal(member, existing.contacts(), data);
                List<FieldChange> changes = MemberSnapshot.diff(before, MemberSnapshot.of(member, contacts));
                if (!changes.isEmpty()) {
                    member.touch(now);
                    entries.add(auditPublisher.event(tenantId, actor, "MEMBER_UPDATED", member, changes));
                }
                if (!contacts.phones().equals(existing.contacts().phones())) {
                    phoneIndex.put(member, contacts.phones());
                }
            }
        }
        try {
            members.flush();
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, EMAIL_INDEX)) {
                throw stale();
            }
            throw e;
        }
        phoneIndex.forEach(crypto::replacePhoneIndex);
        return entries;
    }

    /** The members the plan updates, in one bulk query per chunk (already managed when they were locked), opened. */
    private Map<UUID, Existing> loadMatched(MemberImportPlan plan) {
        List<UUID> ids = plan.items().stream()
                .filter(item -> item.action() == MemberImportAction.UPDATE)
                .map(MemberImportItem::memberId)
                .toList();
        Map<UUID, Existing> byId = new HashMap<>();
        for (List<UUID> chunk : chunks(ids)) {
            for (Member member : members.findAllById(chunk)) {
                byId.put(member.getId(), new Existing(member, crypto.open(member)));
            }
        }
        return byId;
    }

    /** The member data of a row, with the same overlay and rules as the plan; a problem here means the plan is stale. */
    private MemberData dataOf(MemberImportRow row, @Nullable Existing existing) {
        List<FieldProblem> problems = new ArrayList<>();
        MemberData.Validation validation = validate(row, existing, problems);
        if (!onePerField(problems).isEmpty() || validation.data() == null) {
            throw stale();
        }
        return validation.data();
    }

    private static ApiProblemException stale() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.IMPORT_STALE,
                "The members changed after the preview; upload the file again.");
    }

    private MemberImportItem planRow(MemberImportRow row, UUID importId, Context context) {
        String key = blankToNull(row.externalId());
        String email = lowerOrNull(row.email());
        String lastName = blankToNull(row.lastName());
        String firstName = blankToNull(row.firstName());
        String firstPhone = row.phones().isEmpty() ? null : normalisedPhone(row.phones().get(0));
        List<String> nameAndPhone = lastName == null || firstName == null || firstPhone == null ? null
                : List.of(lower(lastName), lower(firstName), firstPhone);

        List<FieldProblem> duplicates = new ArrayList<>();
        if (key != null && !context.seenKeys().add(lower(key))) {
            duplicates.add(new FieldProblem("externalId", ProblemCodes.DUPLICATE_IN_FILE));
        }
        if (email != null && !context.seenEmails().add(email)) {
            duplicates.add(new FieldProblem("email", ProblemCodes.DUPLICATE_IN_FILE));
        }
        if (nameAndPhone != null && !context.seenNamePhones().add(nameAndPhone)) {
            duplicates.add(new FieldProblem("phones", ProblemCodes.DUPLICATE_IN_FILE));
        }
        if (!duplicates.isEmpty()) {
            return problem(row, duplicates);
        }

        List<FieldProblem> problems = new ArrayList<>();
        if (key == null) {
            problems.add(new FieldProblem("externalId", ProblemCodes.REQUIRED));
        }
        Existing existing = match(row, email, nameAndPhone, context, problems);
        if (existing != null && !context.matched().add(existing.id())) {
            // another row already stands for this member
            problems.add(new FieldProblem(email != null ? "email" : "phones", ProblemCodes.DUPLICATE_IN_FILE));
            existing = null;
        }

        MemberData.Validation validation = validate(row, existing, problems);
        List<FieldProblem> distinct = onePerField(problems);
        if (!distinct.isEmpty()) {
            return problem(row, distinct);
        }
        MemberData data = Objects.requireNonNull(validation.data());
        if (existing == null) {
            return new MemberImportItem(row.row(), MemberImportAction.NEW, newId(importId, key), List.of(), List.of());
        }
        List<String> changed = MemberSnapshot.diff(MemberSnapshot.of(existing.member(), existing.contacts()),
                        MemberSnapshot.of(data)).stream()
                .map(FieldChange::field)
                .toList();
        return new MemberImportItem(row.row(),
                changed.isEmpty() ? MemberImportAction.UNCHANGED : MemberImportAction.UPDATE,
                existing.id(), changed, List.of());
    }

    /**
     * The member the row stands for, or null for a new one. Adds the matching problems ({@code MEMBER_KEY_MISSING},
     * {@code MEMBER_AMBIGUOUS}, {@code EMAIL_TAKEN}) to {@code problems}.
     */
    private static @Nullable Existing match(MemberImportRow row, @Nullable String email,
            @Nullable List<String> nameAndPhone, Context context, List<FieldProblem> problems) {
        List<Existing> sameNameAndPhone = nameAndPhone == null ? List.of()
                : context.byPhone().getOrDefault(nameAndPhone.get(2), Map.of()).values().stream()
                        .filter(existing -> lower(existing.member().getLastName()).equals(nameAndPhone.get(0))
                                && lower(existing.member().getFirstName()).equals(nameAndPhone.get(1)))
                        .toList();
        if (email != null) {
            Existing byEmail = context.byEmail().get(email);
            if (byEmail != null && !sameNameAndPhone.isEmpty() && !sameNameAndPhone.contains(byEmail)) {
                problems.add(new FieldProblem("email", ProblemCodes.EMAIL_TAKEN));
                return null;
            }
            return byEmail;
        }
        if (row.phones().isEmpty()) {
            problems.add(new FieldProblem("email", ProblemCodes.MEMBER_KEY_MISSING));
            return null;
        }
        if (sameNameAndPhone.size() > 1) {
            problems.add(new FieldProblem("phones", ProblemCodes.MEMBER_AMBIGUOUS));
            return null;
        }
        return sameNameAndPhone.isEmpty() ? null : sameNameAndPhone.get(0);
    }

    /**
     * Applies the blank-keeps-existing rule, then the rules of {@link MemberData} and of the API contract. Problems
     * are added to {@code problems}; the data is present only when the member rules found none.
     */
    private MemberData.Validation validate(MemberImportRow row, @Nullable Existing existing,
            List<FieldProblem> problems) {
        Member member = existing == null ? null : existing.member();
        OpenedContacts stored = existing == null ? OpenedContacts.NONE : existing.contacts();
        String lastName = firstNonBlank(row.lastName(), member == null ? null : member.getLastName());
        String firstName = firstNonBlank(row.firstName(), member == null ? null : member.getFirstName());
        String middleName = firstNonBlank(row.middleName(), member == null ? null : member.getMiddleName());
        List<String> phones = row.phones().isEmpty() ? stored.phones() : row.phones();
        String email = firstNonBlank(row.email(), stored.email());
        String address = firstNonBlank(row.postalAddress(), stored.postalAddress());
        String telegram = firstNonBlank(row.telegramUsername(), stored.telegramUsername());
        String viber = firstNonBlank(row.viberPhone(), stored.viberPhone());
        String notes = stored.notes();

        DeliveryChannel channel = member == null ? DeliveryChannel.PAPER : member.getDeliveryChannel();
        String channelText = blankToNull(row.deliveryChannel());
        if (channelText != null) {
            DeliveryChannel parsed = parseChannel(channelText);
            if (parsed == null) {
                problems.add(new FieldProblem("deliveryChannel", ProblemCodes.INVALID));
            } else {
                channel = parsed;
            }
        }

        MemberData.Validation validation = MemberData.tryFrom(lastName, firstName, middleName, phones, email,
                address, channel, telegram, viber, notes);
        problems.addAll(validation.problems());
        problems.addAll(contractProblems(lastName, firstName, middleName, phones, email, address, telegram, viber));
        return validation;
    }

    /** The constraints of {@code MemberInput} (lengths, e-mail format), reported with the same codes as the API. */
    private List<FieldProblem> contractProblems(@Nullable String lastName, @Nullable String firstName,
            @Nullable String middleName, List<String> phones, @Nullable String email, @Nullable String address,
            @Nullable String telegram, @Nullable String viber) {
        MemberInput input = new MemberInput();
        input.setLastName(lastName);
        input.setFirstName(firstName);
        input.setMiddleName(middleName);
        input.setPhones(new ArrayList<>(phones));
        input.setEmail(email);
        input.setPostalAddress(address);
        input.setTelegramUsername(telegram);
        input.setViberPhone(viber);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(input, "member");
        validator.validate(input, errors);
        return errors.getFieldErrors().stream()
                .map(ValidationProblems::fieldProblem)
                .toList();
    }

    /** The active members with one of the rows' e-mails, by that (lower-case) e-mail. */
    private Map<String, Existing> activeByEmail(UUID tenantId, List<MemberImportRow> rows) {
        Map<ByteBuffer, String> wanted = new LinkedHashMap<>();
        for (MemberImportRow row : rows) {
            String email = lowerOrNull(row.email());
            if (email != null) {
                wanted.putIfAbsent(ByteBuffer.wrap(crypto.index(tenantId, IndexKind.EMAIL, email)), email);
            }
        }
        Map<String, Existing> byEmail = new HashMap<>();
        for (List<ByteBuffer> chunk : chunks(List.copyOf(wanted.keySet()))) {
            for (Member member : members.findActiveByEmailBidxIn(tenantId, bytes(chunk))) {
                byEmail.put(wanted.get(ByteBuffer.wrap(member.getEmailBidx())),
                        new Existing(member, crypto.open(member)));
            }
        }
        return byEmail;
    }

    /** The active members that have one of the rows' first phones, indexed by that phone. */
    private Map<String, Map<UUID, Existing>> activeByPhone(UUID tenantId, List<MemberImportRow> rows) {
        Map<ByteBuffer, String> wanted = new LinkedHashMap<>();
        for (MemberImportRow row : rows) {
            String phone = row.phones().isEmpty() ? null : normalisedPhone(row.phones().get(0));
            if (phone != null) {
                wanted.putIfAbsent(ByteBuffer.wrap(crypto.index(tenantId, IndexKind.PHONE, phone)), phone);
            }
        }
        Set<String> phones = Set.copyOf(wanted.values());
        Map<String, Map<UUID, Existing>> byPhone = new HashMap<>();
        for (List<ByteBuffer> chunk : chunks(List.copyOf(wanted.keySet()))) {
            for (Member member : members.findActiveByPhoneBidxIn(tenantId, bytes(chunk))) {
                Existing existing = new Existing(member, crypto.open(member));
                for (String phone : existing.contacts().phones()) {
                    String normalised = PhoneIndexValues.normalisedPhone(phone);
                    if (phones.contains(normalised)) {
                        byPhone.computeIfAbsent(normalised, p -> new LinkedHashMap<>()).put(member.getId(), existing);
                    }
                }
            }
        }
        return byPhone;
    }

    private static List<byte[]> bytes(List<ByteBuffer> buffers) {
        return buffers.stream().map(ByteBuffer::array).toList();
    }

    private static MemberImportItem problem(MemberImportRow row, List<FieldProblem> problems) {
        return new MemberImportItem(row.row(), null, null, List.of(), problems);
    }

    /**
     * One problem per field, the first one found, named by the field without an index ({@code phones[1]} and
     * {@code phones[0].<list element>} are both {@code phones}).
     */
    private static List<FieldProblem> onePerField(List<FieldProblem> problems) {
        Map<String, FieldProblem> byField = new LinkedHashMap<>();
        for (FieldProblem problem : problems) {
            String field = baseField(problem.field());
            byField.putIfAbsent(field, new FieldProblem(field, problem.code()));
        }
        return List.copyOf(byField.values());
    }

    private static String baseField(String field) {
        int cut = field.length();
        for (char separator : new char[] {'[', '.'}) {
            int at = field.indexOf(separator);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        return field.substring(0, cut);
    }

    private static @Nullable String normalisedPhone(String raw) {
        return PhoneNumber.parse(raw).map(PhoneNumber::value).orElse(null);
    }

    private static @Nullable DeliveryChannel parseChannel(String text) {
        try {
            return DeliveryChannel.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A new member gets the same id on every planning of the same import, so a re-plan equals the preview. */
    private static UUID newId(UUID importId, @Nullable String key) {
        return UUID.nameUUIDFromBytes((importId + "/member/" + lower(Objects.requireNonNull(key)))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String lower(String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String lowerOrNull(@Nullable String text) {
        String value = blankToNull(text);
        return value == null ? null : lower(value);
    }

    private static @Nullable String firstNonBlank(@Nullable String preferred, @Nullable String fallback) {
        String value = blankToNull(preferred);
        return value != null ? value : fallback;
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static <T> List<List<T>> chunks(List<T> list) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < list.size(); from += CHUNK) {
            chunks.add(list.subList(from, Math.min(list.size(), from + CHUNK)));
        }
        return chunks;
    }
}
