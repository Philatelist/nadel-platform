import {
  getGetMeterQueryKey,
  getListMeterReadingsQueryKey,
  getListMetersQueryKey,
  type RecalculationCause,
} from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useT } from '../../i18n';
import { problemMessage } from '../../problem';
import { Modal } from '../Modal';
import { RecalculationStep } from '../recalculation/RecalculationStep';
import { useCauseDrivenChange } from '../recalculation/useCauseDrivenChange';
import { Button, Notice } from '../ui';

/**
 * Step 2 for a reading that charges already use: previews the cause on mount (`READING_ENTER`, `READING_CORRECT`
 * or `READING_DELETE`), asks for the reason and books the change with the recalculation. A change that alters no
 * amount is saved at once. Mount it only while it should be open; `onDone` gets whether a recalculation was booked.
 */
export function ReadingRecalculationDialog({
  meterId,
  cause,
  onDone,
  onClose,
}: {
  meterId: string;
  cause: RecalculationCause;
  onDone: (recalculated: boolean) => void | Promise<void>;
  onClose: () => void;
}) {
  const t = useT();
  const queryClient = useQueryClient();
  const [error, setError] = useState<unknown>();
  const change = useCauseDrivenChange(async (result) => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getListMeterReadingsQueryKey(meterId) }),
      queryClient.invalidateQueries({ queryKey: getGetMeterQueryKey(meterId) }),
      queryClient.invalidateQueries({ queryKey: getListMetersQueryKey() }),
    ]);
    await onDone(result.booked);
  });
  const started = useRef(false);
  const { start } = change;
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    start(cause).catch(setError);
  }, [start, cause]);

  return (
    <Modal title={t.readings.recalc.title} onClose={onClose}>
      <div className="mt-3 flex flex-col gap-3">
        {change.state.kind === 'preview' ? (
          <RecalculationStep run={change.run} onBack={onClose} onBooked={change.finish} />
        ) : error !== undefined ? (
          <>
            <Notice tone="danger">{problemMessage(t, error)}</Notice>
            <div>
              <Button variant="secondary" onClick={onClose}>
                {t.recalculation.step.back}
              </Button>
            </div>
          </>
        ) : (
          <Notice tone="warning">{t.recalculation.step.loading}</Notice>
        )}
      </div>
    </Modal>
  );
}
