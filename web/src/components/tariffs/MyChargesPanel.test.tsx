import { screen } from '@testing-library/react';
import type { OwnedPlot, PlotTariffs } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { MyChargesPanel } from './MyChargesPanel';

let ownedPlots: OwnedPlot[];
const useGetPlotTariffs = vi.fn();

vi.mock('../../me', () => ({ useMe: () => ({ ownedPlots }) }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetPlotTariffs: (...args: unknown[]): unknown => useGetPlotTariffs(...args),
}));

const data: PlotTariffs = { month: '2026-10', total: '0.00', items: [] };

describe('MyChargesPanel', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    useGetPlotTariffs.mockReset();
    useGetPlotTariffs.mockReturnValue({
      data,
      error: null,
      isPending: false,
      isError: false,
      refetch: vi.fn(),
    });
  });

  it('shows one block per owned plot for the current month', () => {
    ownedPlots = [
      { plotId: 'p7', number: '7', share: '1/1' },
      { plotId: 'p9', number: '9', share: '1/2' },
    ];
    renderWithApp(<MyChargesPanel />);
    expect(screen.getByRole('heading', { name: 'What I will be charged' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Plot 7' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Plot 9' })).toBeInTheDocument();
    expect(useGetPlotTariffs).toHaveBeenCalledWith('p7', { month: expect.stringMatching(/^\d{4}-\d{2}$/) });
    expect(useGetPlotTariffs).toHaveBeenCalledWith('p9', expect.anything());
  });

  it('is hidden when the member owns no plots', () => {
    ownedPlots = [];
    const { container } = renderWithApp(<MyChargesPanel />);
    expect(container).toBeEmptyDOMElement();
  });
});
