import {
  PaymentSource,
  useEnterPayment,
  type PaymentEntryRequest,
  type PaymentLineInput,
  type PaymentPartProposal,
  type SavedPayment,
} from '@nadel/api-client';
import { isZeroMoney, normalizeMoneyInput } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { problemCode } from '../../problem';
import type { PickedMember } from '../ownership/MemberPicker';
import { Button, LoadingState, Notice, Panel, Select, TextArea, TextField } from '../ui';
import { PayerPicker } from './PayerPicker';
import { PaymentSaved } from './PaymentSaved';
import { PlotSelect } from './PlotSelect';
import { ProposalTable } from './ProposalTable';
import { invalidateAfterPayment } from './paymentInvalidation';
import { paymentErrors } from './paymentProblems';
import { usePaymentPreview } from './usePaymentPreview';

const MAX_NOTE = 200;
const MAX_REFERENCE = 64;
const SOURCES = Object.values(PaymentSource);

/** The typed amounts per charge once the treasurer has changed the proposal; null while the proposal stands. */
type Edits = Readonly<Record<string, string>> | null;

interface Lines {
  /** Absent while the server's proposal stands. */
  lines: PaymentLineInput[] | undefined;
  /** Charge ids in the order of `lines` as they were sent. */
  ids: string[];
  /** Typed texts that are not an amount, by charge id. */
  invalid: Set<string>;
}

function linesOf(edits: Edits): Lines {
  if (edits === null) return { lines: undefined, ids: [], invalid: new Set() };
  const lines: PaymentLineInput[] = [];
  const ids: string[] = [];
  const invalid = new Set<string>();
  for (const [chargeId, text] of Object.entries(edits)) {
    const amount = normalizeMoneyInput(text);
    if (amount === undefined) invalid.add(chargeId);
    else if (!isZeroMoney(amount)) {
      lines.push({ chargeId, amount });
      ids.push(chargeId);
    }
  }
  return { lines, ids, invalid };
}

/**
 * Enter one payment for one plot: the plot, source, date, amount, payer, note and bank reference; the server's
 * proposal of how the money is allocated (editable); Save with the digest of the proposal that was shown.
 * Single-plot mode; the split mode of a later slice is another body inside the same shell.
 */
export function PaymentForm({ initialPlotId }: { initialPlotId?: string }) {
  const t = useT();
  const f = t.payments.form;
  const queryClient = useQueryClient();
  const today = kyivToday();

  const [plotId, setPlotId] = useState(initialPlotId);
  const [source, setSource] = useState<PaymentSource>('CASH');
  const [receivedOn, setReceivedOn] = useState(today);
  const [amountText, setAmountText] = useState('');
  const [payer, setPayer] = useState<PickedMember | null>(null);
  const [note, setNote] = useState('');
  const [reference, setReference] = useState('');
  const [edits, setEdits] = useState<Edits>(null);
  const [touched, setTouched] = useState<ReadonlySet<string>>(new Set());
  const [outdated, setOutdated] = useState(false);
  const [saveError, setSaveError] = useState<unknown>();
  const [saved, setSaved] = useState<{ payment: SavedPayment; bankReference?: string }>();

  const amount = normalizeMoneyInput(amountText);
  const typed = linesOf(edits);
  const request: PaymentEntryRequest | undefined =
    plotId !== undefined && amount !== undefined && receivedOn !== '' && typed.invalid.size === 0
      ? { plotId, amount, receivedOn, source, ...(typed.lines === undefined ? {} : { lines: typed.lines }) }
      : undefined;

  const preview = usePaymentPreview(request, plotId);
  const enter = useEnterPayment();
  const errors = paymentErrors(t, saveError ?? preview.error);
  const ready = plotId !== undefined && amount !== undefined && receivedOn !== '';
  const part: PaymentPartProposal | undefined = ready ? preview.shown?.parts[0] : undefined;

  const lineErrors: Record<string, string> = {};
  for (const [index, text] of Object.entries(errors.lines)) {
    const id = typed.ids[Number(index)];
    if (id !== undefined) lineErrors[id] = text;
  }
  const amountErrors: Record<string, string> = {};
  for (const id of typed.invalid) amountErrors[id] = t.payments.proposal.amountInvalid;

  function resetProposal() {
    setEdits(null);
    setTouched(new Set());
    setOutdated(false);
    setSaveError(undefined);
  }

  function seed(): Record<string, string> {
    return Object.fromEntries(
      (part?.lines ?? [])
        .filter((line) => !isZeroMoney(line.proposed))
        .map((line) => [line.chargeId, line.proposed]),
    );
  }

  function edit(chargeId: string, text: string) {
    setEdits({ ...(edits ?? seed()), [chargeId]: text });
    setTouched(new Set(touched).add(chargeId));
    setSaveError(undefined);
  }

  function add(chargeId: string) {
    edit(chargeId, '0.00');
  }

  const canSave =
    request !== undefined && preview.current !== undefined && !preview.pending && !enter.isPending;

  function save() {
    if (request === undefined || preview.current === undefined) return;
    const data: PaymentEntryRequest = {
      ...request,
      digest: preview.current.digest,
      ...(payer === null ? {} : { payerMemberId: payer.id }),
      ...(note.trim() === '' ? {} : { payerNote: note.trim() }),
      ...(source === 'BANK' && reference.trim() !== '' ? { bankReference: reference.trim() } : {}),
    };
    setOutdated(false);
    setSaveError(undefined);
    enter.mutate(
      { data, params: { preview: false } },
      {
        onSuccess: async (result) => {
          const payment = result.payments[0];
          await invalidateAfterPayment(queryClient);
          if (payment !== undefined) {
            setSaved({ payment, bankReference: data.bankReference });
          }
        },
        onError: (error: unknown) => {
          if (problemCode(error) === 'PREVIEW_OUTDATED') {
            setOutdated(true);
            void preview.refresh();
          } else {
            setSaveError(error);
          }
        },
      },
    );
  }

  function another() {
    setSaved(undefined);
    setAmountText('');
    setPayer(null);
    setNote('');
    setReference('');
    setReceivedOn(kyivToday());
    resetProposal();
  }

  if (saved !== undefined) {
    return <PaymentSaved payment={saved.payment} bankReference={saved.bankReference} onAnother={another} />;
  }

  const showNotice = errors.notice !== undefined && !outdated;
  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        if (canSave) save();
      }}
    >
      <Panel>
        <PlotSelect
          label={f.plot}
          plotId={plotId}
          changeLabel={f.changePlot}
          onChange={(id) => {
            setPlotId(id);
            setPayer(null);
            resetProposal();
          }}
        />
        {errors.fields.plotId && <p className="text-sm text-danger">{errors.fields.plotId}</p>}
        <div className="grid gap-4 sm:grid-cols-2">
          <Select
            label={f.source}
            value={source}
            onChange={(e) => setSource(e.target.value as PaymentSource)}
          >
            {SOURCES.map((value) => (
              <option key={value} value={value}>
                {t.payments.source[value]}
              </option>
            ))}
          </Select>
          <TextField
            label={f.receivedOn}
            type="date"
            value={receivedOn}
            onChange={(e) => {
              setReceivedOn(e.target.value);
              setSaveError(undefined);
            }}
            error={errors.fields.receivedOn}
          />
          <TextField
            label={f.amount}
            inputMode="decimal"
            value={amountText}
            onChange={(e) => {
              setAmountText(e.target.value);
              resetProposal();
            }}
            error={
              errors.fields.amount ??
              (amountText.trim() !== '' && amount === undefined ? f.amountInvalid : undefined)
            }
          />
          {source === 'BANK' && (
            <TextField
              label={f.bankReference}
              hint={f.bankReferenceHint}
              maxLength={MAX_REFERENCE}
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              error={errors.fields.bankReference}
            />
          )}
        </div>
        {receivedOn !== '' && receivedOn !== today && <Notice tone="warning">{f.dateWarning}</Notice>}
        <PayerPicker plotId={plotId} value={payer} error={errors.fields.payerMemberId} onChange={setPayer} />
        <TextArea
          label={f.note}
          hint={f.noteHint}
          maxLength={MAX_NOTE}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          error={errors.fields.payerNote}
        />
      </Panel>
      <Panel title={t.payments.proposal.title}>
        {outdated && <Notice tone="warning">{t.payments.proposal.outdated}</Notice>}
        {showNotice && <Notice tone="danger">{errors.notice}</Notice>}
        {!ready ? (
          <p className="text-ink-muted">{f.needPlotAndAmount}</p>
        ) : part === undefined ? (
          preview.pending ? (
            <LoadingState message={f.previewing} />
          ) : null
        ) : (
          <div aria-busy={preview.pending}>
            <ProposalTable
              part={part}
              values={edits}
              touched={touched}
              lineErrors={lineErrors}
              amountError={amountErrors}
              onEdit={edit}
              onAdd={add}
            />
          </div>
        )}
      </Panel>
      <div>
        <Button type="submit" disabled={!canSave}>
          {enter.isPending ? f.saving : f.save}
        </Button>
      </div>
    </form>
  );
}
