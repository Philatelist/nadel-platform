import {
  ApiError,
  type CommonConsumerRunDetails,
  type CommonConsumerRunResult,
  type CommonConsumerRunSummary,
  type Permission,
} from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  chargedBefore,
  chargedWell,
  commonPreview,
  commonRun,
  unknownLight,
} from '../components/charges/commonFixtures';
import { queryResult, withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { CommonConsumersPage } from './CommonConsumersPage';

type Callbacks = {
  onSuccess?: (r: CommonConsumerRunResult) => void | Promise<void>;
  onError?: (e: unknown) => void;
};

let permissions: Permission[];
let runs: CommonConsumerRunSummary[];
const mutate = vi.fn();

const details: CommonConsumerRunDetails = { ...commonRun, consumers: [chargedWell] };

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));
vi.mock('../kyivToday', () => ({ kyivToday: () => '2026-10-23' }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useRunCommonConsumers: () => ({ mutate, isPending: false }),
  useListCommonConsumerRuns: () => queryResult(runs),
  useGetCommonConsumerRun: () => queryResult(details),
  useReverseCommonConsumerRun: () => ({ mutate: vi.fn(), isPending: false }),
}));

function callbacks(): Callbacks {
  return mutate.mock.calls.at(-1)?.[1] as Callbacks;
}

async function showPreview(result: CommonConsumerRunResult = commonPreview) {
  renderWithApp(withQueries(<CommonConsumersPage />));
  await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
  await act(async () => {
    await callbacks().onSuccess?.(result);
  });
}

describe('CommonConsumersPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['CHARGES_VIEW', 'CHARGES_RUN', 'CHARGES_REVERSE'];
    runs = [];
    mutate.mockReset();
  });

  it('shows per consumer the consumption, split, plot-days, plots and amounts', async () => {
    await showPreview();
    const table = screen.getByRole('table', { name: /Common consumers/ });
    const row = within(table).getByRole('link', { name: 'Well pump' }).closest('tr') as HTMLElement;
    const cells = within(row);
    expect(cells.getByText('1,000')).toBeInTheDocument();
    expect(cells.getByText('Among connected plots')).toBeInTheDocument();
    expect(cells.getByText('46')).toBeInTheDocument();
    expect(cells.getByText('calculated')).toBeInTheDocument();
    expect(cells.getByText('Charged')).toBeInTheDocument();
    expect(cells.getByRole('link', { name: '1' })).toHaveAttribute('href', '/plots/p1');
    expect(screen.getByText('Plots charged: 2')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Warnings' })).toHaveTextContent(
      'Street lighting is skipped: its meter has no reading after the end of the range (last reading 30 Sep 2026).',
    );
    expect(screen.getByRole('button', { name: 'Book' })).toBeInTheDocument();
  });

  it('says there is no tariff and does not offer to book', async () => {
    await showPreview({
      ...commonPreview,
      noTariff: true,
      consumers: [{ ...chargedWell, plots: chargedWell.plots.map((p) => ({ ...p, amount: undefined })) }],
    });
    expect(screen.getAllByText('No tariff').length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'Open the tariffs' })).toHaveAttribute('href', '/tariffs');
    expect(screen.getByText(/the run cannot be booked/)).toBeInTheDocument();
    expect(screen.getAllByText(/not priced/)).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Book' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Preview without the charged consumers' })).toBeNull();
  });

  it('books exactly the preview by its digest', async () => {
    await showPreview();
    await userEvent.click(screen.getByRole('button', { name: 'Book' }));
    expect(mutate).toHaveBeenLastCalledWith(
      { data: { from: '2026-10-01', to: '2026-10-23', digest: 'd1' }, params: { preview: false } },
      expect.any(Object),
    );
    await act(async () => {
      await callbacks().onSuccess?.({ ...commonPreview, preview: false, runId: 'r9', created: 2 });
    });
    expect(screen.getByText('Charges created: 2')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Book' })).toBeNull();
  });

  describe('when a consumer was already charged for these days', () => {
    const overlapping: CommonConsumerRunResult = {
      ...commonPreview,
      consumers: [chargedWell, unknownLight, chargedBefore],
    };

    it('hides Book, names the run and re-previews with every consumer that is not charged yet', async () => {
      await showPreview(overlapping);
      expect(screen.queryByRole('button', { name: 'Book' })).toBeNull();
      expect(screen.getByRole('link', { name: '1 Oct 2026 – 31 Oct 2026' })).toHaveAttribute(
        'href',
        '/charges/common?run=r0',
      );
      await userEvent.click(screen.getByRole('button', { name: 'Preview without the charged consumers' }));
      // the unknown consumer stays in, so it is queued
      expect(mutate).toHaveBeenLastCalledWith(
        {
          data: { from: '2026-10-01', to: '2026-10-23', consumerIds: ['w1', 'l1'] },
          params: { preview: true },
        },
        expect.any(Object),
      );
      await act(async () => {
        await callbacks().onSuccess?.({ ...commonPreview, digest: 'd2' });
      });
      await userEvent.click(screen.getByRole('button', { name: 'Book' }));
      expect(mutate).toHaveBeenLastCalledWith(
        {
          data: { from: '2026-10-01', to: '2026-10-23', consumerIds: ['w1', 'l1'], digest: 'd2' },
          params: { preview: false },
        },
        expect.any(Object),
      );
    });

    it('says nothing can be booked when only charged consumers are left', async () => {
      await showPreview({ ...overlapping, consumers: [chargedBefore] });
      expect(screen.getByText('No consumer can be charged for this range.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Book' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Preview without the charged consumers' })).toBeNull();
    });

    it('offers a new preview when the booking meets an overlap', async () => {
      await showPreview();
      await userEvent.click(screen.getByRole('button', { name: 'Book' }));
      act(() => callbacks().onError?.(new ApiError(409, { code: 'COMMON_CONSUMER_RANGE_OVERLAP' })));
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'This common consumer has already been charged for these days',
      );
      expect(screen.getByRole('button', { name: 'Preview again' })).toBeInTheDocument();
    });
  });

  it('shows the refusal of a booking without a tariff', async () => {
    await showPreview();
    await userEvent.click(screen.getByRole('button', { name: 'Book' }));
    act(() => callbacks().onError?.(new ApiError(409, { code: 'NO_COMMON_CONSUMERS_TARIFF' })));
    expect(await screen.findByRole('alert')).toHaveTextContent('There is no tariff for common consumers');
  });

  it('lets a viewer see the history but not run or reverse', () => {
    permissions = ['CHARGES_VIEW'];
    runs = [commonRun];
    renderWithApp(withQueries(<CommonConsumersPage />));
    expect(screen.queryByRole('button', { name: 'Preview' })).toBeNull();
    expect(screen.getByText(/running needs the permission/)).toBeInTheDocument();
    expect(screen.getByText('1 Oct 2026 – 15 Oct 2026')).toBeInTheDocument();
    expect(screen.getByText(/consumers charged: 1; skipped: 1/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reverse the run' })).toBeNull();
  });

  it('opens the run named in the address and shows its consumers', () => {
    runs = [commonRun];
    renderWithApp(withQueries(<CommonConsumersPage />), { route: '/charges/common?run=r1' });
    expect(screen.getByRole('button', { name: 'Hide' })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('link', { name: 'Well pump' })).toHaveAttribute('href', '/meters/w1');
  });
});
