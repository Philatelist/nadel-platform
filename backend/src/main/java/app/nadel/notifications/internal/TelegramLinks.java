package app.nadel.notifications.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The links between members and their Telegram chats (feature 017 §2.2), table {@code telegram_link}. The chat id is
 * sealed with the cooperative's data key at {@code FieldRef("telegram_link", "chat_id_enc", linkId)} and found by its
 * blind index ({@code IndexKind.TELEGRAM_CHAT} of the decimal chat id), so the table never holds it in the clear. A
 * member has at most one active link and a chat at most one active link per cooperative; a link is never changed
 * except to end it, once, with a reason ({@link EndReason}).
 * <p>
 * Linking and unlinking are written to the audit log as {@code TELEGRAM_LINKED} / {@code TELEGRAM_UNLINKED} of the
 * member (entity type {@code MEMBER}, label "Last First"), never with the chat id; an unlink has the field
 * {@code endReason}. Every method runs in a transaction of the bound cooperative. Never log a chat id.
 */
@Service
class TelegramLinks {

    static final String TABLE = "telegram_link";
    static final String CHAT_COLUMN = "chat_id_enc";
    static final String LINKED = "TELEGRAM_LINKED";
    static final String UNLINKED = "TELEGRAM_UNLINKED";

    /** Why a link ended. */
    enum EndReason {
        /** The member unlinked it, in the bot or in the account. */
        UNLINKED,
        /** The member blocked the bot (Telegram answered 403). */
        BLOCKED,
        /** The chat was linked to another member, or the member linked another chat. */
        MOVED
    }

    /** What a code did in a chat. */
    sealed interface Redeemed {
    }

    /** The code is unknown, used or expired, or its member is archived: nothing was linked. */
    record Invalid() implements Redeemed {
    }

    /**
     * The chat is linked to another member of the cooperative: nothing was linked, and the code is still unused, until
     * the person confirms they are {@code member}.
     */
    record NeedsConfirmation(MemberRef member) implements Redeemed {
    }

    /** The chat is now linked to {@code member}, the code used up. */
    record Linked(MemberRef member) implements Redeemed {
    }

    private final JdbcTemplate jdbc;
    private final LinkCodes codes;
    private final MemberDirectory members;
    private final PersonalDataCipher cipher;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TelegramLinks(JdbcTemplate jdbc, LinkCodes codes, MemberDirectory members, PersonalDataCipher cipher,
            ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.codes = codes;
        this.members = members;
        this.cipher = cipher;
        this.events = events;
        this.clock = clock;
    }

    /** The member the chat is linked to now. */
    @Transactional(readOnly = true)
    Optional<UUID> memberOfChat(long chatId) {
        return activeByChat(chatIndex(boundTenant(), chatId)).map(ActiveLink::memberId);
    }

    /** Whether the member's Telegram is linked now. */
    @Transactional(readOnly = true)
    boolean linked(UUID memberId) {
        return activeByMember(memberId).isPresent();
    }

    /** Of the given members, those linked now. */
    @Transactional(readOnly = true)
    Set<UUID> linkedAmong(Collection<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        String ids = "{" + String.join(",", memberIds.stream().distinct().map(UUID::toString).toList()) + "}";
        return new HashSet<>(jdbc.queryForList("select member_id from telegram_link "
                + "where ended_at is null and member_id = any(?::uuid[])", UUID.class, ids));
    }

    /**
     * Links the chat with a code (Telegram's {@code /start <code>}). A chat linked to another member of the cooperative
     * moves only when {@code moveConfirmed} (the person answered «Так, це я»); until then the code stays unused. The
     * chat's earlier member and the member's own earlier chat end as {@link EndReason#MOVED}; only the first is audited
     * ({@code TELEGRAM_UNLINKED} of that member), the member of the code stays linked. A chat already linked to the
     * code's member stays as it is, and the code is used up.
     */
    @Transactional
    Redeemed redeem(long chatId, byte[] codeHash, boolean moveConfirmed) {
        UUID tenantId = boundTenant();
        Optional<UUID> holder = codes.holderOf(codeHash);
        if (holder.isEmpty()) {
            return new Invalid();
        }
        MemberRef member = members.find(List.of(holder.get())).get(holder.get());
        if (member == null || member.archived()) {
            return new Invalid();
        }
        byte[] chatIndex = chatIndex(tenantId, chatId);
        Optional<ActiveLink> chatLink = activeByChat(chatIndex);
        boolean otherMember = chatLink.isPresent() && !chatLink.get().memberId().equals(member.id());
        if (otherMember && !moveConfirmed) {
            return new NeedsConfirmation(member);
        }
        if (codes.redeem(codeHash).isEmpty()) {
            return new Invalid();
        }
        if (chatLink.isPresent() && !otherMember) {
            return new Linked(member);
        }
        if (otherMember) {
            end(chatLink.get(), EndReason.MOVED, AccessProfile.ownerOnly(tenantId, member.id()));
        }
        activeByMember(member.id()).ifPresent(own -> end(own, EndReason.MOVED, null));
        UUID linkId = UUID.randomUUID();
        byte[] sealed = cipher.encrypt(tenantId, new FieldRef(TABLE, CHAT_COLUMN, linkId), Long.toString(chatId));
        jdbc.update("insert into telegram_link (id, tenant_id, member_id, chat_id_enc, chat_id_bidx, linked_at) "
                + "values (?, ?, ?, ?, ?, ?)", linkId, tenantId, member.id(), sealed, chatIndex, now());
        audit(tenantId, AccessProfile.ownerOnly(tenantId, member.id()), LINKED, member, List.of());
        return new Linked(member);
    }

    /** Ends the member's link (the account's «Відключити Telegram»); false when nothing was linked. */
    @Transactional
    boolean unlinkMember(UUID memberId, EndReason reason, AccessProfile actor) {
        Optional<ActiveLink> link = activeByMember(memberId);
        link.ifPresent(found -> end(found, reason, actor));
        return link.isPresent();
    }

    /**
     * Ends the link of the chat («Відключити» or {@code /stop} in the bot, or the member blocked the bot), on the
     * member's own behalf; the member it was linked to, or empty when the chat was not linked.
     */
    @Transactional
    Optional<UUID> unlinkChat(long chatId, EndReason reason) {
        UUID tenantId = boundTenant();
        Optional<ActiveLink> link = activeByChat(chatIndex(tenantId, chatId));
        link.ifPresent(found -> end(found, reason, AccessProfile.ownerOnly(tenantId, found.memberId())));
        return link.map(ActiveLink::memberId);
    }

    /** Ends a link once (the conditional update cannot end it twice); audits it unless {@code actor} is null. */
    private void end(ActiveLink link, EndReason reason, @Nullable AccessProfile actor) {
        int ended = jdbc.update("update telegram_link set ended_at = ?, end_reason = ? "
                + "where id = ? and ended_at is null", now(), reason.name(), link.id());
        if (ended == 1 && actor != null) {
            UUID tenantId = boundTenant();
            MemberRef member = members.find(List.of(link.memberId())).get(link.memberId());
            if (member != null) {
                audit(tenantId, actor, UNLINKED, member,
                        List.of(new FieldChange("endReason", null, reason.name())));
            }
        }
    }

    private void audit(UUID tenantId, AccessProfile actor, String action, MemberRef member,
            List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(tenantId, new Actor(actor.memberId(), actor.subject(),
                actor.displayName()), action, "MEMBER", member.id(), member.shortName(), changes));
    }

    private Optional<ActiveLink> activeByChat(byte[] chatIndex) {
        return jdbc.query("select id, member_id from telegram_link where chat_id_bidx = ? and ended_at is null",
                (rs, n) -> new ActiveLink(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), chatIndex)
                .stream().findFirst();
    }

    private Optional<ActiveLink> activeByMember(UUID memberId) {
        return jdbc.query("select id, member_id from telegram_link where member_id = ? and ended_at is null",
                (rs, n) -> new ActiveLink(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), memberId)
                .stream().findFirst();
    }

    private byte[] chatIndex(UUID tenantId, long chatId) {
        return cipher.blindIndex(tenantId, IndexKind.TELEGRAM_CHAT, Long.toString(chatId));
    }

    private OffsetDateTime now() {
        Instant now = clock.instant();
        return now.atOffset(ZoneOffset.UTC);
    }

    private static UUID boundTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative is bound"));
    }

    private record ActiveLink(UUID id, UUID memberId) {
    }
}
