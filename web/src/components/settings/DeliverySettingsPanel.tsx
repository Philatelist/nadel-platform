import {
  getGetDeliverySettingsQueryKey,
  useGetDeliverySettings,
  useUpdateDeliverySettings,
  type DeliverySettings,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../../i18n';
import { problemMessage } from '../../problem';
import { QueryBoundary } from '../QueryBoundary';
import { Badge, Button, Notice, Panel, TextField } from '../ui';
import { SmtpForm } from './SmtpForm';
import { TelegramBotForm } from './TelegramBotForm';

/** The cooperative mailbox and Telegram bot (SETTINGS_MANAGE; the caller checks the permission). */
export function DeliverySettingsPanel() {
  const t = useT();
  const query = useGetDeliverySettings();
  return (
    <Panel title={t.deliverySettings.title}>
      <QueryBoundary query={query}>{(data) => <Loaded saved={data} />}</QueryBoundary>
    </Panel>
  );
}

function Loaded({ saved }: { saved: DeliverySettings }) {
  const s = useT().deliverySettings;
  return (
    <div className="flex flex-col gap-6">
      <p className="text-sm text-ink-muted">{s.text}</p>
      <section className="flex flex-col gap-3.5">
        <h3 className="flex items-center gap-2 text-[15px] font-semibold">
          {s.email.title}
          <ConfiguredBadge configured={saved.email.configured} />
        </h3>
        <SmtpForm saved={saved.email} />
      </section>
      <section className="flex flex-col gap-3.5">
        <h3 className="flex items-center gap-2 text-[15px] font-semibold">
          {s.telegram.title}
          <ConfiguredBadge configured={saved.telegram.configured} />
        </h3>
        <TelegramBotForm saved={saved.telegram} />
      </section>
      <DeliveryOptionsForm saved={saved} />
    </div>
  );
}

function ConfiguredBadge({ configured }: { configured: boolean }) {
  const s = useT().deliverySettings;
  return (
    <Badge tone={configured ? 'success' : 'neutral'}>{configured ? s.configured : s.notConfigured}</Badge>
  );
}

const RATE_RE = /^\d{1,2}$/;

/** The e-mail rate and the payment notification switch; each is sent alone, the mailbox and the bot stay as they are. */
function DeliveryOptionsForm({ saved }: { saved: DeliverySettings }) {
  const t = useT();
  const s = t.deliverySettings;
  const o = s.options;
  const queryClient = useQueryClient();
  const [rate, setRate] = useState(String(saved.emailRatePerSecond));
  const [notify, setNotify] = useState(saved.notifyPayments);
  const [submitted, setSubmitted] = useState(false);
  const [result, setResult] = useState<'saved' | { error: string }>();

  const update = useUpdateDeliverySettings({
    mutation: {
      onSuccess: (data) => {
        queryClient.setQueryData(getGetDeliverySettingsQueryKey(), data);
        setRate(String(data.emailRatePerSecond));
        setNotify(data.notifyPayments);
        setSubmitted(false);
        setResult('saved');
      },
      onError: (error) => setResult({ error: problemMessage(t, error) }),
    },
  });

  const rateValid = RATE_RE.test(rate.trim()) && Number(rate.trim()) >= 1 && Number(rate.trim()) <= 10;
  const changed = rate.trim() !== String(saved.emailRatePerSecond) || notify !== saved.notifyPayments;

  return (
    <form
      className="flex max-w-xl flex-col gap-4"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        setSubmitted(true);
        setResult(undefined);
        if (!rateValid) return;
        update.mutate({ data: { emailRatePerSecond: Number(rate.trim()), notifyPayments: notify } });
      }}
    >
      <h3 className="text-[15px] font-semibold">{o.title}</h3>
      <TextField
        label={o.emailRate}
        inputMode="numeric"
        hint={o.emailRateHint}
        value={rate}
        error={submitted && !rateValid ? o.emailRateInvalid : undefined}
        onChange={(e) => {
          setRate(e.target.value);
          setResult(undefined);
        }}
      />
      <label className="flex items-center gap-3">
        <input
          type="checkbox"
          role="switch"
          className="size-5"
          checked={notify}
          onChange={(e) => {
            setNotify(e.target.checked);
            setResult(undefined);
          }}
        />
        <span>{o.notifyPayments}</span>
      </label>
      <p className="text-sm text-ink-muted">{o.notifyPaymentsHint}</p>
      {result === 'saved' && <Notice tone="success">{s.saved}</Notice>}
      {typeof result === 'object' && <Notice tone="danger">{result.error}</Notice>}
      <div>
        <Button type="submit" disabled={!changed || update.isPending}>
          {update.isPending ? s.saving : o.save}
        </Button>
      </div>
    </form>
  );
}
