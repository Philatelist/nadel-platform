import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type PlotTariffs } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { PlotTariffsPanel } from './PlotTariffsPanel';

const useGetPlotTariffs = vi.fn();
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetPlotTariffs: (...args: unknown[]): unknown => useGetPlotTariffs(...args),
}));

const result = (data: PlotTariffs) => ({
  data,
  error: null,
  isPending: false,
  isError: false,
  refetch: vi.fn(),
});
const withItem: PlotTariffs = {
  month: '2026-10',
  total: '27.50',
  items: [
    {
      tariffId: 't1',
      tariffName: 'Membership 2026',
      chargeTypeId: 'c1',
      chargeTypeCode: 'MEMBERSHIP',
      chargeTypeName: 'Membership fee',
      chargeTypeNameCustomized: false,
      basis: 'PER_PLOT',
      frequency: 'MONTHLY',
      rate: '27.5000',
      status: 'AMOUNT',
      amount: '27.50',
    },
  ],
};

describe('PlotTariffsPanel', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    useGetPlotTariffs.mockReset();
    vi.useRealTimers();
  });

  it('requests the current Kyiv month by default and shows the rows', () => {
    useGetPlotTariffs.mockReturnValue(result(withItem));
    renderWithApp(<PlotTariffsPanel plotId="p1" />);
    expect(screen.getByRole('heading', { name: 'Tariffs' })).toBeInTheDocument();
    expect(screen.getByLabelText('Month')).toHaveValue(
      new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Kyiv', year: 'numeric', month: '2-digit' })
        .format(new Date())
        .slice(0, 7),
    );
    const month = (useGetPlotTariffs.mock.calls[0] as [string, { month: string }])[1].month;
    expect(month).toMatch(/^\d{4}-\d{2}$/);
    expect(screen.getByText('Membership fee')).toBeInTheDocument();
  });

  it('refetches with the chosen month', async () => {
    useGetPlotTariffs.mockReturnValue(result(withItem));
    renderWithApp(<PlotTariffsPanel plotId="p1" />);
    const input = screen.getByLabelText('Month');
    const { fireEvent } = await import('@testing-library/react');
    fireEvent.change(input, { target: { value: '2027-02' } });
    expect(useGetPlotTariffs).toHaveBeenLastCalledWith('p1', { month: '2027-02' });
    await userEvent.tab();
  });

  it('renders nothing on 404', () => {
    useGetPlotTariffs.mockReturnValue({
      data: undefined,
      error: new ApiError(404, { title: 'Not found' }),
      isPending: false,
      isError: true,
      refetch: vi.fn(),
    });
    const { container } = renderWithApp(<PlotTariffsPanel plotId="p1" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows an empty state when no tariffs apply', () => {
    useGetPlotTariffs.mockReturnValue(result({ month: '2026-10', total: '0.00', items: [] }));
    renderWithApp(<PlotTariffsPanel plotId="p1" />);
    expect(screen.getByText('No tariffs for this month')).toBeInTheDocument();
  });
});
