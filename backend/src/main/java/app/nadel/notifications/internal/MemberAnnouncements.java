package app.nadel.notifications.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.notifications.internal.AnnouncementReads.Received;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * «Оголошення» in the member account (feature 021, follow-up): the board's announcements the signed-in member received
 * through any channel ({@link AnnouncementReads}), sent in the last {@value #MONTHS} months, newest first, and the mark
 * of the ones they opened. The member is always the one behind the token, never a request value; the platform operator
 * (no cooperative, no member) is refused with 403 {@code PERMISSION_DENIED}. No permission is needed. An announcement
 * the member did not receive (another member's, another cooperative's or an unknown id) is 404 {@code NOT_FOUND}. Not
 * audited: opening an announcement is the member's own state, not an admin change.
 */
@Service
class MemberAnnouncements {

    /** How far back the account lists announcements. */
    static final int MONTHS = 12;

    private final AnnouncementReads reads;
    private final Clock clock;

    MemberAnnouncements(AnnouncementReads reads, Clock clock) {
        this.reads = reads;
        this.clock = clock;
    }

    /** The member's announcements of the last {@value #MONTHS} months (Europe/Kyiv, the application clock). */
    @Transactional(readOnly = true)
    List<Received> list(AccessProfile me) {
        UUID memberId = memberOf(me);
        Instant since = ZonedDateTime.now(clock).minusMonths(MONTHS).toInstant();
        return reads.receivedBy(memberId, since);
    }

    /** Marks the announcement as opened by the member; idempotent; 404 when the member did not receive it. */
    @Transactional
    void markRead(AccessProfile me, UUID announcementId) {
        UUID memberId = memberOf(me);
        if (!reads.received(announcementId, memberId)) {
            throw ApiProblemException.notFound();
        }
        reads.markRead(me.tenantId(), announcementId, memberId, clock.instant());
    }

    private static UUID memberOf(AccessProfile me) {
        if (me.platformOperator() || me.tenantId() == null || me.memberId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "Only a member of a cooperative receives announcements.");
        }
        return me.memberId();
    }
}
