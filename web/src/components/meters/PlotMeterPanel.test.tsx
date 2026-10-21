import { screen } from '@testing-library/react';
import { ApiError, type Permission } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { plotMeter, queryResult } from '../../test/meterFixtures';
import { PlotMeterPanel } from './PlotMeterPanel';

const useListPlotMeters = vi.fn();
let permissions: Permission[];

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListPlotMeters: (...args: unknown[]): unknown => useListPlotMeters(...args),
}));

describe('PlotMeterPanel', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = ['METERS_VIEW'];
  });

  it('lists the meters of the plot with the last reading and a link for viewers', () => {
    useListPlotMeters.mockReturnValue(queryResult([plotMeter]));
    renderWithApp(<PlotMeterPanel plotId="p7" />);
    expect(screen.getByRole('link', { name: /EM-104523/ })).toHaveAttribute('href', '/meters/m1');
    expect(screen.getByText('8,205 kWh on 1 Jun 2026')).toBeInTheDocument();
    expect(screen.getByText('In service')).toBeInTheDocument();
  });

  it('shows plain text, no link, to a member without METERS_VIEW', () => {
    permissions = [];
    useListPlotMeters.mockReturnValue(queryResult([plotMeter]));
    renderWithApp(<PlotMeterPanel plotId="p7" />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('EM-104523')).toHaveAttribute('data-user-content');
  });

  it('says so when the plot has no meters', () => {
    useListPlotMeters.mockReturnValue(queryResult([]));
    renderWithApp(<PlotMeterPanel plotId="p7" />);
    expect(screen.getByText('The plot has no meters')).toBeInTheDocument();
  });

  it('renders nothing when the server refuses the plot', () => {
    useListPlotMeters.mockReturnValue({
      data: undefined,
      error: new ApiError(404, {}),
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    });
    const { container } = renderWithApp(<PlotMeterPanel plotId="p7" />);
    expect(container).toBeEmptyDOMElement();
  });
});
