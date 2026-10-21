import {
  getListChargeTypesQueryKey,
  useArchiveChargeType,
  useCreateChargeType,
  useListChargeTypes,
  useReorderChargeTypes,
  useUpdateChargeType,
  type ChargeType,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { chargeTypeName } from '../chargeTypeName';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { QueryBoundary } from '../components/QueryBoundary';
import { ChargeTypeDialog, type ChargeTypeFormValues } from '../components/tariffs/ChargeTypeDialog';
import { Badge, Button, buttonClass, Notice, PageHeader, Panel } from '../components/ui';
import { useT } from '../i18n';
import { useCan } from '../me';
import { problemCode, problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

type Editing = { kind: 'add' } | { kind: 'edit'; type: ChargeType };

export function ChargeTypesPage() {
  const t = useT();
  const c = t.chargeTypes;
  const canManage = useCan('TARIFFS_MANAGE');
  const queryClient = useQueryClient();
  useDocumentTitle(c.title);
  const query = useListChargeTypes();
  const [editing, setEditing] = useState<Editing>();
  const [archiving, setArchiving] = useState<ChargeType>();
  const [dialogError, setDialogError] = useState<string>();
  const [pageError, setPageError] = useState<string>();

  const reload = () => queryClient.invalidateQueries({ queryKey: getListChargeTypesQueryKey() });
  const closeDialog = () => {
    setEditing(undefined);
    setDialogError(undefined);
  };
  const saved = {
    onSuccess: async () => {
      await reload();
      closeDialog();
    },
    onError: (error: unknown) => setDialogError(problemMessage(t, error)),
  };
  const create = useCreateChargeType({ mutation: saved });
  const update = useUpdateChargeType({ mutation: saved });
  const reorder = useReorderChargeTypes({
    mutation: {
      onSuccess: reload,
      onError: async (error: unknown) => {
        setPageError(problemMessage(t, error));
        // A stale order means the list changed meanwhile: show the current one.
        if (problemCode(error) === 'CHARGE_TYPE_ORDER_STALE') await reload();
      },
    },
  });
  const archive = useArchiveChargeType({
    mutation: {
      onSuccess: async () => {
        await reload();
        setArchiving(undefined);
      },
      onError: (error: unknown) => {
        setArchiving(undefined);
        setPageError(problemMessage(t, error));
      },
    },
  });

  function submit(values: ChargeTypeFormValues) {
    setDialogError(undefined);
    const description = values.description === '' ? undefined : values.description;
    if (editing?.kind === 'edit') {
      update.mutate({
        id: editing.type.id,
        data: { name: values.name, description, active: values.active },
      });
    } else {
      create.mutate({ data: { name: values.name, description } });
    }
  }

  /** Sends the whole list in its new order, so the server can detect that the list changed meanwhile. */
  function move(types: readonly ChargeType[], index: number, delta: -1 | 1) {
    const ids = types.map((type) => type.id);
    const moved = ids.splice(index, 1);
    ids.splice(index + delta, 0, ...moved);
    setPageError(undefined);
    reorder.mutate({ data: { ids } });
  }

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={c.title}
        actions={
          <>
            <Link to="/tariffs" className={buttonClass('secondary')}>
              {c.back}
            </Link>
            {canManage && <Button onClick={() => setEditing({ kind: 'add' })}>{c.add}</Button>}
          </>
        }
      />
      {pageError && <Notice tone="danger">{pageError}</Notice>}
      <QueryBoundary query={query} isEmpty={(data) => data.length === 0} emptyMessage={c.empty}>
        {(types) => (
          <Panel>
            <ul className="flex flex-col">
              {types.map((type, index) => (
                <li
                  key={type.id}
                  className="flex flex-wrap items-center justify-between gap-3 border-b border-line-subtle py-3 first:pt-0 last:border-b-0 last:pb-0"
                >
                  <div className="flex min-w-0 flex-col gap-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{chargeTypeName(type, t)}</span>
                      <Badge tone={type.active ? 'success' : 'neutral'}>
                        {type.active ? c.active : c.inactive}
                      </Badge>
                    </div>
                    {type.description && <span className="text-sm text-ink-muted">{type.description}</span>}
                  </div>
                  {canManage && (
                    <div className="flex flex-wrap gap-2">
                      <Button
                        variant="secondary"
                        disabled={index === 0 || reorder.isPending}
                        onClick={() => move(types, index, -1)}
                      >
                        {c.moveUp}
                      </Button>
                      <Button
                        variant="secondary"
                        disabled={index === types.length - 1 || reorder.isPending}
                        onClick={() => move(types, index, 1)}
                      >
                        {c.moveDown}
                      </Button>
                      <Button variant="secondary" onClick={() => setEditing({ kind: 'edit', type })}>
                        {c.edit}
                      </Button>
                      <Button variant="danger" onClick={() => setArchiving(type)}>
                        {c.archive}
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          </Panel>
        )}
      </QueryBoundary>

      {editing && (
        <ChargeTypeDialog
          key={editing.kind === 'edit' ? editing.type.id : 'add'}
          type={editing.kind === 'edit' ? editing.type : undefined}
          busy={create.isPending || update.isPending}
          error={dialogError}
          onSubmit={submit}
          onCancel={closeDialog}
        />
      )}
      {archiving && (
        <ConfirmDialog
          title={c.archiveTitle}
          message={format(c.archiveText, { name: chargeTypeName(archiving, t) })}
          confirmLabel={c.archiveConfirm}
          busy={archive.isPending}
          onConfirm={() => archive.mutate({ id: archiving.id })}
          onCancel={() => setArchiving(undefined)}
        />
      )}
    </div>
  );
}
