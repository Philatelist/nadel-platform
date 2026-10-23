import type { ChargeType } from '@nadel/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { preview } from '../components/recalculation/recalcFixtures';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { withQueries } from '../test/meterFixtures';
import { RecalculationNewPage } from './RecalculationNewPage';

const mutateAsync = vi.fn();
const types: ChargeType[] = [
  { id: 'ct1', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false, active: true, position: 1 },
  { id: 'ct2', name: 'Archived', nameCustomized: true, active: false, position: 2 },
];

vi.mock('../kyivToday', () => ({ kyivToday: () => '2026-10-23' }));
vi.mock('../me', () => ({
  useCan: () => true,
  useMe: () => ({ permissions: ['CHARGES_VIEW', 'RECALCULATION_RUN'] }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListChargeTypes: () => queryResult(types),
  useListPlots: () => queryResult({ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 }),
  useRunRecalculation: () => ({ mutateAsync, isPending: false }),
}));

function renderPage() {
  return renderWithApp(
    withQueries(
      <Routes>
        <Route path="/charges/recalculations/new" element={<RecalculationNewPage />} />
        <Route path="/charges/recalculations/:id" element={<p>detail page</p>} />
      </Routes>,
    ),
    { route: '/charges/recalculations/new' },
  );
}

describe('RecalculationNewPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    mutateAsync.mockReset();
  });

  it('previews the chosen months, then books with the digest and opens the recalculation', async () => {
    mutateAsync.mockImplementation(({ params }: { params: { preview: boolean } }) =>
      Promise.resolve(params.preview ? preview() : preview({ id: 'r9', preview: false, booked: true })),
    );
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    expect(await screen.findByText('Difference by charge type')).toBeInTheDocument();
    expect(mutateAsync).toHaveBeenCalledWith({
      data: { cause: { type: 'MANUAL', manual: { from: '2026-10', to: '2026-10' } } },
      params: { preview: true },
    });
    await userEvent.type(screen.getByRole('textbox'), 'Rate fixed');
    await userEvent.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(await screen.findByText('detail page')).toBeInTheDocument();
    expect(mutateAsync).toHaveBeenLastCalledWith({
      data: {
        cause: { type: 'MANUAL', manual: { from: '2026-10', to: '2026-10' } },
        reason: 'Rate fixed',
        digest: 'digest-1',
      },
      params: { preview: false },
    });
  });

  it('sends only the chosen charge types, active ones offered', async () => {
    mutateAsync.mockResolvedValue(preview());
    renderPage();
    await userEvent.click(screen.getByRole('radio', { name: 'Chosen types' }));
    expect(screen.queryByText('Archived')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    expect(screen.getByText('Choose at least one type')).toBeInTheDocument();
    expect(mutateAsync).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith({
        data: {
          cause: { type: 'MANUAL', manual: { from: '2026-10', to: '2026-10', chargeTypeIds: ['ct1'] } },
        },
        params: { preview: true },
      }),
    );
  });

  it('rejects a first month after the last and a future month without calling the server', async () => {
    renderPage();
    const from = screen.getByLabelText('From month');
    const to = screen.getByLabelText('To month');
    await userEvent.clear(from);
    await userEvent.type(from, '2026-10');
    await userEvent.clear(to);
    await userEvent.type(to, '2026-12');
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    expect(screen.getByText('A future month cannot be recalculated')).toBeInTheDocument();
    await userEvent.clear(from);
    await userEvent.type(from, '2026-10');
    await userEvent.clear(to);
    await userEvent.type(to, '2026-09');
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    expect(screen.getByText('The first month cannot be later than the last')).toBeInTheDocument();
    expect(mutateAsync).not.toHaveBeenCalled();
  });

  it('requires plots when "chosen plots" is selected', async () => {
    renderPage();
    await userEvent.click(screen.getByRole('radio', { name: 'Chosen plots' }));
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    expect(screen.getByText('Add at least one plot')).toBeInTheDocument();
    expect(mutateAsync).not.toHaveBeenCalled();
  });
});
