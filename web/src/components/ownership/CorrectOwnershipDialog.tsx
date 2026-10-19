import { useCreateOwnershipCorrection, type Ownership, type PlotOwnerships } from '@nadel/api-client';
import { addFractions, format, isOne, parseFraction, type Fraction } from '@nadel/shared';
import { useState, type FormEvent } from 'react';
import { useOwnershipInvalidation } from '../../hooks/useOwnershipInvalidation';
import { useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { problemCode, problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Modal } from '../Modal';
import { Button, Notice, TextArea, TextField } from '../ui';
import { MemberPicker, type PickedMember } from './MemberPicker';
import { ShareTotal } from './ShareTotal';

/** Two inclusive periods overlap; a missing end means "still current". */
function overlaps(a: Ownership, b: Ownership): boolean {
  return (
    (b.validTo === undefined || a.validFrom <= b.validTo) &&
    (a.validTo === undefined || b.validFrom <= a.validTo)
  );
}

/**
 * Correction of one owner record (member, share, dates, basis) with a required reason, together with the shares of
 * the co-owners whose period overlaps it, so that the shares can be kept at a total of 1 in one step.
 */
export function CorrectOwnershipDialog({
  plotId,
  ownership,
  ownerships,
  onClose,
  onSaved,
}: {
  plotId: string;
  ownership: Ownership;
  ownerships: PlotOwnerships;
  onClose: () => void;
  onSaved: () => void;
}) {
  const t = useT();
  const today = kyivToday();
  const invalidate = useOwnershipInvalidation(plotId);
  const coOwners = [...ownerships.current, ...ownerships.history].filter(
    (o) => o.id !== ownership.id && overlaps(ownership, o),
  );
  const [member, setMember] = useState<PickedMember | null>({
    id: ownership.memberId,
    name: ownership.memberName,
  });
  const [shareText, setShareText] = useState(ownership.share);
  const [validFrom, setValidFrom] = useState(ownership.validFrom);
  const [validTo, setValidTo] = useState(ownership.validTo ?? '');
  const [basis, setBasis] = useState(ownership.basis ?? '');
  const [reason, setReason] = useState('');
  const [reasonTouched, setReasonTouched] = useState(false);
  const [coTexts, setCoTexts] = useState<string[]>(coOwners.map((o) => o.share));
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string | undefined>();
  const mutation = useCreateOwnershipCorrection();

  const share = parseFraction(shareText);
  const coShares = coTexts.map((text) => parseFraction(text));
  const validShares = [share, ...coShares].filter((s): s is Fraction => s !== null);
  const totalOk = validShares.length === coTexts.length + 1 && isOne(addFractions(validShares));
  const reasonError = reason.trim() === '' && reasonTouched ? t.validation.REQUIRED : serverErrors.reason;
  const fromError =
    validFrom === '' ? t.ownership.dateRequired : validFrom > today ? t.ownership.dateFuture : undefined;
  const toError = validTo > today ? t.ownership.dateFuture : undefined;
  const canSave =
    member !== null &&
    share !== null &&
    totalOk &&
    reason.trim() !== '' &&
    !fromError &&
    !toError &&
    !mutation.isPending;

  function edit(update: () => void) {
    setServerErrors({});
    setNotice(undefined);
    update();
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!canSave || !member) return;
    mutation.mutate(
      {
        id: ownership.id,
        data: {
          memberId: member.id,
          share: shareText.trim(),
          validFrom,
          ...(validTo ? { validTo } : {}),
          ...(basis.trim() ? { basis: basis.trim() } : {}),
          reason: reason.trim(),
          coOwnerShares: coOwners.map((o, i) => ({ ownershipId: o.id, share: (coTexts[i] ?? '').trim() })),
        },
      },
      {
        onSuccess: async () => {
          await invalidate();
          onSaved();
        },
        onError: (error) => {
          const fields = problemFieldErrors(t, error);
          setServerErrors(fields);
          setNotice(
            Object.keys(fields).length > 0
              ? undefined
              : problemCode(error) === 'PLOT_ARCHIVED'
                ? t.ownership.plotArchived
                : problemMessage(t, error),
          );
        },
      },
    );
  }

  return (
    <Modal title={t.ownership.correctTitle} onClose={onClose} size="lg">
      <form onSubmit={submit} noValidate className="mt-3 flex flex-col gap-4">
        {notice && <Notice tone="danger">{notice}</Notice>}
        <MemberPicker
          label={t.ownership.member}
          value={member}
          excludeIds={[]}
          error={serverErrors.memberId}
          onChange={(m) => edit(() => setMember(m))}
        />
        <TextField
          label={t.ownership.share}
          value={shareText}
          error={shareText.trim() !== '' && share === null ? t.validation.SHARE_INVALID : serverErrors.share}
          onChange={(e) => edit(() => setShareText(e.target.value))}
        />
        <TextField
          label={t.ownership.validFrom}
          type="date"
          value={validFrom}
          max={today}
          error={fromError ?? serverErrors.validFrom}
          onChange={(e) => edit(() => setValidFrom(e.target.value))}
        />
        <TextField
          label={t.ownership.validTo}
          type="date"
          value={validTo}
          max={today}
          error={toError ?? serverErrors.validTo}
          onChange={(e) => edit(() => setValidTo(e.target.value))}
        />
        <TextArea
          label={t.ownership.basisField}
          maxLength={500}
          rows={2}
          value={basis}
          onChange={(e) => edit(() => setBasis(e.target.value))}
        />
        {coOwners.length > 0 && (
          <fieldset className="flex flex-col gap-2 rounded-control border border-line p-3">
            <legend className="px-1 text-sm font-medium">{t.ownership.coOwnersTitle}</legend>
            {coOwners.map((o, i) => (
              <TextField
                key={o.id}
                label={format(t.ownership.coOwnerShare, { name: o.memberName })}
                value={coTexts[i] ?? ''}
                error={
                  (coTexts[i] ?? '').trim() !== '' && coShares[i] === null
                    ? t.validation.SHARE_INVALID
                    : (serverErrors[`coOwnerShares[${i}].share`] ??
                      serverErrors[`coOwnerShares[${i}].ownershipId`])
                }
                onChange={(e) =>
                  edit(() => setCoTexts((ts) => ts.map((x, j) => (j === i ? e.target.value : x))))
                }
              />
            ))}
          </fieldset>
        )}
        <ShareTotal shares={validShares} />
        <TextArea
          label={t.ownership.reason}
          maxLength={500}
          rows={2}
          value={reason}
          error={reasonError}
          onBlur={() => setReasonTouched(true)}
          onChange={(e) =>
            edit(() => {
              setReason(e.target.value);
              setReasonTouched(true);
            })
          }
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {t.ownership.cancel}
          </Button>
          <Button type="submit" disabled={!canSave}>
            {mutation.isPending ? t.ownership.saving : t.ownership.save}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
