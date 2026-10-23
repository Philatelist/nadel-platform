import type { Recalculation } from '@nadel/api-client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useT } from '../../i18n';
import { problemCode, problemFieldErrors, problemMessage } from '../../problem';
import { Button, LoadingState, Notice } from '../ui';
import { RecalculationPreview } from './RecalculationPreview';

/** Previews (no digest, no reason) or books (the digest of the preview and the reason) one recalculation. */
export type RecalculationRunner = (
  mode: 'preview' | 'book',
  digest?: string,
  reason?: string,
) => Promise<Recalculation>;

interface Props {
  run: RecalculationRunner;
  onBooked: (booked: Recalculation) => void | Promise<void>;
  onBack: () => void;
}

/**
 * Step two of a recalculation: shows the preview the server computed and books exactly that preview (its digest)
 * with the reason. When the ledger changed in between (`PREVIEW_OUTDATED`) the preview is made again at once.
 */
export function RecalculationStep({ run, onBooked, onBack }: Props) {
  const t = useT();
  const [preview, setPreview] = useState<Recalculation>();
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>();
  const [outdated, setOutdated] = useState(false);
  const [booking, setBooking] = useState(false);
  const runRef = useRef(run);
  useEffect(() => {
    runRef.current = run;
  });

  const refresh = useCallback(async (): Promise<void> => {
    try {
      setPreview(await runRef.current('preview'));
      setError(undefined);
    } catch (e: unknown) {
      setError(e);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  async function book(current: Recalculation) {
    setBooking(true);
    setError(undefined);
    setOutdated(false);
    try {
      const booked = await runRef.current('book', current.digest, reason.trim());
      await onBooked(booked);
    } catch (e: unknown) {
      if (problemCode(e) === 'PREVIEW_OUTDATED') {
        setOutdated(true);
        setPreview(undefined);
        await refresh();
      } else {
        setError(e);
      }
    } finally {
      setBooking(false);
    }
  }

  const fieldErrors = problemFieldErrors(t, error);
  const hasFieldErrors = Object.keys(fieldErrors).length > 0;
  return (
    <div className="flex flex-col gap-4">
      {outdated && (
        <Notice tone="warning">{`${t.problem.PREVIEW_OUTDATED} ${t.recalculation.step.outdated}`}</Notice>
      )}
      {error !== undefined && !hasFieldErrors && <Notice tone="danger">{problemMessage(t, error)}</Notice>}
      {preview ? (
        <RecalculationPreview
          preview={preview}
          reason={reason}
          onReasonChange={setReason}
          reasonError={fieldErrors.reason}
          booking={booking}
          onBook={() => void book(preview)}
          onBack={onBack}
        />
      ) : error !== undefined ? (
        <div>
          <Button variant="secondary" onClick={onBack}>
            {t.recalculation.step.back}
          </Button>
        </div>
      ) : (
        <LoadingState message={t.recalculation.step.loading} />
      )}
    </div>
  );
}
