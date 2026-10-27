import { getGetSettingsQueryKey, useUpdateSettings, type CooperativeSettings } from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../i18n';
import { problemCode, problemFieldErrors, problemMessage } from '../problem';
import { Button, Notice, Panel, TextField } from './ui';

/** Trims and upper-cases an IBAN; the server also strips inner spaces and does the real check. */
function normalizeIban(value: string): string {
  return value.trim().toUpperCase();
}

/** The cooperative requisites printed on invoices; the EDRPOU code is shown but only the operator changes it. */
export function RequisitesPanel({ saved }: { saved: CooperativeSettings }) {
  const t = useT();
  const s = t.settings.requisites;
  const queryClient = useQueryClient();
  const [officialName, setOfficialName] = useState(saved.officialName ?? '');
  const [legalAddress, setLegalAddress] = useState(saved.legalAddress ?? '');
  const [bankName, setBankName] = useState(saved.bankName ?? '');
  const [iban, setIban] = useState(saved.iban ?? '');
  const [ibanError, setIbanError] = useState<string>();
  const [result, setResult] = useState<'saved' | { error: string }>();

  const update = useUpdateSettings({
    mutation: {
      onSuccess: (data) => {
        queryClient.setQueryData(getGetSettingsQueryKey(), data);
        setOfficialName(data.officialName ?? '');
        setLegalAddress(data.legalAddress ?? '');
        setBankName(data.bankName ?? '');
        setIban(data.iban ?? '');
        setIbanError(undefined);
        setResult('saved');
      },
      onError: (error) => {
        if (problemCode(error) === 'IBAN_INVALID' || problemFieldErrors(t, error).iban !== undefined) {
          setIbanError(s.errors.ibanInvalid);
        } else {
          setResult({ error: problemMessage(t, error) });
        }
      },
    },
  });

  const changed =
    officialName.trim() !== (saved.officialName ?? '') ||
    legalAddress.trim() !== (saved.legalAddress ?? '') ||
    bankName.trim() !== (saved.bankName ?? '') ||
    normalizeIban(iban) !== (saved.iban ?? '');

  const edit = (setter: (value: string) => void) => (e: { target: { value: string } }) => {
    setter(e.target.value);
    setResult(undefined);
    setIbanError(undefined);
  };

  return (
    <Panel title={s.title}>
      <form
        className="flex max-w-xl flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          setResult(undefined);
          setIbanError(undefined);
          update.mutate({
            data: {
              officialName: officialName.trim(),
              legalAddress: legalAddress.trim(),
              bankName: bankName.trim(),
              iban: normalizeIban(iban),
            },
          });
        }}
      >
        <p className="text-sm text-ink-muted">{s.text}</p>
        <TextField label={s.officialName} value={officialName} onChange={edit(setOfficialName)} />
        <TextField label={s.legalAddress} value={legalAddress} onChange={edit(setLegalAddress)} />
        <TextField label={s.bankName} value={bankName} onChange={edit(setBankName)} />
        <TextField label={s.iban} value={iban} error={ibanError} onChange={edit(setIban)} />
        <TextField label={s.edrpou} value={saved.edrpou ?? ''} readOnly hint={s.edrpouHint} />
        {result === 'saved' && <Notice tone="success">{t.settings.saved}</Notice>}
        {typeof result === 'object' && <Notice tone="danger">{result.error}</Notice>}
        <div>
          <Button type="submit" disabled={!changed || update.isPending}>
            {s.save}
          </Button>
        </div>
      </form>
    </Panel>
  );
}
