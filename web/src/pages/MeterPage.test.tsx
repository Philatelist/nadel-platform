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
  useReplaceMeter: () => ({ mutate: vi.fn(), isPending: false }),
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

  it('offers replacement to a manager of an active meter and opens the dialog with the wrap-around hint', async () => {
    const user = userEvent.setup();
    renderCard();
    await user.click(screen.getByRole('button', { name: 'Replace meter' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(/Did the counter wrap around/)).toBeInTheDocument();
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
    expect(screen.queryByRole('button', { name: 'Replace meter' })).toBeNull();
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
  describe('replacement chain', () => {
    const replacedBy = { id: 'm5', serial: 'EM-NEW' };
    const replaces = { id: 'm0', serial: 'EM-OLD', removedOn: '2026-05-01', finalReading: '4100.500' };

    it("names the successor with this meter's removal day and final reading", () => {
      meter = { ...plotMeter, active: false, removedOn: '2026-06-01', finalReading: '8205.000', replacedBy };
      renderCard();
      expect(screen.getByTestId('replaced-by')).toHaveTextContent(
        'Replaced by EM-NEW: removed 1 Jun 2026, final reading 8,205 kWh',
      );
      expect(within(screen.getByTestId('replaced-by')).getByRole('link', { name: 'EM-NEW' })).toHaveAttribute(
        'href',
        '/meters/m5',
      );
      expect(screen.queryByTestId('replaces')).toBeNull();
    });

    it('names the predecessor with its removal day and final reading', () => {
      meter = { ...plotMeter, replaces, replacesMeterId: 'm0' };
      renderCard();
      expect(screen.getByTestId('replaces')).toHaveTextContent(
        'Replaced meter EM-OLD: removed 1 May 2026, final reading 4,100.5 kWh',
      );
      expect(within(screen.getByTestId('replaces')).getByRole('link', { name: 'EM-OLD' })).toHaveAttribute(
        'href',
        '/meters/m0',
      );
      expect(screen.queryByTestId('replaced-by')).toBeNull();
    });

    it('shows both lines in the middle of a chain', () => {
      meter = {
        ...plotMeter,
        active: false,
        removedOn: '2026-06-01',
        finalReading: '8205.000',
        replaces,
        replacedBy,
      };
      renderCard();
      expect(screen.getByTestId('replaced-by')).toBeInTheDocument();
      expect(screen.getByTestId('replaces')).toBeInTheDocument();
    });
  });
});
