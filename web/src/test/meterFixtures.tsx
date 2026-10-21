import type { Meter } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';

/** Gives the component under test a QueryClient (the dialogs invalidate queries after a save). */
export function withQueries(ui: ReactElement): ReactElement {
  return <QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>;
}

export const plotMeter: Meter = {
  id: 'm1',
  kind: 'PLOT',
  resource: 'ELECTRICITY',
  serial: 'EM-104523',
  plot: { id: 'p7', number: '7', street: 'Lipova' },
  installedOn: '2026-06-01',
  initialReading: '8205.000',
  note: 'Behind the gate',
  active: true,
  deletable: true,
  lastReading: { takenOn: '2026-06-01', value: '8205.000' },
};

export const wellMeter: Meter = {
  id: 'm2',
  kind: 'COMMON_CONSUMER',
  resource: 'ELECTRICITY',
  serial: 'W-1',
  name: 'Well 1',
  installedOn: '2025-01-10',
  initialReading: '0.000',
  removedOn: '2026-03-01',
  finalReading: '1234.500',
  active: false,
  deletable: false,
  lastReading: { takenOn: '2026-03-01', value: '1234.500' },
};

export function queryResult<T>(data: T) {
  return { data, error: null, isPending: false, isError: false, refetch: () => undefined };
}
