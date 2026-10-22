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

  it('shows the replacement chain with the service periods', () => {
    useListPlotMeters.mockReturnValue(
      queryResult([
        { ...plotMeter, id: 'm3', serial: 'EM-2', replacesMeterId: 'm1', installedOn: '2026-09-20' },
        { ...plotMeter, removedOn: '2026-09-20', finalReading: '8205.000', active: false },
      ]),
    );
    renderWithApp(<PlotMeterPanel plotId="p7" />);
    expect(screen.getByText('Replaced a previous meter')).toBeInTheDocument();
    expect(screen.getByText('In service from 20 Sep 2026')).toBeInTheDocument();
    expect(screen.getByText('In service 1 Jun 2026 – 20 Sep 2026')).toBeInTheDocument();
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
  it('shows the chain lines of each meter, with links for viewers', () => {
    useListPlotMeters.mockReturnValue(
      queryResult([
        {
          ...plotMeter,
          id: 'm3',
          serial: 'EM-2',
          replaces: { id: 'm1', serial: 'EM-1', removedOn: '2026-09-20', finalReading: '8205.000' },
        },
        {
          ...plotMeter,
          active: false,
          removedOn: '2026-09-20',
          finalReading: '8205.000',
          replacedBy: { id: 'm3', serial: 'EM-2' },
        },
      ]),
    );
    renderWithApp(<PlotMeterPanel plotId="p7" />);
    expect(screen.getByTestId('replaces')).toHaveTextContent(
      'Replaced meter EM-1: removed 20 Sep 2026, final reading 8,205 kWh',
    );
    expect(screen.getByTestId('replaced-by')).toHaveTextContent(
      'Replaced by EM-2: removed 20 Sep 2026, final reading 8,205 kWh',
    );
    expect(screen.getByRole('link', { name: 'EM-1' })).toHaveAttribute('href', '/meters/m1');
  });

  it('shows the chain as plain text to a member without METERS_VIEW', () => {
    permissions = [];
    useListPlotMeters.mockReturnValue(
      queryResult([
        {
          ...plotMeter,
          replaces: { id: 'm0', serial: 'EM-0', removedOn: '2026-05-01', finalReading: '1.000' },
        },
      ]),
    );
    renderWithApp(<PlotMeterPanel plotId="p7" />);
    expect(screen.getByTestId('replaces')).toHaveTextContent('Replaced meter EM-0');
    expect(screen.queryByRole('link')).toBeNull();
  });
});
