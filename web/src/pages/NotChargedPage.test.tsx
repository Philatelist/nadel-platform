import type { NotChargedItem, Permission } from '@nadel/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult, withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { NotChargedPage } from './NotChargedPage';

let permissions: Permission[];
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
  useListNotCharged: () => queryResult([item]),
  useRetryNotCharged: (o: { mutation: typeof mutation }) => {
    mutation = o.mutation;
    return { mutate, isPending: false };
  },
}));

describe('NotChargedPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['CHARGES_VIEW', 'CHARGES_RUN'];
    mutate.mockReset();
  });

  it('lists the item with plot and tariff links', () => {
    renderWithApp(withQueries(<NotChargedPage />));
    expect(screen.getByRole('link', { name: '7' })).toHaveAttribute('href', '/plots/p7');
    expect(screen.getByRole('link', { name: 'Old fee' })).toHaveAttribute('href', '/tariffs/tf1');
    expect(screen.getByRole('link', { name: 'New fee' })).toHaveAttribute('href', '/tariffs/tf2');
    expect(screen.getByText('September 2026')).toBeInTheDocument();
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
