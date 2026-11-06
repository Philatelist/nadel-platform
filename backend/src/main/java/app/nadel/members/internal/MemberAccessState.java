package app.nadel.members.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nadel.contract.model.InviteBlockedReason;
import nadel.contract.model.MemberAccessStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;

/**
 * The stored access of a member to the system (feature 020, table {@code member_access}): {@code NONE},
 * {@code INVITED}, {@code ACTIVE} or {@code DISABLED} with the time of each transition. Kept in the database so the
 * member card and the sign-in never ask Keycloak.
 * <p>
 * A member without a row was never invited ({@code NOT_INVITED}), except a member already linked to a Keycloak subject
 * by the sign-in of an account made by hand before feature 020 (the demo users, the legacy path of
 * {@link MemberAccessProfileSource}): that member has access ({@code ACTIVE}). The migration wrote {@code ACTIVE} rows
 * for the members linked at that time; an invitation always writes a row before it links a subject, so the derived
 * value never hides an invitation in progress.
 * <p>
 * Runs in the caller's transaction (RLS limits the rows to the bound cooperative). The application may not delete
 * rows; a transition is an upsert.
 */
@Component
class MemberAccessState {

    /** The stored states ({@code member_access.state}). */
    enum State {
        NONE, INVITED, ACTIVE, DISABLED
    }

    /** One {@code member_access} row. */
    record Row(State state, @Nullable Instant invitedAt, @Nullable Instant activatedAt, @Nullable Instant disabledAt,
            @Nullable Instant lastSignInAt) {
    }

    private final JdbcTemplate jdbc;

    MemberAccessState(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The member's row; empty when it has none. */
    Optional<Row> find(UUID memberId) {
        List<Row> rows = jdbc.query("select state, invited_at, activated_at, disabled_at, last_sign_in_at "
                + "from member_access where member_id = ?", (rs, n) -> row(rs), memberId);
        return rows.stream().findFirst();
    }

    /**
     * Writes a transition: the new state and, for every state but {@code NONE}, the time of the transition in its column
     * ({@code invited_at}, {@code activated_at}, {@code disabled_at}); the other times stay.
     */
    void set(UUID tenantId, UUID memberId, State state, Instant at) {
        OffsetDateTime time = at.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                insert into member_access (member_id, tenant_id, state, invited_at, activated_at, disabled_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (member_id) do update set state = excluded.state,
                    invited_at = coalesce(excluded.invited_at, member_access.invited_at),
                    activated_at = coalesce(excluded.activated_at, member_access.activated_at),
                    disabled_at = coalesce(excluded.disabled_at, member_access.disabled_at),
                    updated_at = excluded.updated_at
                """, memberId, tenantId, state.name(), timeIf(state == State.INVITED, time),
                timeIf(state == State.ACTIVE, time), timeIf(state == State.DISABLED, time), time);
    }

    /**
     * Records a sign-in of a member whose row is {@code ACTIVE}, at most once per interval: only when the stored last
     * sign-in is missing or before {@code dueBefore}. A member without a row (linked by the legacy sign-in) gets none.
     * Answers whether the row was written.
     */
    boolean recordSignIn(UUID memberId, Instant at, Instant dueBefore) {
        return jdbc.update("update member_access set last_sign_in_at = ? where member_id = ? and state = 'ACTIVE' "
                + "and (last_sign_in_at is null or last_sign_in_at < ?)", at.atOffset(ZoneOffset.UTC), memberId,
                dueBefore.atOffset(ZoneOffset.UTC)) > 0;
    }

    /**
     * The {@code access} of the member card and of the invitation endpoints. {@code inviteBlockedBy} is set while an
     * invitation (or a new one) is possible by the status but cannot be sent now: no e-mail (it is the login) before no
     * mailbox. {@code mailboxReady} is asked only when the member has an e-mail.
     */
    nadel.contract.model.MemberAccess view(Member member, boolean hasEmail, MailboxCheck mailboxReady) {
        Optional<Row> row = find(member.getId());
        MemberAccessStatus status = status(member, row);
        var access = new nadel.contract.model.MemberAccess(status);
        row.ifPresent(r -> {
            access.setInvitedAt(utc(r.invitedAt()));
            access.setLastSignInAt(utc(r.lastSignInAt()));
            if (status == MemberAccessStatus.DISABLED) {
                access.setDisabledAt(utc(r.disabledAt()));
            }
        });
        if (status != MemberAccessStatus.ACTIVE) {
            if (!hasEmail) {
                access.setInviteBlockedBy(InviteBlockedReason.EMAIL_MISSING);
            } else if (!mailboxReady.ready()) {
                access.setInviteBlockedBy(InviteBlockedReason.MAILBOX_NOT_CONFIGURED);
            }
        }
        return access;
    }

    /** Asked lazily by {@link #view}: whether the cooperative's mailbox is set up. */
    @FunctionalInterface
    interface MailboxCheck {
        boolean ready();
    }

    /** The member's access as the API names it (see the class comment for a member without a row). */
    static MemberAccessStatus status(Member member, Optional<Row> row) {
        if (row.isEmpty()) {
            return member.getKeycloakSubject() == null ? MemberAccessStatus.NOT_INVITED : MemberAccessStatus.ACTIVE;
        }
        return switch (row.get().state()) {
            case NONE -> MemberAccessStatus.NOT_INVITED;
            case INVITED -> MemberAccessStatus.INVITED;
            case ACTIVE -> MemberAccessStatus.ACTIVE;
            case DISABLED -> MemberAccessStatus.DISABLED;
        };
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(State.valueOf(rs.getString("state")), instant(rs, "invited_at"), instant(rs, "activated_at"),
                instant(rs, "disabled_at"), instant(rs, "last_sign_in_at"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** A {@code timestamptz} parameter: the time, or a typed null. */
    private static SqlParameterValue timeIf(boolean set, OffsetDateTime time) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, set ? time : null);
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
