import {
  ApiError,
  getGetMemberQueryKey,
  getListMembersQueryKey,
  useArchiveMember,
  useGetMember,
  useRestoreMember,
  type Member,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { Banner } from '../components/Banner';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { MemberDetails } from '../components/MemberDetails';
import { MemberForm } from '../components/MemberForm';
import { QueryBoundary } from '../components/QueryBoundary';
import { useT } from '../i18n';
import { useCan } from '../me';
import { problemMessage } from '../problem';
import { NotFoundPage } from './NotFoundPage';

export function MemberPage() {
  const t = useT();
  const { id = '' } = useParams();
  const member = useGetMember(id);

  if (member.error instanceof ApiError && member.error.status === 404) return <NotFoundPage />;

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-8">
      <Link to="/members" className="text-sm text-muted hover:text-ink">
        {t.members.back}
      </Link>
      <QueryBoundary query={member}>{(data) => <MemberView key={data.id} member={data} />}</QueryBoundary>
    </div>
  );
}

function MemberView({ member }: { member: Member }) {
  const t = useT();
  const canEdit = useCan('MEMBERS_EDIT');
  const queryClient = useQueryClient();
  const location = useLocation();
  const [editing, setEditing] = useState(false);
  const [saved, setSaved] = useState(
    typeof location.state === 'object' && location.state !== null && 'saved' in location.state,
  );
  const [confirming, setConfirming] = useState(false);
  const [actionError, setActionError] = useState<string>();

  const options = {
    onSuccess: async (updated: Member) => {
      queryClient.setQueryData(getGetMemberQueryKey(member.id), updated);
      await queryClient.invalidateQueries({ queryKey: getListMembersQueryKey() });
      setConfirming(false);
      setSaved(true);
    },
    onError: (error: unknown) => {
      setConfirming(false);
      setSaved(false);
      setActionError(problemMessage(t, error));
    },
  };
  const archive = useArchiveMember({ mutation: options });
  const restore = useRestoreMember({ mutation: options });
  const isToggling = archive.isPending || restore.isPending;

  const fullName = [member.lastName, member.firstName, member.middleName].filter(Boolean).join(' ');
  const c = t.memberForm;

  return (
    <>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="font-display text-2xl font-bold">{fullName}</h1>
        {member.archived && (
          <span className="rounded-full border border-line px-2 py-0.5 text-xs text-muted">
            {t.members.archived}
          </span>
        )}
      </div>
      {saved && <Banner variant="success">{c.saved}</Banner>}
      {actionError && <Banner variant="error">{actionError}</Banner>}

      {editing ? (
        <MemberForm
          member={member}
          onSaved={() => {
            setEditing(false);
            setSaved(true);
          }}
          onCancel={() => setEditing(false)}
        />
      ) : (
        <>
          {canEdit && (
            <div className="flex gap-2">
              <button
                type="button"
                className="rounded-md border border-line px-3 py-1.5 text-sm"
                onClick={() => {
                  setSaved(false);
                  setActionError(undefined);
                  setEditing(true);
                }}
              >
                {c.edit}
              </button>
              <button
                type="button"
                className="rounded-md border border-line px-3 py-1.5 text-sm"
                onClick={() => {
                  setActionError(undefined);
                  setConfirming(true);
                }}
              >
                {member.archived ? c.restore : c.archive}
              </button>
            </div>
          )}
          <MemberDetails member={member} />
        </>
      )}

      {confirming && (
        <ConfirmDialog
          title={member.archived ? c.restoreTitle : c.archiveTitle}
          message={member.archived ? c.restoreText : c.archiveText}
          confirmLabel={member.archived ? c.restore : c.archive}
          busy={isToggling}
          onConfirm={() => (member.archived ? restore : archive).mutate({ id: member.id })}
          onCancel={() => setConfirming(false)}
        />
      )}
    </>
  );
}
