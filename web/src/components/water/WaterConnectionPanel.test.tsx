import { ApiError, type Permission, type PlotWaterConnections, type WaterSource } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { WaterConnectionPanel } from './WaterConnectionPanel';

type Callbacks = { onSuccess?: () => void | Promise<void>; onError?: (error: unknown) => void };

const connect = vi.fn();
const end = vi.fn();
let permissions: Permission[];
let plotWater: ReturnType<typeof queryResult<PlotWaterConnections>> | { error: unknown; data?: undefined };

const coop = {
  id: 'c1',
  source: { id: 'cooperative', kind: 'COOPERATIVE_SUPPLY' as const },
  from: '2026-05-01',
};
const sources: WaterSource[] = [
  { id: 'cooperative', kind: 'COOPERATIVE_SUPPLY', plots: [] },
  { id: 'm9', kind: 'METER', name: 'Well 1', plots: [] },
];

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetPlotWaterConnections: () => plotWater,
  useListWaterSources: () => queryResult(sources),
  useConnectPlotWater: () => ({ mutate: connect, isPending: false }),
  useEndPlotWaterConnection: () => ({ mutate: end, isPending: false }),
}));
vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('../../kyivToday', () => ({ kyivToday: () => '2026-10-23' }));

function renderPanel(archived = false) {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <WaterConnectionPanel plotId="p7" archived={archived} />
    </QueryClientProvider>,
  );
}

const connected: PlotWaterConnections = {
  plotId: 'p7',
  connected: true,
  current: coop,
  connections: [
    coop,
    {
      id: 'c0',
      source: { id: 'm9', kind: 'METER', name: 'Well 1' },
      from: '2025-05-01',
      until: '2025-09-30',
    },
  ],
};

describe('WaterConnectionPanel', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    connect.mockReset();
    end.mockReset();
    permissions = ['PLOTS_VIEW', 'METERS_MANAGE'];
    plotWater = queryResult(connected);
  });

  it('shows the current source with its start and the history', () => {
    renderPanel();
    expect(
      screen.getByText(
        (_, el) =>
          el?.tagName === 'P' &&
          el.textContent === 'Connected to water: Cooperative water supply from 1 May 2026',
      ),
    ).toBeInTheDocument();
    const history = screen.getByRole('list');
    expect(within(history).getByText('from 1 May 2026')).toBeInTheDocument();
    expect(within(history).getByText('Well 1')).toHaveAttribute('data-user-content');
    expect(within(history).getByText('1 May 2025 – 30 Sep 2025')).toBeInTheDocument();
  });

  it('says so when the plot is not connected and offers Connect', () => {
    plotWater = queryResult({ plotId: 'p7', connected: false, connections: [] });
    renderPanel();
    expect(screen.getByText('Not connected to water')).toBeInTheDocument();
    expect(screen.getByText('The plot has never been connected to water')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Connect' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Disconnect' })).toBeNull();
  });

  it('connects to the cooperative supply with the chosen date', async () => {
    plotWater = queryResult({ plotId: 'p7', connected: false, connections: [] });
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Connect' }));
    fireEvent.change(screen.getByLabelText('Connected from'), { target: { value: '2026-10-01' } });
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(connect).toHaveBeenCalledWith({ id: 'p7', data: { from: '2026-10-01' } }, expect.any(Object));
  });

  it('connects to a meter source by its id', async () => {
    plotWater = queryResult({ plotId: 'p7', connected: false, connections: [] });
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Connect' }));
    await user.selectOptions(screen.getByLabelText('Water source'), 'Well 1');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(connect).toHaveBeenCalledWith(
      { id: 'p7', data: { from: '2026-10-23', sourceMeterId: 'm9' } },
      expect.any(Object),
    );
  });

  it('disconnects the current connection after the chosen day', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Disconnect' }));
    fireEvent.change(screen.getByLabelText('Last connected day'), { target: { value: '2026-10-31' } });
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(end).toHaveBeenCalledWith(
      { id: 'p7', connectionId: 'c1', data: { until: '2026-10-31' } },
      expect.any(Object),
    );
  });

  it('shows the text of a conflict from the server', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Disconnect' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const callbacks = end.mock.calls[0]?.[1] as Callbacks;
    fireEvent.change(screen.getByLabelText('Last connected day'), { target: { value: '2026-10-31' } });
    callbacks.onError?.(new ApiError(409, { code: 'WATER_NOT_CONNECTED' }));
    expect(await screen.findByText('The plot is not connected to water on this date')).toBeInTheDocument();
  });

  it('shows the already-connected text and the field error of the date', async () => {
    plotWater = queryResult({ plotId: 'p7', connected: false, connections: [] });
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Connect' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const callbacks = connect.mock.calls[0]?.[1] as Callbacks;
    callbacks.onError?.(new ApiError(409, { code: 'WATER_ALREADY_CONNECTED' }));
    expect(await screen.findByText('The plot is already connected to water')).toBeInTheDocument();
    callbacks.onError?.(
      new ApiError(400, { code: 'VALIDATION_FAILED', errors: [{ field: 'from', code: 'REQUIRED' }] }),
    );
    expect(await screen.findByText('This field is required.')).toBeInTheDocument();
  });

  it('offers Connect, not Disconnect, when the only connection has an end day in the future', () => {
    const ending = { ...coop, until: '2026-12-31' };
    plotWater = queryResult({ plotId: 'p7', connected: true, current: ending, connections: [ending] });
    renderPanel();
    expect(screen.getByRole('button', { name: 'Connect' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Disconnect' })).toBeNull();
  });

  it('offers Disconnect, not Connect, for a lasting connection that starts in the future', async () => {
    const future = { ...coop, from: '2026-11-01' };
    plotWater = queryResult({ plotId: 'p7', connected: false, connections: [future] });
    const user = userEvent.setup();
    renderPanel();
    expect(screen.queryByRole('button', { name: 'Connect' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Disconnect' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(end).toHaveBeenCalledWith(
      { id: 'p7', connectionId: 'c1', data: { until: '2026-10-23' } },
      expect.any(Object),
    );
  });

  it('has no buttons without METERS_MANAGE', () => {
    permissions = ['PLOTS_VIEW'];
    renderPanel();
    expect(screen.getByText(/Connected to water/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('has no buttons for an archived plot', () => {
    renderPanel(true);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('renders nothing when the server refuses it', () => {
    plotWater = { error: new ApiError(403, {}) };
    const { container } = renderPanel();
    expect(container).toBeEmptyDOMElement();
  });

  // @spec: 012-retroactive-recalculation (acceptance 2.6: the hint on the water connection of a plot)
  // @regression
  it('reminds a holder of RECALCULATION_RUN that past charges are unchanged', () => {
    permissions = ['PLOTS_VIEW', 'METERS_MANAGE', 'RECALCULATION_RUN'];
    renderPanel();
    expect(screen.getByText(/Past charges have not changed/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Recalculation' })).toHaveAttribute(
      'href',
      '/charges/recalculations/new',
    );
  });

  // @spec: 012-retroactive-recalculation
  // @regression
  it('shows no recalculation hint without RECALCULATION_RUN or for an archived plot', () => {
    renderPanel();
    expect(screen.queryByText(/Past charges have not changed/)).not.toBeInTheDocument();
    permissions = ['PLOTS_VIEW', 'METERS_MANAGE', 'RECALCULATION_RUN'];
    renderPanel(true);
    expect(screen.queryAllByText(/Past charges have not changed/)).toHaveLength(0);
  });
});
