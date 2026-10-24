import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type PendingReading } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { plotMeter, queryResult, withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { ApprovalsPage } from './ApprovalsPage';

const approve = vi.fn();
const reject = vi.fn();
let items: PendingReading[];
let rejectOptions: { mutation?: { onError?: (e: unknown) => void } };

vi.mock('../me', () => ({ useCan: () => true, useOptionalMe: () => null }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListReadings: () => queryResult(items),
  useApproveReading: () => ({ mutate: approve, isPending: false }),
  useRejectReading: (o: typeof rejectOptions) => {
    rejectOptions = o;
    return { mutate: reject, isPending: false };
  },
}));

const pending = (overrides: Partial<PendingReading> = {}): PendingReading => ({
  reading: {
    id: 'r1',
    meterId: 'm1',
    takenOn: '2026-10-20',
    value: '8250.000',
    status: 'PENDING',
    source: 'MEMBER',
    changeable: false,
  },
  meter: plotMeter,
  interval: { from: '2026-10-05', to: '2026-10-20', days: 15, consumption: '45.000' },
  warnings: [],
  ...overrides,
});

function open() {
  renderWithApp(withQueries(<ApprovalsPage />));
}

describe('ApprovalsPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    approve.mockReset();
    reject.mockReset();
    items = [pending()];
  });

  it('shows the submitted reading and the interval it would close', () => {
    open();
    expect(screen.getByRole('heading', { name: 'Readings to approve' })).toBeInTheDocument();
    expect(screen.getByText('Reading 8,250 kWh on 20 Oct 2026')).toBeInTheDocument();
    expect(
      screen.getByText('Once approved: 45 kWh over 15 days (5 Oct 2026 – 20 Oct 2026)'),
    ).toBeInTheDocument();
  });

  it('shows the unusual-consumption warning and a note when there is no interval', () => {
    items = [pending({ interval: undefined, warnings: ['UNUSUAL_CONSUMPTION'] })];
    open();
    expect(screen.getByText(/consumption is much higher than usual/)).toBeInTheDocument();
    expect(screen.getByText(/does not fit between the counted readings/)).toBeInTheDocument();
  });

  it('says so when nothing waits', () => {
    items = [];
    open();
    expect(screen.getByText('No readings are waiting for approval')).toBeInTheDocument();
  });

  it('approves a reading', async () => {
    const user = userEvent.setup();
    open();
    await user.click(screen.getByRole('button', { name: 'Approve' }));
    expect(approve).toHaveBeenCalledWith({ id: 'r1' });
  });

  it('requires a reason to reject, then sends it trimmed', async () => {
    const user = userEvent.setup();
    open();
    await user.click(screen.getByRole('button', { name: 'Reject' }));
    await user.click(screen.getByRole('button', { name: 'Reject reading' }));
    expect(reject).not.toHaveBeenCalled();
    expect(screen.getByText('Enter a reason')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Reason'), '  Wrong meter ');
    await user.click(screen.getByRole('button', { name: 'Reject reading' }));
    expect(reject).toHaveBeenCalledWith({ id: 'r1', data: { reason: 'Wrong meter' } });
  });

  it('shows the server refusal as catalog text', async () => {
    const user = userEvent.setup();
    open();
    await user.click(screen.getByRole('button', { name: 'Reject' }));
    rejectOptions.mutation?.onError?.(new ApiError(409, { code: 'READING_STATUS_INVALID' }));
    expect(await screen.findByText(/status has changed/)).toBeInTheDocument();
  });
});
