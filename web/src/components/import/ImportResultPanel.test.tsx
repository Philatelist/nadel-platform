import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ImportResult } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ImportResultPanel } from './ImportResultPanel';

let canViewMembers = true;
vi.mock('../../me', () => ({ useCan: () => canViewMembers }));

const result: ImportResult = {
  id: 'i1',
  counts: {
    plots: { created: 5, updated: 3, unchanged: 7 },
    members: { created: 2, updated: 0, unchanged: 1 },
    ownerships: { created: 4, updated: 0, unchanged: 0 },
    meters: { created: 6, updated: 0, unchanged: 2 },
  },
};

describe('ImportResultPanel', () => {
  beforeEach(() => {
    canViewMembers = true;
  });

  it('shows the counts and both links', () => {
    renderWithApp(<ImportResultPanel result={result} onReset={vi.fn()} />);
    const row = within(screen.getByRole('row', { name: /Plots/ })).getAllByRole('cell');
    expect(row.map((c) => c.textContent)).toEqual(['Plots', '5', '3', '7']);
    const meters = within(screen.getByRole('row', { name: /Meters/ })).getAllByRole('cell');
    expect(meters.map((c) => c.textContent)).toEqual(['Meters', '6', '0', '2']);
    expect(screen.getByRole('link', { name: 'Go to plots' })).toHaveAttribute('href', '/plots');
    expect(screen.getByRole('link', { name: 'Go to members' })).toHaveAttribute('href', '/members');
  });

  it('omits the members link without MEMBERS_VIEW', () => {
    canViewMembers = false;
    renderWithApp(<ImportResultPanel result={result} onReset={vi.fn()} />);
    expect(screen.queryByRole('link', { name: 'Go to members' })).not.toBeInTheDocument();
  });

  it('resets on "Choose another file"', async () => {
    const onReset = vi.fn();
    renderWithApp(<ImportResultPanel result={result} onReset={onReset} />);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Choose another file' }));
    expect(onReset).toHaveBeenCalledOnce();
  });
});
