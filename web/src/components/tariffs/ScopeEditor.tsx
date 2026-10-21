import {
  getGetTariffQueryKey,
  getListTariffsQueryKey,
  useUpdateTariffScope,
  type Tariff,
  type TariffScopeKind,
  type TariffScopeUpdate,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocale, useT } from '../../i18n';
import { useCan } from '../../me';
import { problemCode, problemMessage, type FieldErrors } from '../../problem';
import { UserContent } from '../UserContent';
import { Badge, Button, Notice, Panel, RadioGroup, TextArea } from '../ui';
import { PlotPicker, type PickedPlot } from './PlotPicker';

const MAX_REASON = 500;

interface ExclusionRow extends PickedPlot {
  reason: string;
  archived?: boolean;
}

interface SelectedRow extends PickedPlot {
  archived?: boolean;
}

/** `exclusions[0].reason` style paths of the server's field errors, mapped to catalog text by code. */
function serverFieldErrors(errors: Readonly<Record<string, string>>, error: unknown): FieldErrors {
  const body = (error as { body?: unknown } | null)?.body;
  if (typeof body !== 'object' || body === null || !('errors' in body) || !Array.isArray(body.errors)) {
    return {};
  }
  const result: Record<string, string> = {};
  for (const item of body.errors as unknown[]) {
    if (typeof item !== 'object' || item === null) continue;
    const { field, code } = item as { field?: unknown; code?: unknown };
    if (typeof field !== 'string' || typeof code !== 'string') continue;
    result[field] = errors[code] ?? errors.INVALID ?? code;
  }
  return result;
}

/** The scope of a tariff: shown to everyone with TARIFFS_VIEW, editable (full replace) with TARIFFS_MANAGE. */
export function ScopeEditor({ tariff }: { tariff: Tariff }) {
  const t = useT();
  const { locale } = useLocale();
  const s = t.tariffs.scope;
  const canManage = useCan('TARIFFS_MANAGE');
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [kind, setKind] = useState<TariffScopeKind>(tariff.scopeKind);
  const [selected, setSelected] = useState<SelectedRow[]>([]);
  const [exclusions, setExclusions] = useState<ExclusionRow[]>([]);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();
  const [saved, setSaved] = useState(false);

  const update = useUpdateTariffScope({
    mutation: {
      onSuccess: async () => {
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: getGetTariffQueryKey(tariff.id) }),
          queryClient.invalidateQueries({ queryKey: getListTariffsQueryKey() }),
        ]);
        setEditing(false);
        setSaved(true);
      },
      onError: (error: unknown) => {
        const fields = serverFieldErrors(s.errors, error);
        setErrors(fields);
        if (Object.keys(fields).length === 0) {
          setNotice(problemMessage(t, error));
        } else if (problemCode(error) === 'CHARGE_TYPE_ARCHIVED') {
          setNotice(t.problem.CHARGE_TYPE_ARCHIVED);
        }
      },
    },
  });

  function startEditing() {
    setKind(tariff.scopeKind);
    setSelected(tariff.selectedPlots.map((p) => ({ id: p.plotId, number: p.number, archived: p.archived })));
    setExclusions(
      tariff.exclusions.map((e) => ({
        id: e.plotId,
        number: e.number,
        archived: e.archived,
        reason: e.reason,
      })),
    );
    setErrors({});
    setNotice(undefined);
    setSaved(false);
    setEditing(true);
  }

  function save() {
    setNotice(undefined);
    if (kind === 'ALL_PLOTS') {
      const missing: Record<string, string> = {};
      exclusions.forEach((row, i) => {
        if (row.reason.trim() === '') missing[`exclusions[${i}].reason`] = s.reasonRequired;
      });
      setErrors(missing);
      if (Object.keys(missing).length > 0) return;
    } else {
      setErrors({});
    }
    const data: TariffScopeUpdate =
      kind === 'ALL_PLOTS'
        ? { kind, exclusions: exclusions.map((e) => ({ plotId: e.id, reason: e.reason.trim() })) }
        : { kind, plotIds: selected.map((p) => p.id) };
    update.mutate({ id: tariff.id, data });
  }

  function plotCountText(count: number): string {
    const category = new Intl.PluralRules(locale).select(count);
    const forms = t.tariffs.plots;
    return format(category === 'one' ? forms.one : category === 'few' ? forms.few : forms.many, { count });
  }

  const count = tariff.plotCount === undefined ? t.tariffs.meterBased : plotCountText(tariff.plotCount);
  const changeKindWarning =
    kind !== tariff.scopeKind
      ? kind === 'SELECTED_PLOTS' && exclusions.length > 0
        ? s.warnClearExclusions
        : kind === 'ALL_PLOTS' && selected.length > 0
          ? s.warnClearSelection
          : undefined
      : undefined;
  const chosenIds = (kind === 'ALL_PLOTS' ? exclusions : selected).map((p) => p.id);

  return (
    <Panel title={s.title}>
      {!editing ? (
        <div className="flex flex-col gap-3">
          {saved && <Notice tone="success">{s.saved}</Notice>}
          <p className="font-medium">
            {tariff.scopeKind === 'ALL_PLOTS' ? s.all : s.selected}
            <span className="font-normal text-ink-muted"> · {count}</span>
          </p>
          {tariff.scopeKind === 'ALL_PLOTS' ? (
            <section aria-label={s.exclusions} className="flex flex-col gap-1">
              <h3 className="text-sm font-medium text-ink-muted">{s.exclusions}</h3>
              {tariff.exclusions.length === 0 ? (
                <p className="text-ink-muted">{s.noExclusions}</p>
              ) : (
                <ul className="flex flex-col">
                  {tariff.exclusions.map((e) => (
                    <li
                      key={e.plotId}
                      className="flex flex-col border-b border-line-subtle py-2 last:border-b-0"
                    >
                      <span className="font-medium">
                        <UserContent>{e.number}</UserContent>{' '}
                        {e.archived && <Badge tone="neutral">{s.archivedMark}</Badge>}
                      </span>
                      <span className="text-sm text-ink-muted">
                        <UserContent>{e.reason}</UserContent>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          ) : tariff.selectedPlots.length === 0 ? (
            <p className="text-ink-muted">{s.noSelected}</p>
          ) : (
            <ul className="flex flex-wrap gap-2">
              {tariff.selectedPlots.map((p) => (
                <li key={p.plotId} className="rounded-control border border-line px-3 py-1">
                  <UserContent>{p.number}</UserContent>{' '}
                  {p.archived && <Badge tone="neutral">{s.archivedMark}</Badge>}
                </li>
              ))}
            </ul>
          )}
          {canManage && (
            <div>
              <Button variant="secondary" onClick={startEditing}>
                {s.edit}
              </Button>
            </div>
          )}
        </div>
      ) : (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <RadioGroup
            legend={s.kind}
            value={kind}
            onChange={setKind}
            options={[
              { value: 'ALL_PLOTS', label: s.all },
              { value: 'SELECTED_PLOTS', label: s.selected },
            ]}
          />
          {changeKindWarning && <Notice tone="warning">{changeKindWarning}</Notice>}
          <PlotPicker
            label={kind === 'ALL_PLOTS' ? s.addExclusion : s.addSelected}
            excludeIds={chosenIds}
            onPick={(plot) =>
              kind === 'ALL_PLOTS'
                ? setExclusions([...exclusions, { ...plot, reason: '' }])
                : setSelected([...selected, plot])
            }
          />
          {kind === 'ALL_PLOTS' ? (
            <ul className="flex flex-col gap-3">
              {exclusions.map((row, i) => (
                <li key={row.id} className="flex flex-col gap-2 rounded-control border border-line p-3">
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-medium">
                      {s.plot} <UserContent>{row.number}</UserContent>{' '}
                      {row.archived && <Badge tone="neutral">{s.archivedMark}</Badge>}
                    </span>
                    <Button
                      variant="secondary"
                      aria-label={format(s.removeRow, { number: row.number })}
                      onClick={() => setExclusions(exclusions.filter((_, j) => j !== i))}
                    >
                      {s.remove}
                    </Button>
                  </div>
                  {errors[`exclusions[${i}].plotId`] && (
                    <p role="alert" className="text-sm text-danger">
                      {errors[`exclusions[${i}].plotId`]}
                    </p>
                  )}
                  <TextArea
                    label={s.reason}
                    value={row.reason}
                    maxLength={MAX_REASON}
                    rows={2}
                    error={errors[`exclusions[${i}].reason`]}
                    onChange={(e) =>
                      setExclusions(
                        exclusions.map((x, j) => (j === i ? { ...x, reason: e.target.value } : x)),
                      )
                    }
                  />
                </li>
              ))}
            </ul>
          ) : (
            <ul className="flex flex-col gap-2">
              {selected.map((row, i) => (
                <li key={row.id} className="flex flex-col gap-1">
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-medium">
                      {s.plot} <UserContent>{row.number}</UserContent>{' '}
                      {row.archived && <Badge tone="neutral">{s.archivedMark}</Badge>}
                    </span>
                    <Button
                      variant="secondary"
                      aria-label={format(s.removeRow, { number: row.number })}
                      onClick={() => setSelected(selected.filter((_, j) => j !== i))}
                    >
                      {s.remove}
                    </Button>
                  </div>
                  {errors[`plotIds[${i}]`] && (
                    <p role="alert" className="text-sm text-danger">
                      {errors[`plotIds[${i}]`]}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          )}
          {notice && <Notice tone="danger">{notice}</Notice>}
          <div className="flex flex-wrap gap-3">
            <Button type="submit" disabled={update.isPending}>
              {update.isPending ? s.saving : s.save}
            </Button>
            <Button variant="secondary" onClick={() => setEditing(false)}>
              {s.cancel}
            </Button>
          </div>
        </form>
      )}
    </Panel>
  );
}
