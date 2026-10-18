import {
  getListRolesQueryKey,
  useCreateRole,
  useDeleteRole,
  useListPermissions,
  useListRoles,
  useUpdateRole,
  type Permission,
  type RoleDetails,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { QueryBoundary } from '../components/QueryBoundary';
import { Badge, Button, Notice, PageHeader, TextArea, TextField } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { problemCode, problemFieldErrors, problemMessage, type FieldErrors } from '../problem';
import { useRoleName } from '../roleName';
import { findNameClash } from '../roleNameClash';
import { nameToSubmit } from '../roleNameSubmit';
import { useDocumentTitle } from '../useDocumentTitle';

export function RolesPage() {
  const t = useT();
  const roles = useListRoles();
  const permissions = useListPermissions();
  const queryClient = useQueryClient();
  const roleName = useRoleName();
  const [editing, setEditing] = useState<string>();
  const [deleting, setDeleting] = useState<RoleDetails>();
  const [saved, setSaved] = useState<string>();
  const [error, setError] = useState<string>();
  const c = t.rolesPage;
  useDocumentTitle(c.title);

  const remove = useDeleteRole({
    mutation: {
      onSuccess: async () => {
        await queryClient.invalidateQueries({ queryKey: getListRolesQueryKey() });
        setDeleting(undefined);
        setError(undefined);
        setSaved(c.deleted);
      },
      onError: (e: unknown) => {
        setDeleting(undefined);
        setSaved(undefined);
        setError(problemMessage(t, e));
      },
    },
  });

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={c.title}
        actions={
          <Button
            variant="primary"
            onClick={() => {
              setSaved(undefined);
              setError(undefined);
              setEditing('new');
            }}
          >
            {c.add}
          </Button>
        }
      />
      {saved && <Notice tone="success">{saved}</Notice>}
      {error && <Notice tone="danger">{error}</Notice>}

      {editing === 'new' && permissions.data && (
        <RoleEditor
          key="new"
          permissions={permissions.data.map((p) => p.code)}
          others={roles.data ?? []}
          onDone={() => {
            setEditing(undefined);
            setSaved(c.saved);
          }}
          onCancel={() => setEditing(undefined)}
        />
      )}

      <QueryBoundary query={roles} isEmpty={(data) => data.length === 0} emptyMessage={c.empty}>
        {(data) => (
          <ul className="flex flex-col gap-3">
            {[...data]
              .sort((a, b) => Number(b.baseCode !== undefined) - Number(a.baseCode !== undefined))
              .map((role) => (
                <li key={role.id} className="rounded-panel border border-line bg-surface p-5">
                  {editing === role.id && permissions.data ? (
                    <RoleEditor
                      key={role.id}
                      role={role}
                      permissions={permissions.data.map((p) => p.code)}
                      others={data.filter((r) => r.id !== role.id)}
                      onDone={() => {
                        setEditing(undefined);
                        setSaved(c.saved);
                      }}
                      onCancel={() => setEditing(undefined)}
                    />
                  ) : (
                    <div className="flex flex-wrap items-center gap-3">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-[17px] font-semibold">{roleName(role)}</span>
                          <Badge tone={role.baseCode ? 'info' : 'neutral'}>
                            {role.baseCode ? c.baseRole : c.customRole}
                          </Badge>
                        </div>
                        {role.description && <p className="text-sm text-ink-muted">{role.description}</p>}
                        <p className="text-sm text-ink-muted">
                          {format(c.memberCount, { n: role.memberCount })}
                        </p>
                      </div>
                      <div className="ml-auto flex gap-2">
                        <Button
                          variant="secondary"
                          onClick={() => {
                            setSaved(undefined);
                            setError(undefined);
                            setEditing(role.id);
                          }}
                        >
                          {c.edit}
                        </Button>
                        {!role.baseCode && (
                          <Button
                            variant="danger"
                            onClick={() => {
                              setSaved(undefined);
                              setError(undefined);
                              setDeleting(role);
                            }}
                          >
                            {c.delete}
                          </Button>
                        )}
                      </div>
                    </div>
                  )}
                </li>
              ))}
          </ul>
        )}
      </QueryBoundary>

      {deleting && (
        <ConfirmDialog
          title={c.deleteTitle}
          message={format(c.deleteText, { name: roleName(deleting) })}
          confirmLabel={c.delete}
          busy={remove.isPending}
          onConfirm={() => remove.mutate({ id: deleting.id })}
          onCancel={() => setDeleting(undefined)}
        />
      )}
    </div>
  );
}

/** Create / edit form; initial values are read once, so mount it with a `key`. */
function RoleEditor({
  role,
  permissions: available,
  others,
  onDone,
  onCancel,
}: {
  role?: RoleDetails;
  permissions: readonly Permission[];
  others: readonly RoleDetails[];
  onDone: () => void;
  onCancel: () => void;
}) {
  const t = useT();
  const c = t.rolesPage;
  const queryClient = useQueryClient();
  const isChairman = role?.baseCode === 'CHAIRMAN';
  const roleName = useRoleName();
  const { locale } = useLocale();

  const [name, setName] = useState(role ? roleName(role) : '');
  const [description, setDescription] = useState(role?.description ?? '');
  const [selected, setSelected] = useState<ReadonlySet<Permission>>(
    new Set(isChairman ? available : (role?.permissions ?? [])),
  );
  const [errors, setErrors] = useState<FieldErrors>({});
  const [banner, setFormError] = useState<string>();

  const options = {
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: getListRolesQueryKey() });
      onDone();
    },
    onError: (e: unknown) => {
      const fields = problemFieldErrors(t, e);
      if (problemCode(e) === 'ROLE_NAME_TAKEN') {
        setErrors({ name: t.problem.ROLE_NAME_TAKEN });
        setFormError(undefined);
      } else if (Object.keys(fields).length > 0) {
        setErrors(fields);
        setFormError(undefined);
      } else {
        setErrors({});
        setFormError(problemMessage(t, e));
      }
    },
  };
  const create = useCreateRole({ mutation: options });
  const update = useUpdateRole({ mutation: options });
  const busy = create.isPending || update.isPending;

  const toggle = (code: Permission) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (!next.delete(code)) next.add(code);
      return next;
    });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setErrors({});
    setFormError(undefined);
    const data = {
      name: nameToSubmit(role, name, role ? roleName(role) : ''),
      description: description.trim() === '' ? undefined : description.trim(),
      permissions: available.filter((code) => selected.has(code)),
    };
    if (role) update.mutate({ id: role.id, data });
    else create.mutate({ data });
  };

  const restoreName = () => {
    if (!role) return;
    setErrors({});
    setFormError(undefined);
    update.mutate({
      id: role.id,
      data: {
        name: role.name,
        description: description.trim() === '' ? undefined : description.trim(),
        permissions: available.filter((code) => selected.has(code)),
        useDefaultName: true,
      },
    });
  };

  const clash = findNameClash(name, others, roleName, locale);

  return (
    <form onSubmit={submit} className="flex flex-col gap-3" noValidate>
      <h2 className="font-serif text-xl font-semibold">{role ? roleName(role) : c.newTitle}</h2>
      {banner && <Notice tone="danger">{banner}</Notice>}
      <TextField
        label={c.name}
        error={errors.name}
        value={name}
        hint={role?.baseCode ? format(c.baseHint, { name: t.roles[role.baseCode] }) : undefined}
        maxLength={60}
        onChange={(e) => setName(e.target.value)}
      />
      {clash && <Notice tone="warning">{c.nameClash}</Notice>}
      {role?.baseCode && role.nameCustomized && (
        <div>
          <Button variant="secondary" onClick={restoreName} disabled={busy}>
            {c.restoreName}
          </Button>
        </div>
      )}
      <TextArea
        label={c.description}
        error={errors.description}
        rows={2}
        value={description}
        maxLength={500}
        onChange={(e) => setDescription(e.target.value)}
      />
      <PermissionBoxes
        codes={available}
        selected={selected}
        disabled={isChairman}
        legend={c.permissions}
        onToggle={toggle}
      />
      {isChairman && <p className="text-sm text-ink-muted">{c.chairmanNote}</p>}
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={onCancel}>
          {c.cancel}
        </Button>
        <Button type="submit" variant="primary" disabled={busy}>
          {busy ? c.saving : c.save}
        </Button>
      </div>
    </form>
  );
}

function PermissionBoxes({
  codes,
  selected,
  disabled,
  legend,
  onToggle,
}: {
  codes: readonly Permission[];
  selected: ReadonlySet<Permission>;
  disabled: boolean;
  legend: string;
  onToggle: (code: Permission) => void;
}) {
  const t = useT();
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-1 text-sm text-ink-muted">{legend}</legend>
      {codes.map((code) => (
        <label key={code} className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={selected.has(code)}
            disabled={disabled}
            onChange={() => onToggle(code)}
          />
          <span>
            <span className="font-medium">{t.permission[code].name}</span>
            <span className="block text-ink-muted">{t.permission[code].description}</span>
          </span>
        </label>
      ))}
    </fieldset>
  );
}
