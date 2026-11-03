package app.nadel.members.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.members.MemberChannels;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link MemberChannels}: the member's own choice of channel (feature 017 §2.3). The same contact rule as the
 * member form ({@link DeliveryChannel#check}) and the same audit as an edit ({@code MEMBER_UPDATED}, field
 * {@code deliveryChannel}), so the board sees the new channel on the member form and in the history.
 */
@Service
class MemberChannelCommands implements MemberChannels {

    private final MemberRepository members;
    private final MemberCrypto crypto;
    private final MemberAuditPublisher audit;

    MemberChannelCommands(MemberRepository members, MemberCrypto crypto, MemberAuditPublisher audit) {
        this.members = members;
        this.crypto = crypto;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void setPreferredChannel(UUID memberId, app.nadel.members.DeliveryChannel channel, AccessProfile actor) {
        Member member = members.findByIdForUpdate(memberId).orElseThrow(ApiProblemException::notFound);
        DeliveryChannel chosen = DeliveryChannel.valueOf(channel.name());
        if (member.getDeliveryChannel() == chosen) {
            return;
        }
        OpenedContacts contacts = crypto.open(member);
        chosen.check(contacts.email(), contacts.viberPhone()).ifPresent(problem -> {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(problem));
        });
        String before = member.getDeliveryChannel().name();
        member.choose(chosen, Instant.now());
        members.saveAndFlush(member);
        audit.publish(Objects.requireNonNull(actor.tenantId(), "tenantId"),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), "MEMBER_UPDATED", member,
                List.of(new FieldChange("deliveryChannel", before, chosen.name())));
    }
}
