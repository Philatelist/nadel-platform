import type { AllocationLinesRequest, PaymentPartProposal } from '@nadel/api-client';
import { isZeroMoney } from '@nadel/shared';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useT } from '../../i18n';
import { problemCode } from '../../problem';
import { Button, LoadingState, Notice } from '../ui';
import { ProposalTable } from './ProposalTable';
import { linesOf, type Edits } from './paymentLines';
import { paymentErrors } from './paymentProblems';
import { PREVIEW_DEBOUNCE_MS } from './usePaymentPreview';

/** What a reallocation or a prepayment allocation preview answers with: the proposal and its digest. */
export interface AllocationPreview {
  proposal: PaymentPartProposal;
  digest: string;
}

/**
 * The shared body of the reallocate and allocate-prepayment dialogs: asks the server for a proposal (explicit lines
 * once the treasurer edits one), shows it in the editable `ProposalTable`, and saves exactly the previewed request
 * with its digest. A stale digest re-previews at once. The client never computes an amount.
 */
export function AllocationEditor<TResult extends AllocationPreview>({
  cacheKey,
  preview,
  save,
  onSaved,
  onClose,
  submitLabel,
  savingLabel,
  cancelLabel,
  intro,
}: {
  /** Distinguishes the cached previews of different dialogs. */
  cacheKey: string;
  preview: (body: AllocationLinesRequest) => Promise<TResult>;
  save: (body: AllocationLinesRequest) => Promise<unknown>;
  onSaved: () => void | Promise<void>;
  onClose: () => void;
  submitLabel: string;
  savingLabel: string;
  cancelLabel: string;
  /** Rendered above the proposal, from the latest preview. */
  intro?: (result: TResult) => ReactNode;
}) {
  const t = useT();
  const [edits, setEdits] = useState<Edits>(null);
  const [touched, setTouched] = useState<ReadonlySet<string>>(new Set());
  const [outdated, setOutdated] = useState(false);
  const [saveError, setSaveError] = useState<unknown>();

  const typed = linesOf(edits);
  const request: AllocationLinesRequest | undefined =
    typed.invalid.size > 0 ? undefined : typed.lines === undefined ? {} : { lines: typed.lines };
  const key = request === undefined ? '' : JSON.stringify(request);
  const settledKey = useDebouncedValue(key, PREVIEW_DEBOUNCE_MS);

  const query = useQuery({
    queryKey: ['allocation-preview', cacheKey, settledKey],
    queryFn: () => preview(JSON.parse(settledKey) as AllocationLinesRequest),
    enabled: settledKey !== '',
    placeholderData: keepPreviousData,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
  const saveMutation = useMutation({ mutationFn: save });

  const waiting = key !== settledKey || query.isFetching;
  const current =
    request !== undefined && !waiting && !query.isError && !query.isPlaceholderData ? query.data : undefined;
  const shown = query.data;
  const errors = paymentErrors(t, saveError ?? (waiting ? undefined : query.error));

  const lineErrors: Record<string, string> = {};
  for (const [index, text] of Object.entries(errors.lines)) {
    const id = typed.ids[Number(index)];
    if (id !== undefined) lineErrors[id] = text;
  }
  const amountErrors: Record<string, string> = {};
  for (const id of typed.invalid) amountErrors[id] = t.payments.proposal.amountInvalid;

  function seed(): Record<string, string> {
    return Object.fromEntries(
      (shown?.proposal.lines ?? [])
        .filter((line) => !isZeroMoney(line.proposed))
        .map((line) => [line.chargeId, line.proposed]),
    );
  }

  function edit(chargeId: string, text: string) {
    setEdits({ ...(edits ?? seed()), [chargeId]: text });
    setTouched(new Set(touched).add(chargeId));
    setSaveError(undefined);
    setOutdated(false);
  }

  function submit() {
    if (current === undefined || request === undefined) return;
    setOutdated(false);
    setSaveError(undefined);
    saveMutation.mutate(
      { ...request, digest: current.digest },
      {
        onSuccess: () => onSaved(),
        onError: (error: unknown) => {
          if (problemCode(error) === 'PREVIEW_OUTDATED') {
            setOutdated(true);
            void query.refetch();
          } else {
            setSaveError(error);
          }
        },
      },
    );
  }

  const canSave = current !== undefined && !saveMutation.isPending;
  return (
    <div className="mt-3 flex flex-col gap-4">
      {outdated && <Notice tone="warning">{t.payments.proposal.outdated}</Notice>}
      {errors.notice !== undefined && !outdated && <Notice tone="danger">{errors.notice}</Notice>}
      {shown === undefined ? (
        errors.notice === undefined && <LoadingState message={t.payments.form.previewing} />
      ) : (
        <>
          {intro?.(shown)}
          <div aria-busy={waiting}>
            <ProposalTable
              part={shown.proposal}
              values={edits}
              touched={touched}
              lineErrors={lineErrors}
              amountError={amountErrors}
              onEdit={edit}
              onAdd={(chargeId) => edit(chargeId, '0.00')}
            />
          </div>
        </>
      )}
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={onClose}>
          {cancelLabel}
        </Button>
        <Button disabled={!canSave} onClick={submit}>
          {saveMutation.isPending ? savingLabel : submitLabel}
        </Button>
      </div>
    </div>
  );
}
