import { screen } from '@testing-library/react';
import type { PlotPage, Permission } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { PlotsPage } from './PlotsPage';

let permissions: Permission[];
let data: PlotPage;

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListPlots: () => ({
    data,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

const full = {
  id: 'p1',
  number: '12',
  street: 'Lipova',
  kind: 'PLOT' as const,
  status: 'GREY' as const,
  archived: false,
  areaM2: 650.5,
  cadastralNumber: '1234567890:12:123:0001',
  hasBuilding: true,
};

describe('PlotsPage', () => {
  beforeEach(() => {
    data = { items: [full], page: 0, size: 20, totalItems: 1, totalPages: 1 };
  });

  it('shows area, cadastral number and the archived toggle to viewers of plots', () => {
    permissions = ['PLOTS_VIEW'];
    renderWithApp(<PlotsPage />);
    expect(screen.getByRole('columnheader', { name: 'Area, sotkas' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Cadastral number' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Archived' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Show archived' })).toBeInTheDocument();
    expect(screen.getByText('6.505')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '12' })).toHaveAttribute('href', '/plots/p1');
  });

  it('shows Add plot only with both PLOTS_VIEW and PLOTS_EDIT', () => {
    permissions = ['PLOTS_EDIT'];
    const { unmount } = renderWithApp(<PlotsPage />);
    expect(screen.queryByRole('button', { name: 'Add plot' })).toBeNull();
    unmount();
    permissions = ['PLOTS_VIEW', 'PLOTS_EDIT'];
    renderWithApp(<PlotsPage />);
    expect(screen.getByRole('button', { name: 'Add plot' })).toBeInTheDocument();
  });

  it('shows only number, street, kind and status without PLOTS_VIEW', () => {
    permissions = [];
    data = { ...data, items: [{ ...full, areaM2: undefined, cadastralNumber: undefined }] };
    renderWithApp(<PlotsPage />);
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual(['Number', 'Street', 'Kind', 'Status']);
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.getByText('No owner')).toBeInTheDocument();
  });

  it('distinguishes an empty registry from a filtered list with no match', () => {
    permissions = [];
    data = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };
    renderWithApp(<PlotsPage />);
    expect(screen.getByText('No plots yet')).toBeInTheDocument();
  });

  it('says nothing was found when filters are set', () => {
    permissions = [];
    data = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };
    renderWithApp(<PlotsPage />, { route: '/plots?kind=COMMON' });
    expect(screen.getByText('Nothing found')).toBeInTheDocument();
  });
});
