import {
  getGetTariffQueryKey,
  getListTariffsQueryKey,
  useAddTariffVersion,
  type Tariff,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useT } from '../../i18n';
import type { FieldErrors } from '../../problem';
import { Button, Notice } from '../ui';
import {
  emptyVersionValues,
  validateVersion,
  VersionFields,
  versionSaveErrors,
  type VersionValues,
} from './VersionFields';

/** "New version" form of a tariff card; starts from the newest version's basis, frequency and rate. */
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

  const add = useAddTariffVersion({
    mutation: {
      onSuccess: async () => {
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: getGetTariffQueryKey(tariff.id) }),
          queryClient.invalidateQueries({ queryKey: getListTariffsQueryKey() }),
        ]);
        setValues({ ...values, validFrom: '', dueDate: '', note: '' });
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
    const checked = validateVersion(values, t);
    setErrors(checked.errors ?? {});
    if (checked.data) add.mutate({ id: tariff.id, data: checked.data });
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      <VersionFields values={values} onChange={setValues} errors={errors} />
      {formError && <Notice tone="danger">{formError}</Notice>}
      <div>
        <Button type="submit" disabled={add.isPending}>
          {add.isPending ? c.saving : c.add}
        </Button>
      </div>
    </form>
  );
}
