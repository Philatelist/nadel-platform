import { screen, within } from '@testing-library/react';
import type { Meter, MeterReading, MeterReadingHistory, Permission } from '@nadel/api-client';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { plotMeter, queryResult, wellMeter } from '../test/meterFixtures';
import { MeterPage } from './MeterPage';

let permissions: Permission[];
let meter: Meter;
let history: MeterReadingHistory;
const deleteReading = vi.fn();

const reading = (overrides: Partial<MeterReading>): MeterReading => ({
  id: 'r1',
  meterId: 'm1',
  takenOn: '2026-10-05',
  value: '8205.000',
  status: 'APPROVED',
  source: 'ENTERED',
  changeable: true,
  ...overrides,
});

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMeter: () => queryResult(meter),
  useDeleteMeter: () => ({ mutate: vi.fn(), isPending: false }),
  useUpdateMeter: () => ({ mutate: vi.fn(), isPending: false }),
  useRemoveMeter: () => ({ mutate: vi.fn(), isPending: false }),
  useListMeterReadings: () => queryResult(history),
  useDeleteReading: () => ({ mutate: deleteReading, isPending: false }),
  useCorrectReading: () => ({ mutate: vi.fn(), isPending: false }),
}));

function renderCard() {
  return renderWithApp(
    withQueries(
      <Routes>
        <Route path="/meters/:id" element={<MeterPage />} />
      </Routes>,
    ),
    { route: '/meters/m1' },
  );
}

describe('MeterPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['METERS_VIEW', 'METERS_MANAGE'];
    meter = plotMeter;
    deleteReading.mockReset();
    history = {
      meterId: 'm1',
      intervals: [],
      readings: [
        reading({
          id: 'r2',
          takenOn: '2026-10-05',
          value: '8250.000',
          interval: { from: '2026-09-03', to: '2026-10-05', days: 32, consumption: '110.000' },
        }),
        reading({
          id: 'r1',
          takenOn: '2026-10-05',
          value: '8205.000',
          status: 'SUPERSEDED',
          changeable: false,
        }),
        reading({
          id: 'r3',
          takenOn: '2026-10-28',
          value: '8210.000',
          status: 'REJECTED',
          source: 'MEMBER',
          rejectReason: 'Wrong meter',
          changeable: false,
        }),
      ],
    };
  });

  it('shows the details with units, the note and the reading history', () => {
    renderCard();
    expect(screen.getByText('Initial reading')).toBeInTheDocument();
    expect(screen.getAllByText('8,205 kWh').length).toBeGreaterThan(0);
    expect(screen.getByText('Behind the gate')).toHaveAttribute('data-user-content');
    expect(screen.getByRole('link', { name: '7' })).toHaveAttribute('href', '/plots/p7');
  });

  it('offers edit, remove and delete to a manager of a deletable active meter', () => {
    renderCard();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });

  it('hides delete when the meter is not deletable and remove when it is already removed', () => {
    meter = { ...plotMeter, deletable: false };
    const { unmount } = renderCard();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument();
    unmount();
    meter = wellMeter;
    renderCard();
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
    expect(screen.getByText('Removed on')).toBeInTheDocument();
  });

  it('shows no actions without METERS_MANAGE', () => {
    permissions = ['METERS_VIEW'];
    renderCard();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });

  it('shows the history with intervals, status badges and the reject reason', () => {
    renderCard();
    const rows = screen.getAllByRole('row');
    expect(within(rows[1]!).getByText('counted')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('110 kWh in 32 d (3 Sep 2026 – 5 Oct 2026)')).toBeInTheDocument();
    expect(within(rows[2]!).getByText('superseded')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('rejected')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('Wrong meter')).toHaveAttribute('data-user-content');
  });

  it('offers correct and delete only on changeable readings and deletes after confirming', async () => {
    const user = userEvent.setup();
    renderCard();
    const rows = screen.getAllByRole('row');
    expect(within(rows[1]!).getByRole('button', { name: 'Correct' })).toBeInTheDocument();
    expect(within(rows[2]!).queryByRole('button', { name: 'Correct' })).toBeNull();
    expect(within(rows[3]!).queryByRole('button', { name: 'Delete reading' })).toBeNull();
    await user.click(within(rows[1]!).getByRole('button', { name: 'Delete reading' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete reading' }));
    expect(deleteReading).toHaveBeenCalledWith({ id: 'r2' });
  });
});
