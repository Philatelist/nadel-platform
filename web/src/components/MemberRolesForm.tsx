import {
  getGetMeQueryKey,
  getGetMemberQueryKey,
  getListMembersQueryKey,
  getListRolesQueryKey,
  useListRoles,
  useSetMemberRoles,
  type Member,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../i18n';
import { useMe } from '../me';
import { problemMessage } from '../problem';
import { useRoleName } from '../roleName';
import { Banner } from './Banner';
import { QueryBoundary } from './QueryBoundary';

/** Role checkbox list for one member. Initial selection is read once, so mount it with a `key`. */
export function MemberRolesForm({
  member,
  onSaved,
  onCancel,
}: {
  member: Member;
  onSaved: (saved: Member) => void;
  onCancel: () => void;
}) {
  const t = useT();
  const c = t.rolesPage;
  const roleName = useRoleName();
  const me = useMe();
  const queryClient = useQueryClient();
  const roles = useListRoles();
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set(member.roles.map((r) => r.id)));
  const [error, setError] = useState<string>();

  const save = useSetMemberRoles({
    mutation: {
      onSuccess: async (updated) => {
        queryClient.setQueryData(getGetMemberQueryKey(member.id), updated);
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: getListMembersQueryKey() }),
          queryClient.invalidateQueries({ queryKey: getListRolesQueryKey() }),
          member.id === me.memberId
            ? queryClient.invalidateQueries({ queryKey: getGetMeQueryKey() })
            : Promise.resolve(),
        ]);
        onSaved(updated);
      },
      onError: (e: unknown) => setError(problemMessage(t, e)),
    },
  });

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) next.add(id);
      return next;
    });

  return (
    <div className="flex flex-col gap-3 rounded-xl border border-line bg-surface p-5">
      {error && <Banner variant="error">{error}</Banner>}
      <QueryBoundary query={roles}>
        {(data) => (
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm text-muted">{t.member.fields.roles}</legend>
            {data.map((role) => (
              <label key={role.id} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={selected.has(role.id)} onChange={() => toggle(role.id)} />
                {roleName(role)}
              </label>
            ))}
          </fieldset>
        )}
      </QueryBoundary>
      <div className="flex justify-end gap-2">
        <button
          type="button"
          className="rounded-md border border-line px-3 py-1.5 text-sm"
          onClick={onCancel}
        >
          {c.cancel}
        </button>
        <button
          type="button"
          disabled={save.isPending || !roles.data}
          onClick={() => {
            setError(undefined);
            save.mutate({ id: member.id, data: { roleIds: [...selected] } });
          }}
          className="rounded-md bg-accent px-3 py-1.5 text-sm font-semibold text-on-accent disabled:opacity-60"
        >
          {save.isPending ? c.saving : c.save}
        </button>
      </div>
    </div>
  );
}
