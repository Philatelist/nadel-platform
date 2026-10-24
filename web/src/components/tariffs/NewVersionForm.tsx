import {
  getGetTariffQueryKey,
  getListTariffsQueryKey,
  useAddTariffVersion,
  type Tariff,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { useCan } from '../../me';
import type { FieldErrors } from '../../problem';
import { RecalculationStep } from '../recalculation/RecalculationStep';
import { useCauseDrivenChange } from '../recalculation/useCauseDrivenChange';
import { Button, Notice } from '../ui';
import {
  emptyVersionValues,
  validateVersion,
  VersionFields,
  versionSaveErrors,
  type VersionValues,
} from './VersionFields';

/** The first day of the month after today: a version that starts earlier can change charges already made. */
function nextMonthStart(today: string): string {
  const [year = 0, month = 1] = today.split('-').map(Number);
  return month === 12 ? `${year + 1}-01-01` : `${year}-${String(month + 1).padStart(2, '0')}-01`;
}

/**
 * "New version" form of a tariff card; starts from the newest version's basis, frequency and rate.
 * With RECALCULATION_RUN a version starting before next month is previewed first (`TARIFF_VERSION_ADD`) and saved
 * together with the recalculation; an empty plan is saved by the same call without a reason. Without that permission
 * the version is saved plainly, as before.
 */
export function NewVersionForm({ tariff }: { tariff: Tariff }) {
  const t = useT();
  const c = t.tariffs.card;
  const queryClient = useQueryClient();
  const latest = tariff.versions[0];
  const initial: VersionValues = latest
    ? { ...emptyVersionValues, basis: latest.basis, frequency: latest.frequency, rate: latest.rate }
    : emptyVersionValues;
  const [values, setValues] = useState<VersionValues>(initial);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string>();
  const [done, setDone] = useState<string>();
  const canRecalculate = useCan('RECALCULATION_RUN');

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: getGetTariffQueryKey(tariff.id) }),
      queryClient.invalidateQueries({ queryKey: getListTariffsQueryKey() }),
      queryClient.invalidateQueries({ predicate: (q) => String(q.queryKey[0]).includes('/tariffs') }),
    ]);

  const reset = () => setValues((current) => ({ ...current, validFrom: '', dueDate: '', note: '' }));
  const change = useCauseDrivenChange(async (result) => {
    await refresh();
    reset();
    setDone(result.booked ? c.addedRecalculated : c.added);
  });

  const add = useAddTariffVersion({
    mutation: {
      onSuccess: async () => {
        await refresh();
        reset();
      },
      onError: (error: unknown) => {
        const { fields, form } = versionSaveErrors(t, error);
        setErrors(fields);
        setFormError(form);
      },
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(undefined);
    setDone(undefined);
    const checked = validateVersion(values, t);
    setErrors(checked.errors ?? {});
    const version = checked.data;
    if (!version) return;
    if (canRecalculate && version.validFrom < nextMonthStart(kyivToday())) {
      change
        .start({ type: 'TARIFF_VERSION_ADD', tariffVersion: { tariffId: tariff.id, version } })
        .catch((error: unknown) => {
          const { fields, form } = versionSaveErrors(t, error);
          setErrors(fields);
          setFormError(form);
        });
    } else {
      add.mutate({ id: tariff.id, data: version });
    }
  }

  if (change.state.kind === 'preview') {
    return <RecalculationStep run={change.run} onBack={change.back} onBooked={change.finish} />;
  }
  const busy = add.isPending || change.state.kind === 'saving';

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      <VersionFields values={values} onChange={setValues} errors={errors} />
      {formError && <Notice tone="danger">{formError}</Notice>}
      {done && <Notice tone="success">{done}</Notice>}
      <div>
        <Button type="submit" disabled={busy}>
          {busy ? c.saving : c.add}
        </Button>
      </div>
    </form>
  );
}
