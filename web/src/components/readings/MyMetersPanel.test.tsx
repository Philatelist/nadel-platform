import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { MeterReading } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { plotMeter, queryResult, withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { MyMetersPanel } from './MyMetersPanel';

const submit = vi.fn();
let ownedPlots: { plotId: string; number: string; share: string }[];

const reading = (overrides: Partial<MeterReading>): MeterReading => ({
  id: 'r1',
  meterId: 'm1',
  takenOn: '2026-10-05',
  value: '8205.000',
  status: 'APPROVED',
  source: 'ENTERED',
  changeable: false,
  ...overrides,
});

vi.mock('../../me', () => ({
  useCan: () => false,
  useOptionalMe: () => null,
  useMe: () => ({ ownedPlots }),
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListPlotMeters: () => queryResult([plotMeter]),
  useListMeterReadings: () =>
    queryResult({
      meterId: 'm1',
      intervals: [],
      readings: [
        reading({ id: 'r3', takenOn: '2026-10-20', value: '8260.000', status: 'PENDING', source: 'MEMBER' }),
        reading({
          id: 'r2',
          takenOn: '2026-10-10',
          value: '8230.000',
          status: 'REJECTED',
          source: 'MEMBER',
          rejectReason: 'Photo is unclear',
        }),
        reading({
          id: 'r1',
          interval: { from: '2026-09-03', to: '2026-10-05', days: 32, consumption: '110.000' },
        }),
      ],
    }),
  useSubmitPlotReading: () => ({ mutate: submit, isPending: false }),
}));

describe('MyMetersPanel', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    submit.mockReset();
    ownedPlots = [{ plotId: 'p7', number: '7', share: '1/1' }];
  });

  it('lists the own meters with statuses, the reject reason and the interval', () => {
    renderWithApp(withQueries(<MyMetersPanel />));
    expect(screen.getByRole('heading', { name: 'My meters' })).toBeInTheDocument();
    expect(screen.getByText('pending')).toBeInTheDocument();
    expect(screen.getByText('rejected')).toBeInTheDocument();
    expect(screen.getByText('Photo is unclear')).toHaveAttribute('data-user-content');
    expect(screen.getByText('110 kWh in 32 d (3 Sep 2026 – 5 Oct 2026)')).toBeInTheDocument();
  });

  it('renders nothing for a member without plots', () => {
    ownedPlots = [];
    const { container } = renderWithApp(withQueries(<MyMetersPanel />));
    expect(container).toBeEmptyDOMElement();
  });

  it('submits a reading with the date left at its default', async () => {
    const user = userEvent.setup();
    renderWithApp(withQueries(<MyMetersPanel />));
    await user.click(screen.getByRole('button', { name: 'Submit a reading' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/Reading date is today/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/^Reading, kWh/), '8300.5');
    await user.click(within(dialog).getByRole('button', { name: 'Submit a reading' }));
    expect(submit).toHaveBeenCalledWith({
      id: 'p7',
      data: { meterId: 'm1', takenOn: undefined, value: '8300.5' },
    });
  });
});
