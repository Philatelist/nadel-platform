import { screen } from '@testing-library/react';
import type { Permission, PlotDetails } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { PlotPage } from './PlotPage';

let permissions: Permission[];
let plot: PlotDetails;

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetPlot: () => ({ data: plot, error: null, isPending: false, isError: false, refetch: vi.fn() }),
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
