import { act, screen } from '@testing-library/react';
import { ApiError, type Permission, type PlotDetails } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { PlotPage } from './PlotPage';

let permissions: Permission[];
let plot: PlotDetails;
let ownedPlots: { plotId: string; number: string; share: string }[] = [];
type Opts = { mutation: { onError: (e: unknown) => void } };
let archiveOptions: Opts;
let deleteOptions: Opts;

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
  useMe: () => ({ ownedPlots }),
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useArchivePlot: (options: Opts) => {
    archiveOptions = options;
    return { mutate: vi.fn(), isPending: false };
  },
  useDeletePlot: (options: Opts) => {
    deleteOptions = options;
    return { mutate: vi.fn(), isPending: false };
  },
  useGetPlotTariffs: () => ({
    data: { month: '2026-10', total: '0.00', items: [] },
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useGetPlot: () => ({ data: plot, error: null, isPending: false, isError: false, refetch: vi.fn() }),
  useListPlotOwnerships: () => ({
    data: { plotId: 'p1', current: [], history: [] },
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

const active: PlotDetails = {
  id: 'p1',
  number: '12',
  kind: 'PLOT',
  status: 'GREY',
  archived: false,
  hasBuilding: false,
};

function renderCard() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <Routes>
        <Route path="/plots/:id" element={<PlotPage />} />
      </Routes>
    </QueryClientProvider>,
    { route: '/plots/p1' },
  );
}

describe('PlotPage report link', () => {
  const name = 'Plot balances report for this street';
  beforeEach(() => {
    permissions = ['PLOTS_VIEW', 'REPORTS_VIEW'];
    plot = { ...active, street: 'Лісова & Co 5' };
  });

  it('opens plot balances filtered to the encoded street', () => {
    renderCard();
    const href = screen.getByRole('link', { name }).getAttribute('href') ?? '';
    const url = new URL(href, 'http://x');
    expect(url.pathname).toBe('/reports/plot-balances');
    expect(url.searchParams.get('street')).toBe('Лісова & Co 5');
    expect(href).not.toContain('&Co');
    expect(href).not.toContain(' ');
  });

  it('omits the street parameter for a plot without a street', () => {
    plot = active;
    renderCard();
    expect(screen.getByRole('link', { name })).toHaveAttribute('href', '/reports/plot-balances');
  });

  it('has no link without REPORTS_VIEW', () => {
    permissions = ['PLOTS_VIEW'];
    renderCard();
    expect(screen.queryByRole('link', { name })).toBeNull();
  });
});

describe('PlotPage actions', () => {
  beforeEach(() => {
    permissions = ['PLOTS_VIEW', 'PLOTS_EDIT'];
    plot = active;
  });

  it('offers Archive and Delete for an active plot', () => {
    renderCard();
    expect(screen.getByRole('button', { name: 'Archive' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Restore' })).toBeNull();
  });

  it('offers Restore but not Delete for an archived plot', () => {
    plot = { ...active, archived: true };
    renderCard();
    expect(screen.getByRole('button', { name: 'Restore' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Archive' })).toBeNull();
  });

  it('hides the actions with PLOTS_EDIT alone', () => {
    permissions = ['PLOTS_EDIT'];
    renderCard();
    expect(screen.queryByRole('button', { name: 'Archive' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });

  it('hides the actions without PLOTS_EDIT', () => {
    permissions = ['PLOTS_VIEW'];
    renderCard();
    expect(screen.queryByRole('button', { name: 'Archive' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });
});

describe('PlotPage owners', () => {
  beforeEach(() => {
    plot = active;
  });

  it('shows the owners panel to viewers of plots', () => {
    permissions = ['PLOTS_VIEW'];
    renderCard();
    expect(screen.getByRole('heading', { name: 'Owners' })).toBeInTheDocument();
    expect(screen.getByText('No ownership history')).toBeInTheDocument();
  });

  it('does not show the owners panel to a member without PLOTS_VIEW', () => {
    permissions = [];
    renderCard();
    expect(screen.queryByRole('heading', { name: 'Owners' })).toBeNull();
  });

  it('says common land has no owners instead of listing them', () => {
    permissions = ['PLOTS_VIEW'];
    plot = { ...active, kind: 'COMMON' };
    renderCard();
    expect(screen.getByText('Common land has no owners.')).toBeInTheDocument();
    expect(screen.queryByText('No ownership history')).toBeNull();
  });
});

describe('PlotPage for a member', () => {
  beforeEach(() => {
    permissions = [];
  });

  it('shows the details sent for an owned plot and the owners without actions', () => {
    ownedPlots = [{ plotId: 'p1', number: '12', share: '1' }];
    plot = { ...active, status: 'GREEN', areaM2: 600, cadastralNumber: '4810136300:01:001:0001' };
    renderCard();
    expect(screen.getByText('4810136300:01:001:0001')).toBeInTheDocument();
    expect(screen.getByText('Area')).toBeInTheDocument();
    expect(screen.queryByText('Notes')).toBeNull();
    expect(screen.getByRole('heading', { name: 'Owners' })).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
    ownedPlots = [];
  });

  it('shows another plot limited, with no status mark when the status is absent', () => {
    ownedPlots = [{ plotId: 'other', number: '7', share: '1' }];
    plot = { ...active, status: undefined };
    renderCard();
    expect(screen.queryByText('Area')).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Owners' })).toBeNull();
    expect(screen.queryByText('Status')).toBeNull();
    ownedPlots = [];
  });
});

describe('PlotPage refused actions', () => {
  beforeEach(() => {
    permissions = ['PLOTS_VIEW', 'PLOTS_EDIT'];
    plot = active;
  });

  it('explains that a plot with an owner cannot be archived', () => {
    renderCard();
    act(() => archiveOptions.mutation.onError(new ApiError(409, { code: 'PLOT_HAS_OWNERS' })));
    expect(screen.getByText('This plot has an owner — change the owner first')).toBeInTheDocument();
  });

  it('explains that a plot with ownership history cannot be deleted', () => {
    renderCard();
    act(() => deleteOptions.mutation.onError(new ApiError(409, { code: 'PLOT_HAS_OWNERSHIP_HISTORY' })));
    expect(screen.getByText('A plot with ownership history cannot be deleted')).toBeInTheDocument();
  });
});

describe('PlotPage tariffs panel', () => {
  beforeEach(() => {
    permissions = ['PLOTS_VIEW'];
    plot = active;
  });

  it('shows the tariffs panel on a plot card', () => {
    renderCard();
    expect(screen.getByRole('heading', { name: 'Tariffs' })).toBeInTheDocument();
  });

  it('does not show it for common land', () => {
    plot = { ...active, kind: 'COMMON' };
    renderCard();
    expect(screen.queryByRole('heading', { name: 'Tariffs' })).toBeNull();
  });
});
