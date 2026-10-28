import { ApiError, type Permission, type WaterSource } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { WaterSourcesSection } from './WaterSourcesSection';

type Callbacks = { onSuccess?: () => void | Promise<void>; onError?: (error: unknown) => void };

const connect = vi.fn();
const disconnect = vi.fn();
let permissions: Permission[];

const sources: WaterSource[] = [
  {
    id: 'cooperative',
    kind: 'COOPERATIVE_SUPPLY',
    plots: [
      { plotId: 'p1', plotNumber: '1', connectionId: 'c1', from: '2026-05-01' },
      { plotId: 'p2', plotNumber: '2', connectionId: 'c2', from: '2026-05-01', until: '2026-09-30' },
      { plotId: 'p3', plotNumber: '3', connectionId: 'c3', from: '2026-06-01' },
    ],
  },
  { id: 'm9', kind: 'METER', name: 'Well 1', plots: [] },
];

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListWaterSources: () => queryResult(sources),
  useConnectWaterSourcePlots: () => ({ mutate: connect, isPending: false }),
  useDisconnectWaterSourcePlots: () => ({ mutate: disconnect, isPending: false }),
  useListPlots: () => ({
    data: {
      items: [
        { id: 'p5', number: '5', kind: 'PLOT', archived: false },
        { id: 'p6', number: '6', kind: 'PLOT', archived: false },
      ],
    },
  }),
}));
vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('../../kyivToday', () => ({ kyivToday: () => '2026-10-23' }));

function renderSection() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <WaterSourcesSection />
    </QueryClientProvider>,
  );
}

describe('WaterSourcesSection', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    connect.mockReset();
    disconnect.mockReset();
    permissions = ['PLOTS_VIEW', 'METERS_MANAGE'];
  });

  it('lists the sources with their plots', () => {
    renderSection();
    expect(screen.getByRole('heading', { name: 'Cooperative water supply' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Well 1' })).toBeInTheDocument();
    expect(screen.getByText('No plots connected')).toBeInTheDocument();
    expect(screen.getByText(': 1 May 2026 – 30 Sep 2026')).toBeInTheDocument();
  });

  it('connects several plots at once and shows the errors at the plots they name', async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getAllByRole('button', { name: 'Connect plots' })[1]!);
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByRole('combobox'), '{ArrowDown}');
    await user.click(within(dialog).getByRole('option', { name: '5' }));
    await user.type(within(dialog).getByRole('combobox'), '{ArrowDown}');
    await user.click(within(dialog).getByRole('option', { name: '6' }));
    fireEvent.change(within(dialog).getByLabelText('Connected from'), { target: { value: '2026-10-01' } });
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(connect).toHaveBeenCalledWith(
      { sourceId: 'm9', data: { plotIds: ['p5', 'p6'], from: '2026-10-01' } },
      expect.any(Object),
    );
    const callbacks = connect.mock.calls[0]?.[1] as Callbacks;
    callbacks.onError?.(
      new ApiError(400, {
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'plotIds[1]', code: 'PLOT_ARCHIVED' }],
      }),
    );
    const six = (await within(dialog).findByText('The plot is archived')).closest('li')!;
    expect(within(six).getByText('6')).toBeInTheDocument();
    expect(within(dialog).getAllByText('The plot is archived')).toHaveLength(1);
  });

  it('shows a conflict of the whole change', async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getAllByRole('button', { name: 'Connect plots' })[0]!);
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByRole('combobox'), '{ArrowDown}');
    await user.click(within(dialog).getByRole('option', { name: '5' }));
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    (connect.mock.calls[0]?.[1] as Callbacks).onError?.(
      new ApiError(409, { code: 'WATER_ALREADY_CONNECTED' }),
    );
    expect(await within(dialog).findByText('The plot is already connected to water')).toBeInTheDocument();
  });

  it('disconnects the chosen connected plots after the chosen day', async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getAllByRole('button', { name: 'Disconnect plots' })[0]!);
    const dialog = screen.getByRole('dialog');
    // plot 2 is already disconnected and not offered
    expect(within(dialog).queryByRole('checkbox', { name: '2' })).toBeNull();
    await user.click(within(dialog).getByRole('checkbox', { name: '1' }));
    await user.click(within(dialog).getByRole('checkbox', { name: '3' }));
    fireEvent.change(within(dialog).getByLabelText('Last connected day'), {
      target: { value: '2026-10-31' },
    });
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(disconnect).toHaveBeenCalledWith(
      { sourceId: 'cooperative', data: { plotIds: ['p1', 'p3'], until: '2026-10-31' } },
      expect.any(Object),
    );
    (disconnect.mock.calls[0]?.[1] as Callbacks).onError?.(
      new ApiError(400, {
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'plotIds[1]', code: 'UNKNOWN_PLOT' }],
      }),
    );
    const three = (await within(dialog).findByText('There is no such plot')).closest('li')!;
    expect(within(three).getByText('3')).toBeInTheDocument();
  });

  it('has no change buttons without METERS_MANAGE', () => {
    permissions = ['PLOTS_VIEW'];
    renderSection();
    expect(screen.getByRole('heading', { name: 'Well 1' })).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });
});
