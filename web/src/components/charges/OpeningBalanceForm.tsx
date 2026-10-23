import { useEnterOpeningBalance, useListChargeTypes, type ChargeType } from '@nadel/api-client';
import { format, formatDay, normalizeMoneyInput } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { chargeTypeName } from '../../chargeTypeName';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { QueryBoundary } from '../QueryBoundary';
import { Button, Notice, Select, TextArea, TextField } from '../ui';
import { invalidateAfterCharge } from './chargeInvalidation';

const MAX_REASON = 500;

function OpeningBalanceFields({
  plotId,
  types,
  onDone,
  onClose,
}: {
  plotId: string;
  types: ChargeType[];
  onDone: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const o = t.charges.opening;
  const queryClient = useQueryClient();
  const today = kyivToday();
  const [chargeTypeId, setChargeTypeId] = useState(types[0]?.id ?? '');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState(today);
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const trimmed = amount.trim();
  const isNegative = trimmed.startsWith('-');
  const normalized = normalizeMoneyInput(isNegative ? trimmed.slice(1) : amount);
  // A well-formed amount that is negative or zero gets the catalog text of AMOUNT_NOT_POSITIVE (text check, no arithmetic).
  const isNotPositive = normalized !== undefined && (isNegative || normalized.replace(/[0.]/g, '') === '');
  const amountError =
    normalized === undefined ? o.amountInvalid : isNotPositive ? t.problem.AMOUNT_NOT_POSITIVE : undefined;

  const enter = useEnterOpeningBalance({
    mutation: {
      onSuccess: async () => {
        await invalidateAfterCharge(queryClient, plotId);
        onDone();
      },
      onError: (error: unknown) => {
        const fields = problemFieldErrors(t, error);
        setServerErrors(fields);
        setNotice(Object.keys(fields).length > 0 ? undefined : problemMessage(t, error));
      },
    },
  });

  function edit(setter: (value: string) => void) {
    return (value: string) => {
      setServerErrors({});
      setNotice(undefined);
      setter(value);
    };
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (normalized === undefined || isNotPositive || chargeTypeId === '' || date === '') return;
    enter.mutate({
      id: plotId,
      data: {
        chargeTypeId,
        amount: normalized,
        date,
        ...(reason.trim() === '' ? {} : { reason: reason.trim() }),
      },
    });
  }

  return (
    <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
      {notice && <Notice tone="danger">{notice}</Notice>}
      <Select
        label={o.type}
        value={chargeTypeId}
        error={serverErrors.chargeTypeId}
        onChange={(e) => edit(setChargeTypeId)(e.target.value)}
      >
        {types.map((type) => (
          <option key={type.id} value={type.id}>
            {chargeTypeName(type, t)}
          </option>
        ))}
      </Select>
      <TextField
        label={o.amount}
        inputMode="decimal"
        value={amount}
        hint={o.amountHint}
        error={(submitted ? amountError : undefined) ?? serverErrors.amount}
        onChange={(e) => edit(setAmount)(e.target.value)}
      />
      <TextField
        label={o.date}
        type="date"
        value={date}
        max={today}
        hint={format(o.dateHint, { date: formatDay(today, locale) })}
        error={serverErrors.date}
        onChange={(e) => edit(setDate)(e.target.value)}
      />
      <TextArea
        label={o.reason}
        value={reason}
        rows={3}
        maxLength={MAX_REASON}
        hint={o.reasonHint}
        error={serverErrors.reason}
        onChange={(e) => edit(setReason)(e.target.value)}
      />
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={onClose}>
          {t.charges.reverse.cancel}
        </Button>
        <Button type="submit" disabled={enter.isPending}>
          {enter.isPending ? o.saving : o.submit}
        </Button>
      </div>
    </form>
  );
}

/** Enters an opening balance of the plot (a debt that exists before the system); mount it only while open. */
export function OpeningBalanceForm({
  plotId,
  onDone,
  onClose,
}: {
  plotId: string;
  onDone: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const types = useListChargeTypes();
  return (
    <Modal title={t.charges.opening.title} onClose={onClose}>
      <QueryBoundary
        query={types}
        isEmpty={(data) => data.filter((type) => type.active).length === 0}
        emptyMessage={t.charges.opening.noTypes}
      >
        {(data) => (
          <OpeningBalanceFields
            plotId={plotId}
            types={data.filter((type) => type.active)}
            onDone={onDone}
            onClose={onClose}
          />
        )}
      </QueryBoundary>
    </Modal>
  );
}
