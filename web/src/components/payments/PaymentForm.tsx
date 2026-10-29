import {
  PaymentSource,
  type PaymentSplitPartInput,
  useEnterPayment,
  type PaymentEntryRequest,
  type PaymentPartProposal,
  type SavedPayment,
} from '@nadel/api-client';
import { isZeroMoney, normalizeMoneyInput } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { problemCode } from '../../problem';
import type { PickedMember } from '../ownership/MemberPicker';
import { Button, LoadingState, Notice, Panel, RadioGroup, Select, TextArea, TextField } from '../ui';
import { PayerPicker } from './PayerPicker';
import { PaymentGroupSaved } from './PaymentGroupSaved';
import { PaymentSaved } from './PaymentSaved';
import { PlotSelect } from './PlotSelect';
import { ProposalTable } from './ProposalTable';
import { SplitParts, type SplitEdits } from './SplitParts';
import { SplitPayerStep } from './SplitPayerStep';
import { linesOf, type Edits } from './paymentLines';
import { invalidateAfterPayment } from './paymentInvalidation';
import { allocationProblemTexts, paymentErrors } from './paymentProblems';
import { usePaymentPreview } from './usePaymentPreview';

const MAX_NOTE = 200;
const MAX_REFERENCE = 64;
const SOURCES = Object.values(PaymentSource);

type Mode = 'single' | 'split';

/** The parts to send once the treasurer has edited a part, and the typed texts that are not an amount. */
function splitParts(edits: SplitEdits | null): {
  parts: PaymentSplitPartInput[] | undefined;
  invalid: Set<string>;
  /** Charge ids of each part's lines in the order sent, by plot id. */
  lineIds: Record<string, string[]>;
} {
  const invalid = new Set<string>();
  const lineIds: Record<string, string[]> = {};
  if (edits === null) return { parts: undefined, invalid, lineIds };
  const parts = edits.order.map((plotId) => {
    const part: PaymentSplitPartInput = { plotId };
    const text = edits.amounts?.[plotId];
    if (text !== undefined) {
      const amount = normalizeMoneyInput(text);
      if (amount === undefined) invalid.add(plotId);
      else part.amount = amount;
    }
    const typedLines = edits.lines[plotId];
    if (typedLines !== undefined) {
      const typed = linesOf(typedLines);
      for (const chargeId of typed.invalid) invalid.add(`${plotId}/${chargeId}`);
      part.lines = typed.lines;
      lineIds[plotId] = typed.ids;
    }
    return part;
  });
  return { parts, invalid, lineIds };
}

/**
 * Enter one payment: the source, date, amount, payer, note and bank reference; the server's proposal of how the money
 * is allocated (editable); Save with the digest of the proposal that was shown. Two modes share the shell: one plot,
 * or one payment split over the current plots of one payer.
 */
export function PaymentForm({ initialPlotId }: { initialPlotId?: string }) {
  const t = useT();
  const { locale } = useLocale();
  const f = t.payments.form;
  const queryClient = useQueryClient();
  const today = kyivToday();

  const [mode, setMode] = useState<Mode>('single');
  const [plotId, setPlotId] = useState(initialPlotId);
  const [splitPayer, setSplitPayer] = useState<PickedMember | null>(null);
  const [splitEdits, setSplitEdits] = useState<SplitEdits | null>(null);
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
  const [savedGroup, setSavedGroup] = useState<{
    payments: SavedPayment[];
    plotNumbers: Record<string, string>;
  }>();

  const amount = normalizeMoneyInput(amountText);
  const typed = linesOf(edits);
  const isSplit = mode === 'split';
  const typedParts = splitParts(splitEdits);
  const common = amount !== undefined && receivedOn !== '';
  const request: PaymentEntryRequest | undefined = isSplit
    ? splitPayer !== null && common && typedParts.invalid.size === 0
      ? {
          amount,
          receivedOn,
          source,
          split: {
            payerMemberId: splitPayer.id,
            ...(typedParts.parts === undefined ? {} : { parts: typedParts.parts }),
          },
        }
      : undefined
    : plotId !== undefined && common && typed.invalid.size === 0
      ? { plotId, amount, receivedOn, source, ...(typed.lines === undefined ? {} : { lines: typed.lines }) }
      : undefined;

  const owner = isSplit ? splitPayer?.id : plotId;
  const preview = usePaymentPreview(request, owner);
  const enter = useEnterPayment();
  const errors = paymentErrors(t, saveError ?? preview.error);
  const problems = allocationProblemTexts(t, preview.shown?.problems, isSplit ? undefined : plotId, {
    locale,
    open: Object.fromEntries(
      (preview.shown?.parts ?? []).map((p) => [
        p.plotId,
        Object.fromEntries(p.lines.map((l) => [l.chargeId, l.open])),
      ]),
    ),
  });
  const ready = (isSplit ? splitPayer !== null : plotId !== undefined) && common;
  const part: PaymentPartProposal | undefined = ready && !isSplit ? preview.shown?.parts[0] : undefined;
  const splitShown = ready && isSplit ? preview.shown : undefined;

  const lineErrors: Record<string, string> = { ...problems.lines[plotId ?? ''] };
  for (const [index, text] of Object.entries(errors.lines)) {
    const id = typed.ids[Number(index)];
    if (id !== undefined) lineErrors[id] = text;
  }
  const amountErrors: Record<string, string> = {};
  for (const id of typed.invalid) amountErrors[id] = t.payments.proposal.amountInvalid;

  const partAmountErrors: Record<string, string> = {};
  const partLineErrors: Record<string, Record<string, string>> = {};
  if (isSplit) {
    for (const key of typedParts.invalid) {
      if (!key.includes('/')) partAmountErrors[key] = t.payments.proposal.amountInvalid;
      else {
        const [plot = '', charge = ''] = key.split('/');
        (partLineErrors[plot] ??= {})[charge] = t.payments.proposal.amountInvalid;
      }
    }
    for (const [plot, byCharge] of Object.entries(problems.lines)) {
      partLineErrors[plot] = { ...byCharge, ...partLineErrors[plot] };
    }
    const order = splitEdits?.order ?? [];
    for (const [index, text] of Object.entries(errors.parts.amount)) {
      const plot = order[Number(index)];
      if (plot !== undefined) partAmountErrors[plot] = text;
    }
    for (const [index, text] of Object.entries(errors.parts.plot)) {
      const plot = order[Number(index)];
      if (plot !== undefined) partAmountErrors[plot] = text;
    }
    for (const [index, byLine] of Object.entries(errors.parts.lines)) {
      const plot = order[Number(index)];
      if (plot === undefined) continue;
      for (const [lineIndex, text] of Object.entries(byLine)) {
        const charge = typedParts.lineIds[plot]?.[Number(lineIndex)];
        if (charge !== undefined) (partLineErrors[plot] ??= {})[charge] = text;
      }
    }
  }

  function resetProposal() {
    setEdits(null);
    setSplitEdits(null);
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

  /** The first edit of a part seeds the explicit parts from what the server proposed, in its order. */
  function baseSplit(): SplitEdits {
    return (
      splitEdits ?? {
        order: (splitShown?.parts ?? []).map((p) => p.plotId),
        amounts: null,
        lines: {},
        touched: {},
      }
    );
  }

  function editPartAmount(plotId: string, text: string) {
    const base = baseSplit();
    const amounts =
      base.amounts ?? Object.fromEntries((splitShown?.parts ?? []).map((p) => [p.plotId, p.amount]));
    setSplitEdits({ ...base, amounts: { ...amounts, [plotId]: text } });
    setSaveError(undefined);
  }

  function editPartLine(plotId: string, chargeId: string, text: string) {
    const base = baseSplit();
    const proposal = splitShown?.parts.find((p) => p.plotId === plotId);
    const seeded =
      base.lines[plotId] ??
      Object.fromEntries(
        (proposal?.lines ?? [])
          .filter((line) => !isZeroMoney(line.proposed))
          .map((line) => [line.chargeId, line.proposed]),
      );
    setSplitEdits({
      ...base,
      lines: { ...base.lines, [plotId]: { ...seeded, [chargeId]: text } },
      touched: { ...base.touched, [plotId]: new Set(base.touched[plotId]).add(chargeId) },
    });
    setSaveError(undefined);
  }

  function changeMode(next: Mode) {
    if (next === mode) return;
    setMode(next);
    resetProposal();
  }

  const canSave =
    request !== undefined &&
    preview.current !== undefined &&
    preview.current.valid &&
    preview.current.digest !== undefined &&
    !preview.pending &&
    !enter.isPending;

  function save() {
    if (request === undefined || preview.current === undefined) return;
    const data: PaymentEntryRequest = {
      ...request,
      digest: preview.current.digest,
      ...(isSplit || payer === null ? {} : { payerMemberId: payer.id }),
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
          if (payment === undefined) return;
          if (data.split !== undefined) {
            const plotNumbers = Object.fromEntries(
              (preview.current?.parts ?? []).map((p) => [p.plotId, p.plotNumber]),
            );
            setSavedGroup({ payments: result.payments, plotNumbers });
          } else {
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
    setSavedGroup(undefined);
    setSplitPayer(null);
    setAmountText('');
    setPayer(null);
    setNote('');
    setReference('');
    setReceivedOn(kyivToday());
    resetProposal();
  }

  if (savedGroup !== undefined) {
    return (
      <PaymentGroupSaved
        payments={savedGroup.payments}
        plotNumbers={savedGroup.plotNumbers}
        onAnother={another}
      />
    );
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
        <RadioGroup
          legend={f.mode}
          value={mode}
          onChange={(value) => changeMode(value as Mode)}
          options={[
            { value: 'single', label: f.modeSingle },
            { value: 'split', label: f.modeSplit },
          ]}
        />
        {isSplit ? (
          <SplitPayerStep
            payer={splitPayer}
            error={errors.parts.payer}
            onChange={(member) => {
              setSplitPayer(member);
              resetProposal();
            }}
          />
        ) : (
          <>
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
          </>
        )}
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
        {!isSplit && (
          <PayerPicker
            plotId={plotId}
            value={payer}
            error={errors.fields.payerMemberId}
            onChange={setPayer}
          />
        )}
        <TextArea
          label={f.note}
          hint={f.noteHint}
          maxLength={MAX_NOTE}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          error={errors.fields.payerNote}
        />
      </Panel>
      <Panel title={isSplit ? t.payments.split.title : t.payments.proposal.title}>
        {outdated && <Notice tone="warning">{t.payments.proposal.outdated}</Notice>}
        {showNotice && <Notice tone="danger">{errors.notice}</Notice>}
        {!ready ? (
          <p className="text-ink-muted">
            {isSplit ? t.payments.split.needPayerAndAmount : f.needPlotAndAmount}
          </p>
        ) : isSplit ? (
          splitShown === undefined ? (
            preview.pending ? (
              <LoadingState message={f.previewing} />
            ) : null
          ) : (
            <div aria-busy={preview.pending}>
              {preview.pending && <PendingNote text={f.previewing} />}
              <SplitParts
                result={splitShown}
                splitError={problems.split}
                totalErrors={problems.payment}
                edits={splitEdits}
                amountErrors={partAmountErrors}
                lineErrors={partLineErrors}
                onAmount={editPartAmount}
                onLine={editPartLine}
              />
            </div>
          )
        ) : part === undefined ? (
          preview.pending ? (
            <LoadingState message={f.previewing} />
          ) : null
        ) : (
          <div aria-busy={preview.pending}>
            {preview.pending && <PendingNote text={f.previewing} />}
            <ProposalTable
              part={part}
              values={edits}
              touched={touched}
              lineErrors={lineErrors}
              amountError={amountErrors}
              totalError={problems.payment[plotId ?? '']}
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

/** The table stays; this only says that a newer answer is on its way. */
function PendingNote({ text }: { text: string }) {
  return (
    <p role="status" className="mb-2 text-sm text-ink-muted">
      {text}
    </p>
  );
}
