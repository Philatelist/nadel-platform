import {
  getGetTariffQueryKey,
  getListTariffsQueryKey,
  useArchiveTariff,
  type Tariff,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useT } from '../../i18n';
import { problemFieldErrors, problemMessage } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextField } from '../ui';
import { formatUser } from '../UserContent';

/** The day after `day` (`YYYY-MM-DD`), as `YYYY-MM-DD`; calendar arithmetic only. */
function nextDay(day: string): string {
  const date = new Date(`${day}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

/** The newest version start of the tariff (ISO dates compare as strings). */
function lastVersionStart(tariff: Tariff): string {
  return tariff.versions.reduce((latest, v) => (v.validFrom > latest ? v.validFrom : latest), '');
}

/**
 * «Archive tariff»: the user picks the first day the tariff no longer applies (`endsOn`); it must come after the
 * start of the last version. Mount it only while it should be open.
 */
export function ArchiveTariffDialog({ tariff, onClose }: { tariff: Tariff; onClose: () => void }) {
  const t = useT();
  const a = t.tariffs.archive;
  const queryClient = useQueryClient();
  const min = nextDay(lastVersionStart(tariff));
  const [endsOn, setEndsOn] = useState('');
  const [fieldError, setFieldError] = useState<string>();
  const [error, setError] = useState<string>();
  const archive = useArchiveTariff({
    mutation: {
      onSuccess: async () => {
        await queryClient.invalidateQueries({ queryKey: getGetTariffQueryKey(tariff.id) });
        await queryClient.invalidateQueries({ queryKey: getListTariffsQueryKey() });
        await queryClient.invalidateQueries({ predicate: (q) => String(q.queryKey[0]).includes('/tariffs') });
        onClose();
      },
      onError: (e: unknown) => {
        const fields = problemFieldErrors(t, e);
        if (fields.endsOn !== undefined) {
          setFieldError(fields.endsOn);
          setError(undefined);
        } else {
          setFieldError(undefined);
          setError(problemMessage(t, e));
        }
      },
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setError(undefined);
    setFieldError(undefined);
    if (endsOn === '') return setFieldError(t.validation.REQUIRED);
    archive.mutate({ id: tariff.id, data: { endsOn } });
  }

  return (
    <Modal title={a.title} onClose={onClose}>
      <form onSubmit={submit} noValidate className="mt-2 flex flex-col gap-4">
        <p className="text-[15px] text-ink-muted">{formatUser(a.text, { name: tariff.name })}</p>
        <TextField
          label={a.endsOn}
          type="date"
          required
          min={min}
          value={endsOn}
          hint={a.endsOnHint}
          error={fieldError}
          onChange={(e) => setEndsOn(e.target.value)}
        />
        {error && <Notice tone="danger">{error}</Notice>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {t.memberForm.cancel}
          </Button>
          <Button type="submit" disabled={archive.isPending}>
            {archive.isPending ? a.saving : a.confirm}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
