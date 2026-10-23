import { useRunRecalculation, type Recalculation, type RecalculationCause } from '@nadel/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useRef, useState } from 'react';
import { invalidateAfterRecalculation } from '../charges/chargeInvalidation';
import type { RecalculationRunner } from './RecalculationStep';

/** The screen of a change that can need a recalculation: the edit form, step 2 with the cause, or a request in flight. */
export type CauseDrivenState =
  | { readonly kind: 'edit' }
  | { readonly kind: 'preview'; readonly cause: RecalculationCause }
  | { readonly kind: 'saving' };

/**
 * Drives a change of the source data (a tariff version, later a reading) through `POST /recalculations`.
 * `start(cause)` previews first: a plan without lines saves the change at once (a booking without a reason, the server
 * answers `booked: false`), otherwise the state becomes `preview` and `run` feeds `RecalculationStep`. The preview
 * already made is handed to the step once, so the server is asked a single time. `start` rejects with the problem of
 * the failed request and returns the state to `edit`.
 */
export function useCauseDrivenChange(onFinished: (result: Recalculation) => void | Promise<void>) {
  const queryClient = useQueryClient();
  const mutation = useRunRecalculation();
  const [state, setState] = useState<CauseDrivenState>({ kind: 'edit' });
  const cause = useRef<RecalculationCause>(undefined);
  const first = useRef<Recalculation>(undefined);

  const finish = useCallback(
    async (result: Recalculation) => {
      await invalidateAfterRecalculation(queryClient);
      setState({ kind: 'edit' });
      await onFinished(result);
    },
    [queryClient, onFinished],
  );

  const start = useCallback(
    async (next: RecalculationCause): Promise<void> => {
      setState({ kind: 'saving' });
      try {
        const plan = await mutation.mutateAsync({ data: { cause: next }, params: { preview: true } });
        if (plan.lines.length === 0) {
          const saved = await mutation.mutateAsync({
            data: { cause: next, digest: plan.digest },
            params: { preview: false },
          });
          await finish(saved);
          return;
        }
        cause.current = next;
        first.current = plan;
        setState({ kind: 'preview', cause: next });
      } catch (error: unknown) {
        setState({ kind: 'edit' });
        throw error;
      }
    },
    [mutation, finish],
  );

  const run: RecalculationRunner = useCallback(
    (mode, digest, reason) => {
      if (mode === 'preview' && first.current) {
        const plan = first.current;
        first.current = undefined;
        return Promise.resolve(plan);
      }
      return mutation.mutateAsync({
        data: {
          cause: cause.current as RecalculationCause,
          ...(reason !== undefined ? { reason } : {}),
          ...(digest ? { digest } : {}),
        },
        params: { preview: mode === 'preview' },
      });
    },
    [mutation],
  );

  const back = useCallback(() => {
    first.current = undefined;
    setState({ kind: 'edit' });
  }, []);

  return { state, start, run, back, finish };
}
