import {
  ApiError,
  type LossesRunDetails,
  type LossesRunResult,
  type LossesRunSummary,
  type Permission,
} from '@nadel/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  alreadyChargedNode,
  bookedRun,
  chargedNode,
  lossesPreview,
  negativeNode,
  reversedRun,
} from '../components/charges/lossesFixtures';
import { queryResult, withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { LossesPage } from './LossesPage';

type Callbacks = {
  onSuccess?: (r: LossesRunResult) => void | Promise<void>;
  onError?: (e: unknown) => void;
};

let permissions: Permission[];
let runs: LossesRunSummary[];
const mutate = vi.fn();
const reverse = vi.fn();

const details: LossesRunDetails = {
  ...bookedRun,
  nodes: [chargedNode],
  charges: [{ plotId: 'p1', plotNumber: '1', kwh: '60.000', amount: '259.20', chargeId: 'c1' }],
};

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));
vi.mock('../kyivToday', () => ({ kyivToday: () => '2026-10-23' }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useRunLosses: () => ({ mutate, isPending: false }),
  useListLossesRuns: () => queryResult(runs),
  useGetLossesRun: () => queryResult(details),
  useReverseLossesRun: () => ({ mutate: reverse, isPending: false }),
}));

function callbacks(): Callbacks {
  return mutate.mock.calls.at(-1)?.[1] as Callbacks;
}

async function showPreview(result: LossesRunResult = lossesPreview) {
  renderWithApp(withQueries(<LossesPage />));
  await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
  await act(async () => {
    await callbacks().onSuccess?.(result);
  });
}

describe('LossesPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['CHARGES_VIEW', 'CHARGES_RUN', 'CHARGES_REVERSE'];
    runs = [];
    mutate.mockReset();
    reverse.mockReset();
  });

  it('asks the server for a preview of the default range', async () => {
    renderWithApp(withQueries(<LossesPage />));
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    expect(mutate).toHaveBeenCalledWith(
      { data: { from: '2026-10-01', to: '2026-10-23' }, params: { preview: true } },
      expect.any(Object),
    );
  });

  it('shows the preview per node with the kWh, the percent, the plots and the calculated badge', async () => {
    await showPreview();
    const table = screen.getByRole('table', { name: /Losses per node/ });
    const row = within(table).getByRole('link', { name: 'Quarter A' }).closest('tr');
    expect(row).not.toBeNull();
    const cells = within(row as HTMLElement);
    expect(cells.getByText('1,100')).toBeInTheDocument();
    expect(cells.getByText('1,000')).toBeInTheDocument();
    expect(cells.getByText('100')).toBeInTheDocument();
    expect(cells.getByText('9.09%')).toBeInTheDocument();
    expect(cells.getByText('calculated')).toBeInTheDocument();
    expect(cells.getByText('Charged')).toBeInTheDocument();
    expect(cells.getByRole('link', { name: '1' })).toHaveAttribute('href', '/plots/p1');
    expect(screen.getByText('Plots charged: 2')).toBeInTheDocument();
    expect(screen.getByText('The charges will be booked in October 2026')).toBeInTheDocument();
  });

  it('writes a warning per node from its code and parameters', async () => {
    await showPreview();
    const warnings = screen.getByRole('list', { name: 'Warnings' });
    expect(warnings).toHaveTextContent(
      'Quarter B: the meters under it used more than it shows (losses −50 kWh), so nothing is charged for it.',
    );
    expect(warnings).toHaveTextContent(
      'Quarter C is skipped: the meter of plot 9 has no reading after the end of the range (last reading 30 Sep 2026).',
    );
  });

  it('books exactly the preview by its digest and reports the created charges', async () => {
    await showPreview();
    await userEvent.click(screen.getByRole('button', { name: 'Book' }));
    expect(mutate).toHaveBeenLastCalledWith(
      { data: { from: '2026-10-01', to: '2026-10-23', digest: 'd1' }, params: { preview: false } },
      expect.any(Object),
    );
    await act(async () => {
      await callbacks().onSuccess?.({ ...lossesPreview, preview: false, runId: 'r9', created: 2 });
    });
    expect(screen.getByText('Charges created: 2')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Book' })).toBeNull();
  });

  it('offers a new preview when the digest is outdated', async () => {
    await showPreview();
    await userEvent.click(screen.getByRole('button', { name: 'Book' }));
    act(() => callbacks().onError?.(new ApiError(409, { code: 'PREVIEW_OUTDATED' })));
    expect(await screen.findByRole('alert')).toHaveTextContent('The charges changed since the preview');
    await userEvent.click(screen.getByRole('button', { name: 'Preview again' }));
    expect(mutate).toHaveBeenLastCalledWith(
      { data: { from: '2026-10-01', to: '2026-10-23' }, params: { preview: true } },
      expect.any(Object),
    );
  });

  describe('when a node was already charged for these days', () => {
    const overlapping: LossesRunResult = {
      ...lossesPreview,
      nodes: [chargedNode, alreadyChargedNode],
    };

    it('refuses to book everything, names the run and lets the user book the other nodes', async () => {
      await showPreview(overlapping);
      expect(screen.queryByRole('button', { name: 'Book' })).toBeNull();
      expect(screen.getByRole('list', { name: 'Warnings' })).toHaveTextContent(
        'Losses for these days have already been calculated for node Quarter D (run 1 Oct 2026 – 31 Oct 2026).',
      );
      expect(screen.getByRole('link', { name: '1 Oct 2026 – 31 Oct 2026' })).toHaveAttribute(
        'href',
        '/charges/losses?run=r0',
      );

      await userEvent.click(screen.getByRole('button', { name: 'Preview without the charged nodes' }));
      expect(mutate).toHaveBeenLastCalledWith(
        { data: { from: '2026-10-01', to: '2026-10-23', nodeIds: ['qa'] }, params: { preview: true } },
        expect.any(Object),
      );
      await act(async () => {
        await callbacks().onSuccess?.({ ...lossesPreview, nodes: [chargedNode], digest: 'd2' });
      });
      await userEvent.click(screen.getByRole('button', { name: 'Book' }));
      expect(mutate).toHaveBeenLastCalledWith(
        {
          data: { from: '2026-10-01', to: '2026-10-23', nodeIds: ['qa'], digest: 'd2' },
          params: { preview: false },
        },
        expect.any(Object),
      );
    });

    it('says nothing can be booked when no node is left', async () => {
      await showPreview({ ...overlapping, nodes: [alreadyChargedNode, negativeNode] });
      expect(screen.getByText('No node can be charged for this range.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Book' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Preview without the charged nodes' })).toBeNull();
    });

    it('shows the overlap refusal of a booking and offers a new preview', async () => {
      await showPreview();
      await userEvent.click(screen.getByRole('button', { name: 'Book' }));
      act(() => callbacks().onError?.(new ApiError(409, { code: 'LOSSES_RANGE_OVERLAP' })));
      expect(await screen.findByRole('alert')).toHaveTextContent('have already been calculated for a node');
      expect(screen.getByRole('button', { name: 'Preview again' })).toBeInTheDocument();
    });
  });

  it('hints at the settings when losses are not proportional', async () => {
    permissions.push('SETTINGS_MANAGE');
    renderWithApp(withQueries(<LossesPage />));
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    act(() => callbacks().onError?.(new ApiError(409, { code: 'LOSSES_MODE_NOT_PROPORTIONAL' })));
    expect(await screen.findByRole('alert')).toHaveTextContent('set to a fixed percentage');
    expect(screen.getByRole('link', { name: /switch to proportional losses/ })).toHaveAttribute(
      'href',
      '/settings',
    );
  });

  it('shows the settings hint only to those who can open the settings', async () => {
    renderWithApp(withQueries(<LossesPage />));
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    act(() => callbacks().onError?.(new ApiError(409, { code: 'LOSSES_MODE_NOT_PROPORTIONAL' })));
    await screen.findByRole('alert');
    expect(screen.queryByRole('link', { name: /switch to proportional losses/ })).toBeNull();
  });

  it('keeps Preview from a future end day', async () => {
    renderWithApp(withQueries(<LossesPage />));
    const to = screen.getByLabelText('To');
    await userEvent.clear(to);
    await userEvent.type(to, '2026-11-01');
    expect(screen.getByRole('button', { name: 'Preview' })).toBeDisabled();
    expect(screen.getByText('Check the dates')).toBeInTheDocument();
  });

  it('lets a viewer see the history but not run or reverse', () => {
    permissions = ['CHARGES_VIEW'];
    runs = [bookedRun, reversedRun];
    renderWithApp(withQueries(<LossesPage />));
    expect(screen.queryByRole('button', { name: 'Preview' })).toBeNull();
    expect(screen.getByText(/running needs the permission/)).toBeInTheDocument();
    expect(screen.getByText('1 Oct 2026 – 15 Oct 2026')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reverse the run' })).toBeNull();
  });

  it('opens the run named in the address', () => {
    runs = [bookedRun];
    renderWithApp(withQueries(<LossesPage />), { route: '/charges/losses?run=r1' });
    expect(screen.getByRole('button', { name: 'Hide' })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('heading', { name: 'Charges' })).toBeInTheDocument();
  });
});
