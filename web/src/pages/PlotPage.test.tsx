import { act, screen } from '@testing-library/react';
import { ApiError, type Permission, type PlotDetails } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { PlotPage } from './PlotPage';

let permissions: Permission[];
let plot: PlotDetails;
type Opts = { mutation: { onError: (e: unknown) => void } };
let archiveOptions: Opts;
let deleteOptions: Opts;

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
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
