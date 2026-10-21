import { screen } from '@testing-library/react';
import type { Meter, Permission } from '@nadel/api-client';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { withQueries } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { plotMeter, queryResult, wellMeter } from '../test/meterFixtures';
import { MeterPage } from './MeterPage';

let permissions: Permission[];
let meter: Meter;

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMeter: () => queryResult(meter),
  useDeleteMeter: () => ({ mutate: vi.fn(), isPending: false }),
  useUpdateMeter: () => ({ mutate: vi.fn(), isPending: false }),
  useRemoveMeter: () => ({ mutate: vi.fn(), isPending: false }),
}));

function renderCard() {
  return renderWithApp(
    withQueries(
      <Routes>
        <Route path="/meters/:id" element={<MeterPage />} />
      </Routes>,
    ),
    { route: '/meters/m1' },
  );
}

describe('MeterPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['METERS_VIEW', 'METERS_MANAGE'];
    meter = plotMeter;
  });

  it('shows the details with units, the note and the history placeholder', () => {
    renderCard();
    expect(screen.getByText('Initial reading')).toBeInTheDocument();
    expect(screen.getAllByText('8,205 kWh').length).toBeGreaterThan(0);
    expect(screen.getByText('Behind the gate')).toHaveAttribute('data-user-content');
    expect(screen.getByRole('link', { name: '7' })).toHaveAttribute('href', '/plots/p7');
    expect(screen.getByText('The reading history will appear here.')).toBeInTheDocument();
  });

  it('offers edit, remove and delete to a manager of a deletable active meter', () => {
    renderCard();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });

  it('hides delete when the meter is not deletable and remove when it is already removed', () => {
    meter = { ...plotMeter, deletable: false };
    const { unmount } = renderCard();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument();
    unmount();
    meter = wellMeter;
    renderCard();
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
    expect(screen.getByText('Removed on')).toBeInTheDocument();
  });

  it('shows no actions without METERS_MANAGE', () => {
    permissions = ['METERS_VIEW'];
    renderCard();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });
});
