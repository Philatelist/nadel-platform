import type { NotChargedItem, Permission } from '@nadel/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult, withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { NotChargedPage } from './NotChargedPage';

let permissions: Permission[];
let notCharged: NotChargedItem[];
const mutate = vi.fn();
let mutation: { onSuccess: (r: { resolved: boolean }) => Promise<void> };

const item: NotChargedItem = {
  id: 'n1',
  kind: 'TARIFF_MONTH',
  reason: 'CONFLICTING_TARIFFS',
  plotId: 'p7',
  plotNumber: '7',
  chargeType: { id: 't2', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false },
  month: '2026-09',
  tariffs: [
    { id: 'tf1', name: 'Old fee' },
    { id: 'tf2', name: 'New fee' },
  ],
};

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListNotCharged: () => queryResult(notCharged),
  useRetryNotCharged: (o: { mutation: typeof mutation }) => {
    mutation = o.mutation;
    return { mutate, isPending: false };
  },
}));

const intervalItem: NotChargedItem = {
  id: 'n2',
  kind: 'INTERVAL',
  reason: 'NO_TARIFF_FOR_ALL_DAYS',
  meterId: 'm9',
  chargeType: { id: 't1', code: 'ELECTRICITY', name: 'Electricity', nameCustomized: false },
  from: '2026-09-23',
  to: '2026-10-04',
  tariffs: [],
};

const lossesItem: NotChargedItem = {
  id: 'n3',
  kind: 'LOSSES_NODE',
  reason: 'UNKNOWN_NODE_VALUE',
  meterId: 'qc',
  chargeType: { id: 't1', code: 'ELECTRICITY', name: 'Electricity', nameCustomized: false },
  from: '2026-10-01',
  to: '2026-10-23',
  tariffs: [],
};

describe('NotChargedPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['CHARGES_VIEW', 'CHARGES_RUN'];
    mutate.mockReset();
    notCharged = [item];
  });

  it('lists the item with plot and tariff links', () => {
    renderWithApp(withQueries(<NotChargedPage />));
    expect(screen.getByRole('link', { name: '7' })).toHaveAttribute('href', '/plots/p7');
    expect(screen.getByRole('link', { name: 'Old fee' })).toHaveAttribute('href', '/tariffs/tf1');
    expect(screen.getByRole('link', { name: 'New fee' })).toHaveAttribute('href', '/tariffs/tf2');
    expect(screen.getByText('September 2026')).toBeInTheDocument();
  });

  it('shows the period and a meter link for an interval item', () => {
    notCharged = [intervalItem];
    renderWithApp(withQueries(<NotChargedPage />));
    expect(screen.getByText('23 Sep 2026 – 4 Oct 2026')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Meter' })).toHaveAttribute('href', '/meters/m9');
  });

  it('shows a losses node with its node link, the period and a retry', async () => {
    notCharged = [lossesItem];
    renderWithApp(withQueries(<NotChargedPage />));
    expect(screen.getByRole('link', { name: 'Node' })).toHaveAttribute('href', '/meters/qc');
    expect(screen.getByText('1 Oct 2026 – 23 Oct 2026')).toBeInTheDocument();
    expect(screen.getByText('A meter value of the period is unknown')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(mutate).toHaveBeenCalledWith({ id: 'n3' });
  });

  it('retries and reports an item that is still not charged', async () => {
    renderWithApp(withQueries(<NotChargedPage />));
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(mutate).toHaveBeenCalledWith({ id: 'n1' });
    await act(() => mutation.onSuccess({ resolved: false }));
    expect(screen.getByText('Still not charged')).toBeInTheDocument();
  });

  it('does not report a resolved retry as still not charged', async () => {
    renderWithApp(withQueries(<NotChargedPage />));
    await act(() => mutation.onSuccess({ resolved: true }));
    expect(screen.queryByText('Still not charged')).not.toBeInTheDocument();
  });

  it('hides Retry without CHARGES_RUN', () => {
    permissions = ['CHARGES_VIEW'];
    renderWithApp(withQueries(<NotChargedPage />));
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });
});
