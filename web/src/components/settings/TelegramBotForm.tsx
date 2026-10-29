import {
  getGetDeliverySettingsQueryKey,
  useTestDeliveryTelegram,
  useUpdateDeliverySettings,
  type DeliveryTelegramSettings,
  type DeliveryTestTelegramResult,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../../i18n';
import { problemMessage } from '../../problem';
import { Button, Notice, TextField } from '../ui';

/** The bot token is write-only: only its last four characters come back, an empty field keeps the saved token. */
export function TelegramBotForm({ saved }: { saved: DeliveryTelegramSettings }) {
  const t = useT();
  const s = t.deliverySettings;
  const g = s.telegram;
  const queryClient = useQueryClient();
  const [token, setToken] = useState('');
  const [result, setResult] = useState<'saved' | { error: string }>();
  const [check, setCheck] = useState<DeliveryTestTelegramResult | { error: string }>();

  const update = useUpdateDeliverySettings({
    mutation: {
      onSuccess: (data) => {
        queryClient.setQueryData(getGetDeliverySettingsQueryKey(), data);
        setToken('');
        setResult('saved');
      },
      onError: (error) => setResult({ error: problemMessage(t, error) }),
    },
  });
  const verify = useTestDeliveryTelegram({
    mutation: {
      onSuccess: (data) => setCheck(data),
      onError: (error) => setCheck({ error: problemMessage(t, error) }),
    },
  });

  const dirty = token.trim() !== '';

  return (
    <form
      className="flex max-w-xl flex-col gap-4"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        setResult(undefined);
        if (dirty) update.mutate({ data: { telegram: { botToken: token.trim() } } });
      }}
    >
      <p className="text-sm text-ink-muted">{g.instruction}</p>
      {saved.botUsername && <p>{format(g.botName, { name: saved.botUsername })}</p>}
      <TextField
        label={g.token}
        type="password"
        autoComplete="new-password"
        placeholder={saved.tokenLast4 ? `••••${saved.tokenLast4}` : undefined}
        value={token}
        onChange={(e) => {
          setToken(e.target.value);
          setResult(undefined);
          setCheck(undefined);
        }}
      />
      {result === 'saved' && <Notice tone="success">{s.saved}</Notice>}
      {typeof result === 'object' && <Notice tone="danger">{result.error}</Notice>}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={!dirty || update.isPending}>
          {update.isPending ? s.saving : g.save}
        </Button>
        <Button
          type="button"
          variant="secondary"
          disabled={dirty || !saved.configured || verify.isPending}
          onClick={() => {
            setCheck(undefined);
            verify.mutate();
          }}
        >
          {verify.isPending ? g.checking : g.check}
        </Button>
      </div>
      {dirty && saved.configured && <p className="text-sm text-ink-muted">{g.saveFirst}</p>}
      {check && <BotCheckResult result={check} />}
    </form>
  );
}

function BotCheckResult({ result }: { result: DeliveryTestTelegramResult | { error: string } }) {
  const g = useT().deliverySettings.telegram;
  if ('error' in result) return <Notice tone="danger">{result.error}</Notice>;
  if (result.ok) {
    return <Notice tone="success">{format(g.checkOk, { name: result.botUsername ?? '' })}</Notice>;
  }
  return <Notice tone="danger">{g[result.code ?? 'TELEGRAM_UNAVAILABLE']}</Notice>;
}
