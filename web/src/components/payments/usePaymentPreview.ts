import { enterPayment, type PaymentEntryRequest, type PaymentEntryResult } from '@nadel/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';

export const PREVIEW_DEBOUNCE_MS = 400;

export interface PaymentPreview {
  /** The latest successful preview, kept while a newer one is on its way or has failed; only for display. */
  shown: PaymentEntryResult | undefined;
  /** The preview of exactly the current request, the only one a save may use; undefined until it has arrived. */
  current: PaymentEntryResult | undefined;
  /** The debounce is running or a preview request is in flight. */
  pending: boolean;
  error: unknown;
  /** Asks the server again (after a stale digest). */
  refresh: () => Promise<void>;
}

/** A kept answer is shown only while it is about the plot (or, for a split, the payer) of the request. */
function ofOwner(
  owner: string | undefined,
  result: PaymentEntryResult | undefined,
): PaymentEntryResult | undefined {
  if (owner === undefined || result === undefined) return undefined;
  const resultOwner = result.split !== undefined ? result.split.payerMemberId : result.parts[0]?.plotId;
  return resultOwner === owner ? result : undefined;
}

/**
 * The server's proposal for a request (`POST /payments?preview=true`): debounced, keyed by the request, the previous
 * answer is kept while a new one is awaited, and a failed request is not retried (its problem is shown inline).
 * While the request is incomplete (a line is being retyped) the last answer stays on screen, `current` is empty.
 * Nothing is computed here; amounts only travel as the strings the server sent.
 */
export function usePaymentPreview(
  request: PaymentEntryRequest | undefined,
  owner: string | undefined,
): PaymentPreview {
  const key = request === undefined ? '' : JSON.stringify(request);
  const settledKey = useDebouncedValue(key, PREVIEW_DEBOUNCE_MS);
  const settled = settledKey === '' ? undefined : (JSON.parse(settledKey) as PaymentEntryRequest);

  const query = useQuery({
    queryKey: ['payment-preview', settledKey],
    queryFn: () => enterPayment(settled as PaymentEntryRequest, { preview: true }),
    enabled: settled !== undefined,
    placeholderData: keepPreviousData,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });

  const [shown, setShown] = useState<PaymentEntryResult>();
  if (query.data !== undefined && !query.isPlaceholderData && query.data !== shown) setShown(query.data);

  const waiting = key !== settledKey || query.isFetching;
  const fresh = settled !== undefined && !waiting && !query.isError && !query.isPlaceholderData;
  return {
    shown: ofOwner(owner, shown ?? query.data),
    current: fresh ? query.data : undefined,
    pending: request !== undefined && waiting,
    error: request !== undefined && !waiting ? query.error : undefined,
    refresh: async () => {
      await query.refetch();
    },
  };
}
