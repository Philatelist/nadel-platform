import { ApiError, type MonthRunResult } from '@nadel/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { RunPreviewDialog } from './RunPreviewDialog';

const mutate = vi.fn();
let mutation: {
  onError: (e: unknown) => void;
  onSuccess: (r: MonthRunResult, v: { params: { preview: boolean } }) => Promise<void>;
};

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useRunBillingMonth: (o: { mutation: typeof mutation }) => {
    mutation = o.mutation;
    return { mutate, isPending: false };
  },
}));

const preview: MonthRunResult = {
  month: '2026-09',
  preview: true,
  plots: 41,
  totals: [
    {
      chargeTypeId: 't2',
      chargeTypeCode: 'MEMBERSHIP',
      name: 'Membership',
      nameCustomized: false,
      amount: '1250.50',
    },
  ],
  total: '1250.50',
  notCharged: [
    {
      kind: 'TARIFF_MONTH',
      reason: 'CONFLICTING_TARIFFS',
      plotId: 'p7',
      plotNumber: '7',
      chargeType: { id: 't2', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false },
      month: '2026-09',
      tariffs: [{ id: 'tf1', name: 'Old fee' }],
    },
  ],
  created: 0,
  digest: 'abc',
};

describe('RunPreviewDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    mutate.mockReset();
  });

  async function shown() {
    renderWithApp(withQueries(<RunPreviewDialog month="2026-09" onClose={vi.fn()} />));
    expect(mutate).toHaveBeenCalledWith({ month: '2026-09', params: { preview: true } });
    await act(() => mutation.onSuccess(preview, { params: { preview: true } }));
  }

  it('shows plots, totals and the not-charged items', async () => {
    await shown();
    expect(screen.getByText('Plots that get new charges: 41')).toBeInTheDocument();
    expect(screen.getAllByText(/Membership/).length).toBeGreaterThan(1);
    expect(screen.getByRole('link', { name: 'Old fee' })).toHaveAttribute('href', '/tariffs/tf1');
  });

  it('books with the digest and shows the created count', async () => {
    await shown();
    await userEvent.click(screen.getByRole('button', { name: 'Book' }));
    expect(mutate).toHaveBeenLastCalledWith({
      month: '2026-09',
      params: { preview: false },
      data: { digest: 'abc' },
    });
    await act(() =>
      mutation.onSuccess({ ...preview, preview: false, created: 41 }, { params: { preview: false } }),
    );
    expect(screen.getByText('Charges created: 41')).toBeInTheDocument();
  });

  it('offers a new preview when the digest is outdated', async () => {
    await shown();
    act(() => mutation.onError(new ApiError(409, { code: 'PREVIEW_OUTDATED' })));
    expect(await screen.findByRole('alert')).toHaveTextContent('The charges changed since the preview');
    await userEvent.click(screen.getByRole('button', { name: 'Preview again' }));
    expect(mutate).toHaveBeenLastCalledWith({ month: '2026-09', params: { preview: true } });
  });
});
