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
import { ConfirmDialog } from '../components/ConfirmDialog';
import { MemberDetails } from '../components/MemberDetails';
import { MemberForm } from '../components/MemberForm';
import { MemberPlotsPanel } from '../components/ownership/MemberPlotsPanel';
import { MemberRolesForm } from '../components/MemberRolesForm';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import { Badge, Button, buttonClass, Notice, PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { useCan } from '../me';
import { problemMessage } from '../problem';
import { NotFoundPage } from './NotFoundPage';
import { useDocumentTitle } from '../useDocumentTitle';

export function MemberPage() {
  const t = useT();
  const { id = '' } = useParams();
  const member = useGetMember(id);
  const loaded = member.data;
  useDocumentTitle(
    loaded
      ? [loaded.lastName, loaded.firstName, loaded.middleName].filter(Boolean).join(' ')
      : t.members.title,
  );

  if (member.error instanceof ApiError && member.error.status === 404) return <NotFoundPage />;

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <Link to="/members" className="text-ink-muted hover:text-ink">
        {t.members.back}
      </Link>
      <QueryBoundary query={member}>{(data) => <MemberView key={data.id} member={data} />}</QueryBoundary>
    </div>
  );
}

function MemberView({ member }: { member: Member }) {
  const t = useT();
  const canEdit = useCan('MEMBERS_EDIT');
  const canAudit = useCan('AUDIT_VIEW');
  const canManageRoles = useCan('ROLES_MANAGE');
  const queryClient = useQueryClient();
  const location = useLocation();
  const [editing, setEditing] = useState(false);
  const [editingRoles, setEditingRoles] = useState(false);
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

  const actions = !editing && (
    <>
      {canAudit && (
        <Link to={`/audit?entityType=MEMBER&entityId=${member.id}`} className={buttonClass('secondary')}>
          {t.members.history}
        </Link>
      )}
      {canManageRoles && !editingRoles && (
        <Button
          variant="secondary"
          onClick={() => {
            setSaved(false);
            setActionError(undefined);
            setEditingRoles(true);
          }}
        >
          {t.rolesPage.memberRoles}
        </Button>
      )}
      {canEdit && (
        <>
          <Button
            variant="secondary"
            onClick={() => {
              setSaved(false);
              setActionError(undefined);
              setEditing(true);
            }}
          >
            {c.edit}
          </Button>
          <Button
            variant="secondary"
            onClick={() => {
              setActionError(undefined);
              setConfirming(true);
            }}
          >
            {member.archived ? c.restore : c.archive}
          </Button>
        </>
      )}
    </>
  );

  return (
    <>
      <PageHeader
        title={<UserContent>{fullName}</UserContent>}
        badge={member.archived && <Badge tone="neutral">{t.members.archived}</Badge>}
        actions={actions}
      />
      {saved && <Notice tone="success">{c.saved}</Notice>}
      {actionError && <Notice tone="danger">{actionError}</Notice>}

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
          {editingRoles ? (
            <MemberRolesForm
              member={member}
              onSaved={() => {
                setEditingRoles(false);
                setSaved(true);
              }}
              onCancel={() => setEditingRoles(false)}
            />
          ) : (
            <MemberDetails member={member} />
          )}
          <MemberPlotsPanel memberId={member.id} />
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
