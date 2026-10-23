import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { getListImportsQueryKey, type ImportSummary } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ImportHistory } from './ImportHistory';

const listImports = vi.fn();
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  listImports: (...args: unknown[]) => listImports(...args),
}));

const zero = { created: 0, updated: 0, unchanged: 0 };
function row(over: Partial<ImportSummary> = {}): ImportSummary {
  return {
    id: 'r1',
    fileName: 'plots.xlsx',
    uploadedBy: 'Olena Koval',
    uploadedAt: '2026-10-20T09:30:00Z',
    status: 'COMMITTED',
    counts: {
      plots: { created: 5, updated: 2, unchanged: 1 },
      members: zero,
      ownerships: zero,
      meters: { created: 3, updated: 0, unchanged: 1 },
      waterConnections: { created: 6, updated: 0, unchanged: 0 },
      openingBalances: { created: 2, updated: 0, unchanged: 0 },
    },
    problemCount: 0,
    ...over,
  };
}
const pageOf = (items: ImportSummary[], page = 0, totalPages = 1) => ({
  items,
  page,
  size: 20,
  totalItems: items.length,
  totalPages,
});

let client: QueryClient;
function renderIt() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  renderWithApp(
    <QueryClientProvider client={client}>
      <ImportHistory />
    </QueryClientProvider>,
  );
}

describe('ImportHistory', () => {
  beforeEach(() => listImports.mockReset());

  it('lists a row with date in Kyiv time, file, author, status and counts', async () => {
    listImports.mockResolvedValue(pageOf([row()]));
    renderIt();
    const cells = within(await screen.findByRole('row', { name: /plots\.xlsx/ }));
    expect(cells.getByText('Olena Koval')).toBeInTheDocument();
    expect(cells.getByText('Imported')).toBeInTheDocument();
    expect(cells.getByText('Plots: 5 new, 2 updated')).toBeInTheDocument();
    expect(cells.getByText('Water connections: 6 new, 0 updated')).toBeInTheDocument();
    expect(cells.getByText('Opening balances: 2 new, 0 updated')).toBeInTheDocument();
    expect(cells.getByText(/12:30/)).toBeInTheDocument();
  });

  it.each([
    ['PREVIEW', 'Checked, not imported'],
    ['HAS_ERRORS', 'Has errors'],
    ['COMMITTED', 'Imported'],
    ['STALE', 'Refused: data changed'],
    ['EXPIRED', 'Never imported, expired'],
  ] as const)('labels status %s', async (status, label) => {
    listImports.mockResolvedValue(pageOf([row({ status, problemCount: 3 })]));
    renderIt();
    expect(await screen.findByText(label)).toBeInTheDocument();
  });

  it('shows the empty state', async () => {
    listImports.mockResolvedValue(pageOf([], 0, 0));
    renderIt();
    expect(await screen.findByText('No files have been uploaded yet.')).toBeInTheDocument();
  });

  it('pages through the list', async () => {
    listImports
      .mockResolvedValueOnce(pageOf([row({ id: 'r0', fileName: 'f0.xlsx' })], 0, 2))
      .mockResolvedValueOnce(pageOf([row({ id: 'r1', fileName: 'f1.xlsx' })], 1, 2));
    renderIt();
    expect(await screen.findByText('f0.xlsx')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('f1.xlsx')).toBeInTheDocument();
    expect(listImports).toHaveBeenLastCalledWith({ page: 1, size: 20 });
  });

  it('refetches when the list key is invalidated (after upload or commit)', async () => {
    listImports.mockResolvedValue(pageOf([row()]));
    renderIt();
    await screen.findByText('plots.xlsx');
    listImports.mockResolvedValue(pageOf([row({ id: 'r2', fileName: 'new.xlsx' })]));
    await client.invalidateQueries({ queryKey: getListImportsQueryKey() });
    expect(await screen.findByText('new.xlsx')).toBeInTheDocument();
  });
});
