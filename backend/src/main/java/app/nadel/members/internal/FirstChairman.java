package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.members.internal.MemberAccessState.Row;
import app.nadel.privacy.IndexKind;
import app.nadel.tenancy.TenantContext;
import app.nadel.tenancy.TenantProvisioning;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nadel.contract.model.MemberAccessStatus;
import nadel.contract.model.MemberInput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The work of the first-chairman command (feature 020, technical considerations 2.1; run by
 * {@link FirstChairmanCommand}): a new cooperative and its first chairman, invited with a one-time link.
 * <ol>
 *   <li>The cooperative id is {@code UUID.nameUUIDFromBytes("nadel:cooperative:" + e-mail)} (the e-mail trimmed and
 *       lower-cased), so the same arguments always name the same cooperative and a second run creates nothing.
 *       {@link TenantProvisioning#ensure} creates it with its base roles when it does not exist.</li>
 *   <li>With the cooperative bound ({@code TenantContext.runAs}), one transaction finds the active member with the
 *       e-mail (blind index) or creates it through {@link MemberCommands#create} with the names and the e-mail and gives
 *       it the Chairman role through {@link MemberRoleCommands#setRoles}: audited as {@code MEMBER_CREATED} and
 *       {@code MEMBER_ROLES_CHANGED} by the system actor {@value #ACTOR} (no member, so the audit page shows
 *       «Система»). An active member with the e-mail who does not hold the Chairman role is refused
 *       ({@code MEMBER_NOT_CHAIRMAN}): the board gives roles in the application, the command never does.</li>
 *   <li>The access decides the rest: {@code ACTIVE} prints that there is nothing to do; {@code INVITED} prints the
 *       invitation date and issues nothing unless {@code resend}; otherwise (and for {@code INVITED} with
 *       {@code resend}) {@link MemberInvitations#issueForCommand} invites, audited as {@code MEMBER_INVITED} (or
 *       {@code MEMBER_INVITE_RESENT}) by the same actor.</li>
 * </ol>
 * Without a mailbox (a new cooperative has none) the link is printed to {@code out} exactly once, by
 * {@code println}, never by a logger, never into the audit log or the outbox; with a mailbox the letter goes out as
 * for the board's invitation. Logs carry ids only; the printed text carries no personal data except the link itself.
 */
@Service
class FirstChairman {

    /** The audit subject of everything the command writes. */
    static final String ACTOR = "system:first-chairman";

    /** The longest cooperative name the command accepts (the wrapper script checks the same). */
    static final int MAX_COOPERATIVE_NAME = 200;

    private static final Logger log = LoggerFactory.getLogger(FirstChairman.class);
    private static final DateTimeFormatter UTC_MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final TenantProvisioning provisioning;
    private final MemberRepository members;
    private final RoleRepository roles;
    private final RoleGrantReader grants;
    private final MemberCrypto crypto;
    private final MemberCommands memberCommands;
    private final MemberRoleCommands roleCommands;
    private final MemberAccessState access;
    private final MemberInvitations invitations;
    private final Validator validator;
    private final TransactionTemplate reading;
    private final TransactionTemplate writing;

    FirstChairman(TenantProvisioning provisioning, MemberRepository members, RoleRepository roles,
            RoleGrantReader grants, MemberCrypto crypto, MemberCommands memberCommands,
            MemberRoleCommands roleCommands, MemberAccessState access, MemberInvitations invitations,
            Validator validator, PlatformTransactionManager transactionManager) {
        this.provisioning = provisioning;
        this.members = members;
        this.roles = roles;
        this.grants = grants;
        this.crypto = crypto;
        this.memberCommands = memberCommands;
        this.roleCommands = roleCommands;
        this.access = access;
        this.invitations = invitations;
        this.validator = validator;
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        this.writing = new TransactionTemplate(transactionManager);
    }

    /** The command's arguments ({@code nadel.first-chairman.*}). Personal data: {@link #toString()} hides it. */
    record Arguments(String cooperative, String email, String lastName, String firstName, boolean resend) {

        @Override
        public String toString() {
            return "Arguments[resend=" + resend + "]";
        }
    }

    /** What the access of the chairman was, and what the command did about it. */
    enum Outcome {
        /** The chairman has access already: nothing issued. */
        ALREADY_ACTIVE,
        /** The chairman is invited and no resend was asked: nothing issued. */
        ALREADY_INVITED,
        /** A new link was printed (no mailbox). */
        LINK_PRINTED,
        /** A new link was sent by letter (the cooperative has a mailbox). */
        LETTER_SENT
    }

    /**
     * What a run did.
     *
     * @param tenantId           the cooperative
     * @param memberId           the chairman
     * @param cooperativeCreated whether this run created the cooperative
     * @param chairmanCreated    whether this run created the chairman member
     */
    record Result(UUID tenantId, UUID memberId, boolean cooperativeCreated, boolean chairmanCreated, Outcome outcome) {
    }

    /** A failure the command reports by its code (and a text without personal data) and a non-zero exit code. */
    static final class Failure extends RuntimeException {

        private final String code;
        private final boolean invalidArguments;

        Failure(String code, String message, boolean invalidArguments) {
            super(message, null, false, false);
            this.code = code;
            this.invalidArguments = invalidArguments;
        }

        String code() {
            return code;
        }

        boolean invalidArguments() {
            return invalidArguments;
        }
    }

    /** The cooperative of a chairman's e-mail: the same e-mail, in any case, always gives the same id. */
    static UUID tenantIdOf(String email) {
        String normalised = Objects.requireNonNull(PhoneIndexValues.normalisedEmail(email), "email");
        return UUID.nameUUIDFromBytes(("nadel:cooperative:" + normalised).getBytes(StandardCharsets.UTF_8));
    }

    /** Runs the command (see the class comment); throws {@link Failure} or the problem of a refused step. */
    Result run(Arguments arguments, PrintStream out) {
        MemberInput input = validated(arguments);
        String email = Objects.requireNonNull(PhoneIndexValues.normalisedEmail(input.getEmail()));
        UUID tenantId = tenantIdOf(email);
        boolean cooperativeCreated = provisioning.ensure(tenantId, arguments.cooperative().strip());
        out.println((cooperativeCreated ? "Cooperative created: " : "Cooperative exists: ") + tenantId);
        return TenantContext.runAs(tenantId, () -> {
            AccessProfile system = systemActor(tenantId);
            Chairman chairman = Objects.requireNonNull(writing.execute(status -> chairman(tenantId, email, input,
                    system)));
            out.println((chairman.created() ? "Chairman created: " : "Chairman exists: ") + chairman.memberId());
            Outcome outcome = invite(chairman.memberId(), system, arguments.resend(), out);
            log.info("First chairman command: cooperative {} (created {}), member {} (created {}), {}", tenantId,
                    cooperativeCreated, chairman.memberId(), chairman.created(), outcome);
            return new Result(tenantId, chairman.memberId(), cooperativeCreated, chairman.created(), outcome);
        });
    }

    /**
     * The names and the e-mail as the member form takes them, checked with the contract's constraints of
     * {@code MemberInput} (names 1..100, the e-mail format and length); the e-mail is required here (it is the login),
     * the cooperative name too (at most {@value #MAX_COOPERATIVE_NAME} characters). The failure names the fields only.
     */
    private MemberInput validated(Arguments arguments) {
        Set<String> invalid = new TreeSet<>();
        String cooperative = strip(arguments.cooperative());
        if (cooperative == null || cooperative.isEmpty() || cooperative.length() > MAX_COOPERATIVE_NAME) {
            invalid.add("cooperative");
        }
        MemberInput input = new MemberInput(strip(arguments.lastName()), strip(arguments.firstName()))
                .email(strip(arguments.email()));
        if (input.getEmail() == null || input.getEmail().isEmpty()) {
            invalid.add("email");
        }
        for (ConstraintViolation<MemberInput> violation : validator.validate(input)) {
            invalid.add(violation.getPropertyPath().toString());
        }
        if (!invalid.isEmpty()) {
            throw new Failure("INVALID_ARGUMENTS", "Missing or invalid: " + String.join(", ", invalid), true);
        }
        return input;
    }

    /** Step 2, in one transaction: the active member with the e-mail, or a new one with the Chairman role. */
    private Chairman chairman(UUID tenantId, String email, MemberInput input, AccessProfile system) {
        byte[] emailBidx = crypto.index(tenantId, IndexKind.EMAIL, email);
        Optional<Member> found = members.findActiveByEmailBidx(tenantId, emailBidx);
        if (found.isPresent()) {
            if (grants.read(found.get()).stream().noneMatch(RoleGrant::isChairman)) {
                throw new Failure("MEMBER_NOT_CHAIRMAN", "An active member with this e-mail exists without the "
                        + "Chairman role; the board gives roles in the application.", false);
            }
            return new Chairman(found.get().getId(), false);
        }
        Role chairmanRole = roles.findAllOf(tenantId).stream()
                .filter(role -> PermissionUnion.CHAIRMAN.equals(role.getBaseCode()))
                .findFirst()
                .orElseThrow(() -> new Failure("CHAIRMAN_ROLE_MISSING", "The cooperative has no Chairman role.",
                        false));
        UUID memberId = memberCommands.create(input, system).getId();
        roleCommands.setRoles(memberId, Set.of(chairmanRole.getId()), system);
        return new Chairman(memberId, true);
    }

    /** Step 3: what the chairman's access asks for. */
    private Outcome invite(UUID memberId, AccessProfile system, boolean resend, PrintStream out) {
        Access current = Objects.requireNonNull(reading.execute(status -> {
            Member member = members.findById(memberId).orElseThrow();
            Optional<Row> row = access.find(memberId);
            return new Access(MemberAccessState.status(member, row), row.map(Row::invitedAt).orElse(null));
        }));
        if (current.status() == MemberAccessStatus.ACTIVE) {
            out.println("The chairman already has access to the system. Nothing to do.");
            return Outcome.ALREADY_ACTIVE;
        }
        if (current.status() == MemberAccessStatus.INVITED && !resend) {
            out.println("The chairman was invited on "
                    + (current.invitedAt() == null ? "an unknown date" : UTC_MINUTE.format(current.invitedAt()))
                    + ". No new link was issued; run again with --resend to issue one (the previous link then "
                    + "stops working).");
            return Outcome.ALREADY_INVITED;
        }
        AtomicInteger printed = new AtomicInteger();
        invitations.issueForCommand(memberId, system, current.status() == MemberAccessStatus.INVITED,
                (link, expiresAt) -> {
                    if (printed.getAndIncrement() > 0) {
                        throw new IllegalStateException("A link is printed once");
                    }
                    out.println("One-time set-password link for the chairman, valid until "
                            + UTC_MINUTE.format(expiresAt) + ". It is shown only this once; pass it on privately:");
                    out.println(link);
                });
        if (printed.get() > 0) {
            return Outcome.LINK_PRINTED;
        }
        out.println("The invitation letter was sent to the chairman's e-mail.");
        return Outcome.LETTER_SENT;
    }

    /** The system actor: no member, no permissions, the audit subject {@value #ACTOR}. */
    private static AccessProfile systemActor(UUID tenantId) {
        return new AccessProfile(tenantId, null, ACTOR, "", Set.of(), List.of(), false);
    }

    private static @Nullable String strip(@Nullable String value) {
        return value == null ? null : value.strip();
    }

    private record Chairman(UUID memberId, boolean created) {
    }

    private record Access(MemberAccessStatus status, @Nullable Instant invitedAt) {
    }
}
