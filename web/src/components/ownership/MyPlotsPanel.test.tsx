import { screen } from '@testing-library/react';
import type { OwnedPlot, PlotOwnerships } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { MyPlotsPanel } from './MyPlotsPanel';

let ownedPlots: OwnedPlot[];

vi.mock('../../me', () => ({
  useCan: () => false,
  useMe: () => ({ ownedPlots }),
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListPlotOwnerships: (plotId: string): unknown => ({
    data: {
      plotId,
      current: [
        {
          id: 'o1',
          memberId: 'm1',
          memberName: 'Bondarenko Andrii',
          memberArchived: false,
          share: '1/2',
          validFrom: '2020-01-01',
        },
        {
          id: 'o2',
          memberId: 'm2',
          memberName: 'Moroz Halyna',
          memberArchived: false,
          share: '1/2',
          validFrom: '2020-01-01',
        },
      ],
      history: [],
    } satisfies PlotOwnerships,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

describe('MyPlotsPanel', () => {
  beforeEach(() => {
    ownedPlots = [{ plotId: 'p7', number: '7', street: 'Garden St', share: '1/2' }];
  });

  it('lists an owned plot with my share and the co-owners', () => {
    renderWithApp(<MyPlotsPanel />);
    expect(screen.getByRole('heading', { name: 'My plots' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Plot 7' })).toHaveAttribute('href', '/plots/p7');
    expect(screen.getByText('My share: 1/2')).toBeInTheDocument();
    expect(screen.getByText('Moroz Halyna')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('says so when the member owns no plots', () => {
    ownedPlots = [];
    renderWithApp(<MyPlotsPanel />);
    expect(screen.getByText('You do not own any plots')).toBeInTheDocument();
  });
});
