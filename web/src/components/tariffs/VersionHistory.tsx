import {
  getGetTariffQueryKey,
  getListTariffsQueryKey,
  useDeleteTariffVersion,
  useUpdateTariffVersion,
  type Tariff,
  type TariffVersion,
  type TariffVersionCreate,
} from '@nadel/api-client';
import { format, formatDay, formatRateWithCurrency } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { useCan } from '../../me';
import { problemCode, problemMessage, type FieldErrors } from '../../problem';
import { ConfirmDialog } from '../ConfirmDialog';
import { RecalculationStep } from '../recalculation/RecalculationStep';
import { useCauseDrivenChange } from '../recalculation/useCauseDrivenChange';
import { Button, Notice } from '../ui';
import { UserContent } from '../UserContent';
import {
  emptyVersionValues,
  validateVersion,
  VersionFields,
  versionSaveErrors,
  type VersionValues,
} from './VersionFields';

function valuesOf(version: TariffVersion): VersionValues {
  return {
    ...emptyVersionValues,
    validFrom: version.validFrom,
    basis: version.basis,
    frequency: version.frequency,
    rate: version.rate,
    dueDate: version.dueDate ?? '',
    note: version.note ?? '',
  };
}

/**
 * The versions of a tariff as the server sends them (newest first). With TARIFFS_MANAGE (and a live charge type)
 * each version can be corrected in place or deleted; a version already used in charges answers 409, and from then
 * on its controls are disabled with a hint (the API has no "in use" flag on the version). Holders of
 * RECALCULATION_RUN get step 2 of a recalculation instead (`TARIFF_VERSION_UPDATE` / `TARIFF_VERSION_DELETE`):
 * the change and the recalculation are saved together. A change of the note alone is a plain save.
 */
export function VersionHistory({ tariff }: { tariff: Tariff }) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.tariffs.card;
  const queryClient = useQueryClient();
  const canManage = useCan('TARIFFS_MANAGE') && tariff.chargeType.active;
  const [editingId, setEditingId] = useState<string>();
  const [deleting, setDeleting] = useState<TariffVersion>();
  const [recalcDelete, setRecalcDelete] = useState<TariffVersion>();
  const canRecalculate = useCan('RECALCULATION_RUN');
  const [inUse, setInUse] = useState<ReadonlySet<string>>(new Set());
  const [status, setStatus] = useState<{ tone: 'success' | 'danger'; text: string }>();

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: getGetTariffQueryKey(tariff.id) }),
      queryClient.invalidateQueries({ queryKey: getListTariffsQueryKey() }),
      queryClient.invalidateQueries({ predicate: (q) => String(q.queryKey[0]).includes('/tariffs') }),
    ]);

  const markInUse = (versionId: string) => setInUse((current) => new Set(current).add(versionId));

  const remove = useDeleteTariffVersion({
    mutation: {
      onSuccess: async () => {
        setDeleting(undefined);
        setStatus({ tone: 'success', text: c.deleted });
        await refresh();
      },
      onError: (error: unknown) => {
        const used = problemCode(error) === 'TARIFF_VERSION_IN_USE' && deleting;
        setDeleting(undefined);
        if (used && canRecalculate) return setRecalcDelete(deleting);
        if (used) markInUse(deleting.id);
        setStatus({ tone: 'danger', text: problemMessage(t, error) });
      },
    },
  });

  return (
    <div className="flex flex-col gap-3">
      {status && <Notice tone={status.tone}>{status.text}</Notice>}
      <ol className="flex flex-col">
        {tariff.versions.map((version) => {
          const used = inUse.has(version.id);
          return (
            <li
              key={version.id}
              className="flex flex-col gap-1 border-b border-line-subtle py-3 first:pt-0 last:border-b-0 last:pb-0"
            >
              {recalcDelete?.id === version.id ? (
                <DeleteViaRecalculation
                  tariffId={tariff.id}
                  version={version}
                  onDone={async (recalculated) => {
                    setRecalcDelete(undefined);
                    setStatus({ tone: 'success', text: recalculated ? c.deletedRecalculated : c.deleted });
                    await refresh();
                  }}
                  onCancel={() => setRecalcDelete(undefined)}
                />
              ) : editingId === version.id ? (
                <EditVersion
                  tariffId={tariff.id}
                  version={version}
                  canRecalculate={canRecalculate}
                  onDone={async (recalculated) => {
                    setEditingId(undefined);
                    setStatus({ tone: 'success', text: recalculated ? c.savedRecalculated : c.saved });
                    await refresh();
                  }}
                  onInUse={() => {
                    markInUse(version.id);
                    setEditingId(undefined);
                  }}
                  onCancel={() => setEditingId(undefined)}
                />
              ) : (
                <>
                  <span className="font-medium">
                    {version.validTo
                      ? format(c.range, {
                          from: formatDay(version.validFrom, locale),
                          to: formatDay(version.validTo, locale),
                        })
                      : format(c.rangeOpen, { from: formatDay(version.validFrom, locale) })}
                  </span>
                  <span>
                    {formatRateWithCurrency(version.rate, locale)} · {t.tariffs.basis[version.basis]} ·{' '}
                    {t.tariffs.frequency[version.frequency]}
                  </span>
                  {version.dueDate && (
                    <span className="text-sm text-ink-muted">
                      {format(c.dueOn, { date: formatDay(version.dueDate, locale) })}
                    </span>
                  )}
                  {version.note && (
                    <span className="text-sm text-ink-muted">
                      <UserContent>{version.note}</UserContent>
                    </span>
                  )}
                  {canManage && (
                    <div className="mt-1 flex flex-wrap items-center gap-2">
                      <Button
                        variant="secondary"
                        disabled={used}
                        onClick={() => {
                          setStatus(undefined);
                          setEditingId(version.id);
                        }}
                      >
                        {c.edit}
                      </Button>
                      <Button
                        variant="secondary"
                        disabled={used}
                        onClick={() => {
                          setStatus(undefined);
                          setDeleting(version);
                        }}
                      >
                        {c.delete}
                      </Button>
                      {used && <span className="text-sm text-ink-muted">{c.inUse}</span>}
                    </div>
                  )}
                </>
              )}
            </li>
          );
        })}
      </ol>
      {deleting && (
        <ConfirmDialog
          title={c.deleteTitle}
          message={format(c.deleteText, { date: formatDay(deleting.validFrom, locale) })}
          confirmLabel={c.deleteConfirm}
          busy={remove.isPending}
          onConfirm={() => remove.mutate({ id: tariff.id, versionId: deleting.id })}
          onCancel={() => setDeleting(undefined)}
        />
      )}
    </div>
  );
}

/** Step 2 for the deletion of a used version: previews on mount, books the deletion with the recalculation. */
function DeleteViaRecalculation({
  tariffId,
  version,
  onDone,
  onCancel,
}: {
  tariffId: string;
  version: TariffVersion;
  onDone: (recalculated: boolean) => Promise<void>;
  onCancel: () => void;
}) {
  const t = useT();
  const [error, setError] = useState<unknown>();
  const change = useCauseDrivenChange((result) => onDone(result.booked));
  const started = useRef(false);
  const { start } = change;
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    start({ type: 'TARIFF_VERSION_DELETE', tariffVersion: { tariffId, versionId: version.id } }).catch(
      setError,
    );
  }, [start, tariffId, version.id]);

  if (change.state.kind === 'preview') {
    return <RecalculationStep run={change.run} onBack={onCancel} onBooked={change.finish} />;
  }
  return error !== undefined ? (
    <div className="flex flex-col gap-3">
      <Notice tone="danger">{problemMessage(t, error)}</Notice>
      <div>
        <Button variant="secondary" onClick={onCancel}>
          {t.recalculation.step.back}
        </Button>
      </div>
    </div>
  ) : (
    <Notice tone="warning">{t.recalculation.step.loading}</Notice>
  );
}

function EditVersion({
  tariffId,
  version,
  canRecalculate,
  onDone,
  onInUse,
  onCancel,
}: {
  tariffId: string;
  version: TariffVersion;
  canRecalculate: boolean;
  onDone: (recalculated: boolean) => Promise<void>;
  onInUse: () => void;
  onCancel: () => void;
}) {
  const t = useT();
  const c = t.tariffs.card;
  const [values, setValues] = useState<VersionValues>(() => valuesOf(version));
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string>();

  const sent = useRef<TariffVersionCreate>(undefined);
  const change = useCauseDrivenChange((result) => onDone(result.booked));

  function showError(error: unknown) {
    const { fields, form } = versionSaveErrors(t, error);
    setErrors(fields);
    setFormError(form);
  }

  const update = useUpdateTariffVersion({
    mutation: {
      onSuccess: () => onDone(false),
      onError: (error: unknown) => {
        if (problemCode(error) !== 'TARIFF_VERSION_IN_USE') return showError(error);
        if (!canRecalculate || !sent.current) return onInUse();
        change
          .start({
            type: 'TARIFF_VERSION_UPDATE',
            tariffVersion: { tariffId, versionId: version.id, version: sent.current },
          })
          .catch(showError);
      },
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(undefined);
    const checked = validateVersion(values, t);
    setErrors(checked.errors ?? {});
    if (!checked.data) return;
    sent.current = checked.data;
    update.mutate({ id: tariffId, versionId: version.id, data: checked.data });
  }

  if (change.state.kind === 'preview') {
    return <RecalculationStep run={change.run} onBack={change.back} onBooked={change.finish} />;
  }
  const busy = update.isPending || change.state.kind === 'saving';

  return (
    <form onSubmit={submit} noValidate aria-label={c.editVersion} className="flex flex-col gap-4">
      <VersionFields values={values} onChange={setValues} errors={errors} />
      {formError && <Notice tone="danger">{formError}</Notice>}
      <div className="flex gap-2">
        <Button type="submit" disabled={busy}>
          {busy ? c.saving : c.save}
        </Button>
        <Button variant="secondary" onClick={onCancel}>
          {t.memberForm.cancel}
        </Button>
      </div>
    </form>
  );
}
