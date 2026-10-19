import { useCreateOwnershipChange } from '@nadel/api-client';
import { parseFraction, isOne, addFractions, type Fraction } from '@nadel/shared';
import { useState, type FormEvent } from 'react';
import { useOwnershipInvalidation } from '../../hooks/useOwnershipInvalidation';
import { useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { problemCode, problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { format } from '@nadel/shared';
import { Modal } from '../Modal';
import { Button, Notice, TextArea, TextField } from '../ui';
import { MemberPicker, type PickedMember } from './MemberPicker';
import { ShareTotal } from './ShareTotal';

interface Row {
  key: number;
  member: PickedMember | null;
  shareText: string;
}

const MAX_ROWS = 20;

/** Change of the owners of a plot from a date: members, shares with a live total, and the basis. */
export function ChangeOwnerDialog({
  plotId,
  onClose,
  onSaved,
}: {
  plotId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const t = useT();
  const today = kyivToday();
  const invalidate = useOwnershipInvalidation(plotId);
  const [date, setDate] = useState(today);
  const [nextKey, setNextKey] = useState(1);
  const [rows, setRows] = useState<Row[]>([{ key: 0, member: null, shareText: '1/1' }]);
  const [basis, setBasis] = useState('');
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string | undefined>();
  const mutation = useCreateOwnershipChange();

  const shares = rows.map((r) => parseFraction(r.shareText));
  const validShares = shares.filter((s): s is Fraction => s !== null);
  const memberIds = rows.map((r) => r.member?.id);
  const isDuplicate = (i: number) => {
    const id = memberIds[i];
    return id !== undefined && memberIds.indexOf(id) !== i;
  };
  const dateError =
    date === '' ? t.ownership.dateRequired : date > today ? t.ownership.dateFuture : undefined;
  const totalOk =
    rows.length === 0 || (validShares.length === rows.length && isOne(addFractions(validShares)));
  const canSave =
    !dateError &&
    totalOk &&
    rows.every((r, i) => r.member !== null && shares[i] !== null && !isDuplicate(i)) &&
    !mutation.isPending;

  function edit(update: () => void) {
    setServerErrors({});
    setNotice(undefined);
    update();
  }
  function patchRow(key: number, patch: Partial<Row>) {
    edit(() => setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...patch } : r))));
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!canSave) return;
    mutation.mutate(
      {
        id: plotId,
        data: {
          effectiveFrom: date,
          owners: rows.map((r) => ({ memberId: r.member?.id ?? '', share: r.shareText.trim() })),
          ...(basis.trim() ? { basis: basis.trim() } : {}),
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
    <Modal title={t.ownership.changeTitle} onClose={onClose} size="lg">
      <form onSubmit={submit} noValidate className="mt-3 flex flex-col gap-4">
        {notice && <Notice tone="danger">{notice}</Notice>}
        <TextField
          label={t.ownership.effectiveFrom}
          type="date"
          value={date}
          max={today}
          error={dateError ?? serverErrors.effectiveFrom}
          onChange={(e) => edit(() => setDate(e.target.value))}
        />
        {rows.map((row, i) => {
          const n = i + 1;
          const shareInvalid = row.shareText.trim() !== '' && shares[i] === null;
          return (
            <fieldset key={row.key} className="flex flex-col gap-2 rounded-control border border-line p-3">
              <legend className="px-1 text-sm font-medium">{format(t.ownership.ownerRow, { n })}</legend>
              <MemberPicker
                label={`${t.ownership.member} ${n}`}
                value={row.member}
                excludeIds={memberIds.filter((id, j): id is string => j !== i && id !== undefined)}
                error={
                  serverErrors[`owners[${i}].memberId`] ??
                  (isDuplicate(i) ? t.validation.DUPLICATE_OWNER : undefined)
                }
                onChange={(member) => patchRow(row.key, { member })}
              />
              <TextField
                label={`${t.ownership.share} ${n}`}
                value={row.shareText}
                error={shareInvalid ? t.validation.SHARE_INVALID : serverErrors[`owners[${i}].share`]}
                onChange={(e) => patchRow(row.key, { shareText: e.target.value })}
              />
              <div>
                <Button
                  variant="secondary"
                  onClick={() => edit(() => setRows((rs) => rs.filter((r) => r.key !== row.key)))}
                >
                  {format(t.ownership.removeOwner, { n })}
                </Button>
              </div>
            </fieldset>
          );
        })}
        {rows.length < MAX_ROWS && (
          <div>
            <Button
              variant="secondary"
              onClick={() => {
                edit(() => setRows((rs) => [...rs, { key: nextKey, member: null, shareText: '' }]));
                setNextKey(nextKey + 1);
              }}
            >
              {t.ownership.addOwner}
            </Button>
          </div>
        )}
        {rows.length > 0 ? (
          <ShareTotal shares={validShares} />
        ) : (
          <p className="text-sm text-ink-muted">{t.ownership.noOwnersHint}</p>
        )}
        {serverErrors.owners && (
          <p role="alert" className="text-sm text-danger">
            {serverErrors.owners}
          </p>
        )}
        <TextArea
          label={t.ownership.basisField}
          maxLength={500}
          rows={2}
          value={basis}
          onChange={(e) => edit(() => setBasis(e.target.value))}
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
