package app.nadel.members.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.FieldChange;
import app.nadel.members.MemberImport;
import app.nadel.members.MemberImportAction;
import app.nadel.members.MemberImportItem;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.problem.ValidationProblems;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validator;
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
 * Plans the member rows of an Excel import. Reads with a few bulk queries (active members by e-mail, active members by
 * phone), never one query per row, and writes nothing.
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

    private static final String EMAIL_INDEX = "member_tenant_email_uq";

    private final MemberRepository members;
    private final SpringValidatorAdapter validator;
    private final MemberAuditPublisher audit;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    MemberImportService(MemberRepository members, Validator validator, MemberAuditPublisher audit, Clock clock) {
        this.members = members;
        this.validator = new SpringValidatorAdapter(validator);
        this.audit = audit;
        this.clock = clock;
    }

    /** What is known while the rows are walked in file order. */
    private record Context(Map<String, Member> byEmail, Map<String, Map<UUID, Member>> byPhone,
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
                .sorted()
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
    public void apply(UUID tenantId, List<MemberImportRow> rows, MemberImportPlan plan, Actor actor) {
        Instant now = clock.instant();
        Map<UUID, Member> matched = loadMatched(plan);
        for (int index = 0; index < rows.size(); index++) {
            MemberImportItem item = plan.items().get(index);
            MemberImportAction action = item.action();
            if (action == MemberImportAction.NEW) {
                MemberData data = dataOf(rows.get(index), null);
                Member member = Member.create(item.memberId(), tenantId, data, now);
                entityManager.persist(member);
                audit.publish(tenantId, actor, "MEMBER_CREATED", member,
                        MemberSnapshot.diff(MemberSnapshot.NONE, MemberSnapshot.of(member)));
            } else if (action == MemberImportAction.UPDATE) {
                Member member = matched.get(item.memberId());
                if (member == null || member.isArchived()) {
                    throw stale();
                }
                MemberData data = dataOf(rows.get(index), member);
                MemberSnapshot before = MemberSnapshot.of(member);
                member.apply(data);
                List<FieldChange> changes = MemberSnapshot.diff(before, MemberSnapshot.of(member));
                if (!changes.isEmpty()) {
                    member.touch(now);
                    audit.publish(tenantId, actor, "MEMBER_UPDATED", member, changes);
                }
            }
        }
        try {
            members.flush();
        } catch (DataIntegrityViolationException e) {
            if (mentions(e, EMAIL_INDEX)) {
                throw stale();
            }
            throw e;
        }
    }

    /** The members the plan updates, in one bulk query per chunk (already managed when they were locked). */
    private Map<UUID, Member> loadMatched(MemberImportPlan plan) {
        List<UUID> ids = plan.items().stream()
                .filter(item -> item.action() == MemberImportAction.UPDATE)
                .map(MemberImportItem::memberId)
                .toList();
        Map<UUID, Member> byId = new HashMap<>();
        for (List<UUID> chunk : chunks(ids)) {
            for (Member member : members.findAllById(chunk)) {
                byId.put(member.getId(), member);
            }
        }
        return byId;
    }

    /** The member data of a row, with the same overlay and rules as the plan; a problem here means the plan is stale. */
    private MemberData dataOf(MemberImportRow row, @Nullable Member existing) {
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

    private static boolean mentions(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && constraint.equals(cve.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
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
        Member existing = match(row, email, nameAndPhone, context, problems);
        if (existing != null && !context.matched().add(existing.getId())) {
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
        List<String> changed = MemberSnapshot.diff(MemberSnapshot.of(existing), MemberSnapshot.of(data)).stream()
                .map(FieldChange::field)
                .toList();
        return new MemberImportItem(row.row(),
                changed.isEmpty() ? MemberImportAction.UNCHANGED : MemberImportAction.UPDATE,
                existing.getId(), changed, List.of());
    }

    /**
     * The member the row stands for, or null for a new one. Adds the matching problems ({@code MEMBER_KEY_MISSING},
     * {@code MEMBER_AMBIGUOUS}, {@code EMAIL_TAKEN}) to {@code problems}.
     */
    private static @Nullable Member match(MemberImportRow row, @Nullable String email,
            @Nullable List<String> nameAndPhone, Context context, List<FieldProblem> problems) {
        List<Member> sameNameAndPhone = nameAndPhone == null ? List.of()
                : context.byPhone().getOrDefault(nameAndPhone.get(2), Map.of()).values().stream()
                        .filter(member -> lower(member.getLastName()).equals(nameAndPhone.get(0))
                                && lower(member.getFirstName()).equals(nameAndPhone.get(1)))
                        .toList();
        if (email != null) {
            Member byEmail = context.byEmail().get(email);
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
    private MemberData.Validation validate(MemberImportRow row, @Nullable Member existing,
            List<FieldProblem> problems) {
        String lastName = firstNonBlank(row.lastName(), existing == null ? null : existing.getLastName());
        String firstName = firstNonBlank(row.firstName(), existing == null ? null : existing.getFirstName());
        String middleName = firstNonBlank(row.middleName(), existing == null ? null : existing.getMiddleName());
        List<String> phones = row.phones().isEmpty() ? (existing == null ? List.of() : existing.getPhones())
                : row.phones();
        String email = firstNonBlank(row.email(), existing == null ? null : existing.getEmail());
        String address = firstNonBlank(row.postalAddress(), existing == null ? null : existing.getPostalAddress());
        String telegram = firstNonBlank(row.telegramUsername(), existing == null ? null : existing.getTelegramUsername());
        String viber = firstNonBlank(row.viberPhone(), existing == null ? null : existing.getViberPhone());
        String notes = existing == null ? null : existing.getNotes();

        DeliveryChannel channel = existing == null ? DeliveryChannel.PAPER : existing.getDeliveryChannel();
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

    private Map<String, Member> activeByEmail(UUID tenantId, List<MemberImportRow> rows) {
        List<String> emails = rows.stream()
                .map(row -> lowerOrNull(row.email()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, Member> byEmail = new HashMap<>();
        for (List<String> chunk : chunks(emails)) {
            for (Member member : members.findActiveByLowerEmails(tenantId, chunk)) {
                byEmail.put(lower(member.getEmail()), member);
            }
        }
        return byEmail;
    }

    /** The active members that have one of the rows' first phones, indexed by that phone. */
    private Map<String, Map<UUID, Member>> activeByPhone(UUID tenantId, List<MemberImportRow> rows) {
        Set<String> wanted = new LinkedHashSet<>();
        for (MemberImportRow row : rows) {
            String phone = row.phones().isEmpty() ? null : normalisedPhone(row.phones().get(0));
            if (phone != null) {
                wanted.add(phone);
            }
        }
        Map<String, Map<UUID, Member>> byPhone = new HashMap<>();
        for (List<String> chunk : chunks(List.copyOf(wanted))) {
            for (Member member : members.findActiveByAnyPhone(tenantId, chunk)) {
                for (String phone : member.getPhones()) {
                    if (wanted.contains(phone)) {
                        byPhone.computeIfAbsent(phone, p -> new LinkedHashMap<>()).put(member.getId(), member);
                    }
                }
            }
        }
        return byPhone;
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
