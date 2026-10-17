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
import { Banner } from '../components/Banner';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Field } from '../components/Field';
import { TextArea, TextInput } from '../components/inputs';
import { QueryBoundary } from '../components/QueryBoundary';
import { useT } from '../i18n';
import { problemCode, problemFieldErrors, problemMessage, type FieldErrors } from '../problem';
import { useRoleName } from '../roleName';

const buttonClass = 'rounded-md border border-line px-3 py-1.5 text-sm';

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
    <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-8">
      <div className="flex items-center gap-3">
        <h1 className="font-display text-2xl font-bold">{c.title}</h1>
        <button
          type="button"
          className={`${buttonClass} ml-auto`}
          onClick={() => {
            setSaved(undefined);
            setError(undefined);
            setEditing('new');
          }}
        >
          {c.add}
        </button>
      </div>
      {saved && <Banner variant="success">{saved}</Banner>}
      {error && <Banner variant="error">{error}</Banner>}

      {editing === 'new' && permissions.data && (
        <RoleEditor
          key="new"
          permissions={permissions.data.map((p) => p.code)}
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
                <li key={role.id} className="rounded-xl border border-line bg-surface p-4">
                  {editing === role.id && permissions.data ? (
                    <RoleEditor
                      key={role.id}
                      role={role}
                      permissions={permissions.data.map((p) => p.code)}
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
                          <span className="font-semibold">{roleName(role)}</span>
                          {role.baseCode && (
                            <span className="rounded-full border border-line px-2 py-0.5 text-xs text-muted">
                              {c.baseRole}
                            </span>
                          )}
                        </div>
                        {role.description && <p className="text-sm text-muted">{role.description}</p>}
                        <p className="text-sm text-muted">{format(c.memberCount, { n: role.memberCount })}</p>
                      </div>
                      <div className="ml-auto flex gap-2">
                        <button
                          type="button"
                          className={buttonClass}
                          onClick={() => {
                            setSaved(undefined);
                            setError(undefined);
                            setEditing(role.id);
                          }}
                        >
                          {c.edit}
                        </button>
                        {!role.baseCode && (
                          <button
                            type="button"
                            className={buttonClass}
                            onClick={() => {
                              setSaved(undefined);
                              setError(undefined);
                              setDeleting(role);
                            }}
                          >
                            {c.delete}
                          </button>
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
  onDone,
  onCancel,
}: {
  role?: RoleDetails;
  permissions: readonly Permission[];
  onDone: () => void;
  onCancel: () => void;
}) {
  const t = useT();
  const c = t.rolesPage;
  const queryClient = useQueryClient();
  const isBase = role?.baseCode !== undefined;
  const isChairman = role?.baseCode === 'CHAIRMAN';
  const roleName = useRoleName();

  const [name, setName] = useState(role ? roleName(role) : '');
  const [description, setDescription] = useState(role?.description ?? '');
  const [selected, setSelected] = useState<ReadonlySet<Permission>>(
    new Set(isChairman ? available : (role?.permissions ?? [])),
  );
  const [errors, setErrors] = useState<FieldErrors>({});
  const [banner, setBanner] = useState<string>();

  const options = {
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: getListRolesQueryKey() });
      onDone();
    },
    onError: (e: unknown) => {
      const fields = problemFieldErrors(t, e);
      if (problemCode(e) === 'ROLE_NAME_TAKEN') {
        setErrors({ name: t.problem.ROLE_NAME_TAKEN });
        setBanner(undefined);
      } else if (Object.keys(fields).length > 0) {
        setErrors(fields);
        setBanner(undefined);
      } else {
        setErrors({});
        setBanner(problemMessage(t, e));
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
    setBanner(undefined);
    const data = {
      name: isBase ? role.name : name.trim(),
      description: description.trim() === '' ? undefined : description.trim(),
      permissions: available.filter((code) => selected.has(code)),
    };
    if (role) update.mutate({ id: role.id, data });
    else create.mutate({ data });
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-3" noValidate>
      <h2 className="font-display text-lg font-bold">{role ? roleName(role) : c.newTitle}</h2>
      {banner && <Banner variant="error">{banner}</Banner>}
      <Field label={c.name} error={errors.name}>
        {(control) => (
          <TextInput
            {...control}
            value={name}
            disabled={isBase}
            maxLength={60}
            onChange={(e) => setName(e.target.value)}
          />
        )}
      </Field>
      <Field label={c.description} error={errors.description}>
        {(control) => (
          <TextArea
            {...control}
            rows={2}
            value={description}
            maxLength={500}
            onChange={(e) => setDescription(e.target.value)}
          />
        )}
      </Field>
      <PermissionBoxes
        codes={available}
        selected={selected}
        disabled={isChairman}
        legend={c.permissions}
        onToggle={toggle}
      />
      {isChairman && <p className="text-sm text-muted">{c.chairmanNote}</p>}
      <div className="flex justify-end gap-2">
        <button type="button" className={buttonClass} onClick={onCancel}>
          {c.cancel}
        </button>
        <button
          type="submit"
          disabled={busy || isChairman}
          className="rounded-md bg-accent px-3 py-1.5 text-sm font-semibold text-on-accent disabled:opacity-60"
        >
          {busy ? c.saving : c.save}
        </button>
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
      <legend className="mb-1 text-sm text-muted">{legend}</legend>
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
            <span className="block text-muted">{t.permission[code].description}</span>
          </span>
        </label>
      ))}
    </fieldset>
  );
}
