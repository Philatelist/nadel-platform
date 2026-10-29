import {
  getGetDeliverySettingsQueryKey,
  useTestDeliveryEmail,
  useUpdateDeliverySettings,
  type DeliveryEmailSettings,
  type DeliveryEmailSettingsUpdate,
  type DeliveryFailureCode,
  type DeliveryTestEmailResult,
  type SmtpSecurity,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../../i18n';
import { problemMessage } from '../../problem';
import { Button, Notice, Select, TextField } from '../ui';

const PORT_RE = /^\d{1,5}$/;
const SECURITIES = ['STARTTLS', 'SSL'] as const satisfies readonly SmtpSecurity[];

function isSecurity(value: string): value is SmtpSecurity {
  return (SECURITIES as readonly string[]).includes(value);
}

/** The mailbox: the saved password is never shown; an empty password field keeps it. */
export function SmtpForm({ saved }: { saved: DeliveryEmailSettings }) {
  const t = useT();
  const s = t.deliverySettings;
  const e = s.email;
  const queryClient = useQueryClient();
  const [host, setHost] = useState(saved.host ?? '');
  const [port, setPort] = useState(saved.port === undefined ? '' : String(saved.port));
  const [security, setSecurity] = useState<string>(saved.security ?? '');
  const [user, setUser] = useState(saved.user ?? '');
  const [password, setPassword] = useState('');
  const [fromAddress, setFromAddress] = useState(saved.fromAddress ?? '');
  const [fromName, setFromName] = useState(saved.fromName ?? '');
  const [submitted, setSubmitted] = useState(false);
  const [result, setResult] = useState<'saved' | { error: string }>();
  const [test, setTest] = useState<DeliveryTestEmailResult | { error: string }>();

  const update = useUpdateDeliverySettings({
    mutation: {
      onSuccess: (data) => {
        queryClient.setQueryData(getGetDeliverySettingsQueryKey(), data);
        setHost(data.email.host ?? '');
        setPort(data.email.port === undefined ? '' : String(data.email.port));
        setSecurity(data.email.security ?? '');
        setUser(data.email.user ?? '');
        setFromAddress(data.email.fromAddress ?? '');
        setFromName(data.email.fromName ?? '');
        setPassword('');
        setSubmitted(false);
        setResult('saved');
      },
      onError: (error) => setResult({ error: problemMessage(t, error) }),
    },
  });
  const sendTest = useTestDeliveryEmail({
    mutation: {
      onSuccess: (data) => setTest(data),
      onError: (error) => setTest({ error: problemMessage(t, error) }),
    },
  });

  const portValid = port.trim() === '' || (PORT_RE.test(port.trim()) && Number(port.trim()) <= 65535);
  const dirty =
    host.trim() !== (saved.host ?? '') ||
    port.trim() !== (saved.port === undefined ? '' : String(saved.port)) ||
    security !== (saved.security ?? '') ||
    user.trim() !== (saved.user ?? '') ||
    password !== '' ||
    fromAddress.trim() !== (saved.fromAddress ?? '') ||
    fromName.trim() !== (saved.fromName ?? '');

  const edit = (setter: (value: string) => void) => (event: { target: { value: string } }) => {
    setter(event.target.value);
    setResult(undefined);
    setTest(undefined);
  };

  function body(): DeliveryEmailSettingsUpdate {
    const email: DeliveryEmailSettingsUpdate = {};
    if (host.trim()) email.host = host.trim();
    if (port.trim()) email.port = Number(port.trim());
    if (isSecurity(security)) email.security = security;
    if (user.trim()) email.user = user.trim();
    if (password) email.password = password;
    if (fromAddress.trim()) email.fromAddress = fromAddress.trim();
    if (fromName.trim()) email.fromName = fromName.trim();
    return email;
  }

  return (
    <form
      className="flex max-w-xl flex-col gap-4"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        setSubmitted(true);
        setResult(undefined);
        if (!portValid) return;
        update.mutate({ data: { email: body() } });
      }}
    >
      <TextField label={e.host} value={host} onChange={edit(setHost)} />
      <TextField
        label={e.port}
        inputMode="numeric"
        value={port}
        error={submitted && !portValid ? e.portInvalid : undefined}
        onChange={edit(setPort)}
      />
      <Select label={e.security} value={security} onChange={edit(setSecurity)}>
        {SECURITIES.map((value) => (
          <option key={value} value={value}>
            {e[value]}
          </option>
        ))}
        <option value="">{e.securityNone}</option>
      </Select>
      <TextField label={e.user} autoComplete="off" value={user} onChange={edit(setUser)} />
      <TextField
        label={e.password}
        type="password"
        autoComplete="new-password"
        placeholder={saved.passwordSet ? e.passwordSaved : undefined}
        value={password}
        onChange={edit(setPassword)}
      />
      <TextField label={e.fromAddress} type="email" value={fromAddress} onChange={edit(setFromAddress)} />
      <TextField label={e.fromName} value={fromName} onChange={edit(setFromName)} />
      {result === 'saved' && <Notice tone="success">{s.saved}</Notice>}
      {typeof result === 'object' && <Notice tone="danger">{result.error}</Notice>}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={!dirty || update.isPending}>
          {update.isPending ? s.saving : e.save}
        </Button>
        <Button
          type="button"
          variant="secondary"
          disabled={dirty || !saved.configured || sendTest.isPending}
          onClick={() => {
            setTest(undefined);
            sendTest.mutate();
          }}
        >
          {sendTest.isPending ? e.sendingTest : e.sendTest}
        </Button>
      </div>
      {dirty && saved.configured && <p className="text-sm text-ink-muted">{e.saveFirst}</p>}
      {test && <TestLetterResult result={test} />}
    </form>
  );
}

function TestLetterResult({ result }: { result: DeliveryTestEmailResult | { error: string } }) {
  const t = useT();
  const e = t.deliverySettings.email;
  if ('error' in result) return <Notice tone="danger">{result.error}</Notice>;
  const reply = result.serverReply ? format(e.serverReply, { reply: result.serverReply }) : undefined;
  if (result.accepted) {
    return (
      <Notice tone="success">
        {e.testAccepted}
        {reply && <> {reply}</>}
      </Notice>
    );
  }
  const code: DeliveryFailureCode = result.failureCode ?? 'CONNECTION_FAILED';
  return (
    <Notice tone="danger">
      {format(e.testRefused, { reason: t.deliveries.reason[code] })}
      {reply && <> {reply}</>}
    </Notice>
  );
}
