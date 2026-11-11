import {
  ApiError,
  getGetMemberQueryKey,
  getListMembersQueryKey,
  useDisableMemberAccess,
  useInviteMember,
  useResendMemberInvitation,
  type Member,
  type MemberAccess as MemberAccessData,
} from '@nadel/api-client';
import { format, formatDateTime } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocale, useT } from '../../i18n';
import { useCan, useMe } from '../../me';
import { problemCode, problemMessage } from '../../problem';
import { ConfirmDialog } from '../ConfirmDialog';
import { Button, Notice, Panel } from '../ui';

function failureCodeOf(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) return undefined;
  const body = error.body;
  return typeof body === 'object' &&
    body !== null &&
    'failureCode' in body &&
    typeof body.failureCode === 'string'
    ? body.failureCode
    : undefined;
}

/** The member's access to the system and the invite, send-again and turn-off actions (shown only with `access`). */
export function MemberAccess({ member, access }: { member: Member; access: MemberAccessData }) {
  const t = useT();
  const { locale } = useLocale();
  const canInvite = useCan('MEMBERS_INVITE');
  // nobody turns off their own access (the server refuses it with OWN_ACCESS)
  const ownCard = useMe().memberId === member.id;
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string }>();
  const c = t.memberAccess;

  const refresh = async (text: string): Promise<void> => {
    setConfirming(false);
    setMessage({ tone: 'success', text });
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getGetMemberQueryKey(member.id) }),
      queryClient.invalidateQueries({ queryKey: getListMembersQueryKey() }),
    ]);
  };
  const fail = (error: unknown): void => {
    setConfirming(false);
    const failure = failureCodeOf(error);
    const reasons: Readonly<Record<string, string>> = t.deliveries.reason;
    const reason = failure !== undefined ? reasons[failure] : undefined;
    setMessage({
      tone: 'danger',
      text:
        problemCode(error) === 'INVITATION_NOT_SENT' && reason !== undefined
          ? format(c.notSent, { reason })
          : problemCode(error) === 'LAST_CHAIRMAN'
            ? c.lastChairman
            : problemMessage(t, error),
    });
  };
  const invite = useInviteMember({ mutation: { onSuccess: () => refresh(c.invited), onError: fail } });
  const resend = useResendMemberInvitation({
    mutation: { onSuccess: () => refresh(c.resent), onError: fail },
  });
  const disable = useDisableMemberAccess({
    mutation: { onSuccess: () => refresh(c.disabled), onError: fail },
  });
  const busy = invite.isPending || resend.isPending || disable.isPending;

  const statusText = ((): string => {
    switch (access.status) {
      case 'NOT_INVITED':
        return c.status.NOT_INVITED;
      case 'INVITED':
        return format(c.status.INVITED, {
          date: access.invitedAt ? formatDateTime(access.invitedAt, locale) : '',
        });
      case 'ACTIVE':
        return access.lastSignInAt
          ? format(c.status.ACTIVE_SIGNED_IN, { date: formatDateTime(access.lastSignInAt, locale) })
          : c.status.ACTIVE_NEVER;
      case 'DISABLED':
        return c.status.DISABLED;
    }
  })();

  const canInviteNow = access.status === 'NOT_INVITED' || access.status === 'DISABLED';
  const blocked = access.inviteBlockedBy;
  const name = [member.lastName, member.firstName, member.middleName].filter(Boolean).join(' ');

  return (
    <Panel title={c.title}>
      <div className="flex flex-col gap-3">
        <p>{statusText}</p>
        {message && <Notice tone={message.tone}>{message.text}</Notice>}
        {canInvite && (
          <div className="flex flex-wrap items-center gap-2">
            {canInviteNow && (
              <Button
                disabled={busy || blocked !== undefined}
                onClick={() => {
                  setMessage(undefined);
                  invite.mutate({ id: member.id });
                }}
              >
                {c.invite}
              </Button>
            )}
            {access.status === 'INVITED' && (
              <Button
                variant="secondary"
                disabled={busy || blocked !== undefined}
                onClick={() => {
                  setMessage(undefined);
                  resend.mutate({ id: member.id });
                }}
              >
                {c.resend}
              </Button>
            )}
            {(access.status === 'INVITED' || access.status === 'ACTIVE') && !ownCard && (
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() => {
                  setMessage(undefined);
                  setConfirming(true);
                }}
              >
                {c.disable}
              </Button>
            )}
          </div>
        )}
        {canInvite && blocked && (canInviteNow || access.status === 'INVITED') && (
          <p className="text-sm text-ink-muted">{c.blocked[blocked]}</p>
        )}
      </div>
      {confirming && (
        <ConfirmDialog
          title={c.disableTitle}
          message={format(c.disableText, { name })}
          confirmLabel={c.disable}
          busy={disable.isPending}
          onConfirm={() => disable.mutate({ id: member.id })}
          onCancel={() => setConfirming(false)}
        />
      )}
    </Panel>
  );
}
