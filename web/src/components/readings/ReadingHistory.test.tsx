import type { Meter, MeterReading } from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { plotMeter, queryResult, withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { ReadingHistory } from './ReadingHistory';

const invalidateAfterReading = vi.fn();
const mutate = vi.fn();
let deleteOptions: { mutation: { onSuccess: () => Promise<void> } };
let shown: MeterReading[] = [];

const reading: MeterReading = {
  id: 'r1',
  meterId: 'm1',
  takenOn: '2026-10-05',
  value: '8205.000',
  status: 'APPROVED',
  source: 'ENTERED',
  changeable: true,
};

vi.mock('../charges/chargeInvalidation', () => ({
  invalidateAfterReading: (...args: unknown[]) => invalidateAfterReading(...args),
}));
vi.mock('../../me', () => ({ useCan: () => true, useOptionalMe: () => null }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMeterReadings: () => queryResult({ meterId: 'm1', intervals: [], readings: shown }),
  useDeleteReading: (o: typeof deleteOptions) => {
    deleteOptions = o;
    return { mutate, isPending: false };
  },
}));

describe('ReadingHistory', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    invalidateAfterReading.mockReset();
    mutate.mockReset();
    shown = [reading];
  });

  it('refreshes the plot and charge queries after a reading is deleted', async () => {
    const user = userEvent.setup();
    renderWithApp(withQueries(<ReadingHistory meter={plotMeter as Meter} />));
    await user.click(screen.getByRole('button', { name: 'Delete reading' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete reading' }));
    expect(mutate).toHaveBeenCalledWith({ id: 'r1' });
    await deleteOptions.mutation.onSuccess();
    expect(invalidateAfterReading).toHaveBeenCalledTimes(1);
  });

  it('offers Correct and Delete on a counted reading a charge uses, and not on a pending one', () => {
    shown = [
      { ...reading, id: 'used', changeable: false },
      { ...reading, id: 'pending', status: 'PENDING', changeable: false },
    ];
    renderWithApp(withQueries(<ReadingHistory meter={plotMeter as Meter} />));
    expect(screen.getAllByRole('button', { name: 'Correct' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'Delete reading' })).toHaveLength(1);
  });
});
