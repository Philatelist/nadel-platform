import { ApiError, type Permission, type Tariff } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ScopeEditor } from './ScopeEditor';

const mutate = vi.fn();
let options: { mutation: { onError: (e: unknown) => void } };
let permissions: Permission[];
let tariff: Tariff;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useUpdateTariffScope: (o: typeof options) => {
    options = o;
    return { mutate, isPending: false };
  },
  useListPlots: () => ({
    data: {
      items: [
        { id: 'p5', number: '5', kind: 'PLOT', archived: false },
        { id: 'p6', number: '6', kind: 'PLOT', archived: false },
      ],
    },
  }),
}));
vi.mock('../../me', () => ({
  useOptionalMe: () => null,
  useCan: (p: Permission) => permissions.includes(p),
}));

const base: Tariff = {
  id: 't1',
  name: 'Per sotka',
  scopeKind: 'ALL_PLOTS',
  plotCount: 41,
  selectedPlots: [],
  exclusions: [
    { plotId: 'p1', number: '1', archived: false, reason: 'Vacant' },
    { plotId: 'p2', number: '2', archived: true, reason: 'Sold' },
  ],
  chargeType: { id: 'c1', code: 'MEMBERSHIP', name: 'Fee', nameCustomized: false, active: true, position: 1 },
  versions: [],
  conflicts: [],
};

function renderEditor() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <ScopeEditor tariff={tariff} />
    </QueryClientProvider>,
  );
}

describe('ScopeEditor', () => {
  beforeEach(() => {
    mutate.mockClear();
    permissions = ['TARIFFS_VIEW', 'TARIFFS_MANAGE'];
    tariff = base;
  });

  it('shows all plots with the exclusions, reasons, archived mark and the plot count', () => {
    renderEditor();
    expect(screen.getByText('All plots')).toBeInTheDocument();
    expect(screen.getByText(/41 plots/)).toBeInTheDocument();
    expect(screen.getByText('Vacant')).toBeInTheDocument();
    expect(screen.getByText('Sold')).toBeInTheDocument();
    expect(screen.getByText('archived')).toBeInTheDocument();
  });

  it('shows selected plots and the count of plots with an active meter', () => {
    tariff = {
      ...base,
      scopeKind: 'SELECTED_PLOTS',
      plotCount: 3,
      exclusions: [],
      selectedPlots: [{ plotId: 'p7', number: '7', archived: false }],
    };
    renderEditor();
    expect(screen.getByText('Selected plots')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.getByText(/3 plots/)).toBeInTheDocument();
  });

  it('is read-only without TARIFFS_MANAGE', () => {
    permissions = ['TARIFFS_VIEW'];
    renderEditor();
    expect(screen.getByText('Vacant')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change' })).not.toBeInTheDocument();
  });

  it('refuses an exclusion without a reason on the client', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(screen.getByRole('button', { name: 'Change' }));
    await user.click(screen.getByRole('button', { name: '5' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(mutate).not.toHaveBeenCalled();
    expect(screen.getByText('Enter a reason')).toBeInTheDocument();
  });

  it('saves the full replace for all plots with trimmed reasons', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(screen.getByRole('button', { name: 'Change' }));
    await user.click(screen.getByRole('button', { name: 'Remove plot 2' }));
    await user.click(screen.getByRole('button', { name: '5' }));
    const reasons = screen.getAllByLabelText('Reason');
    await user.type(reasons[1]!, ' Empty ');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(mutate).toHaveBeenCalledWith({
      id: 't1',
      data: {
        kind: 'ALL_PLOTS',
        exclusions: [
          { plotId: 'p1', reason: 'Vacant' },
          { plotId: 'p5', reason: 'Empty' },
        ],
      },
    });
  });

  it('warns on switching to selected plots and sends no exclusions then', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(screen.getByRole('button', { name: 'Change' }));
    await user.click(screen.getByRole('radio', { name: 'Selected plots' }));
    expect(screen.getByText('Exclusions will be removed when you save.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '6' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(mutate).toHaveBeenCalledWith({ id: 't1', data: { kind: 'SELECTED_PLOTS', plotIds: ['p6'] } });
  });

  it('warns on switching to all plots and sends no plot list then', async () => {
    const user = userEvent.setup();
    tariff = {
      ...base,
      scopeKind: 'SELECTED_PLOTS',
      exclusions: [],
      selectedPlots: [{ plotId: 'p7', number: '7', archived: false }],
    };
    renderEditor();
    await user.click(screen.getByRole('button', { name: 'Change' }));
    await user.click(screen.getByRole('radio', { name: 'All plots' }));
    expect(screen.getByText('The selected plots will be removed when you save.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(mutate).toHaveBeenCalledWith({ id: 't1', data: { kind: 'ALL_PLOTS', exclusions: [] } });
  });

  it('puts a server field error on the row', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(screen.getByRole('button', { name: 'Change' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    act(() =>
      options.mutation.onError(
        new ApiError(400, {
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'exclusions[1].plotId', code: 'PLOT_ARCHIVED' }],
        }),
      ),
    );
    expect(await screen.findByText('The plot is archived')).toBeInTheDocument();
  });

  it('shows an archived charge type as a notice', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(screen.getByRole('button', { name: 'Change' }));
    act(() => options.mutation.onError(new ApiError(409, { code: 'CHARGE_TYPE_ARCHIVED' })));
    expect(await screen.findByText('This charge type is archived.')).toBeInTheDocument();
  });

  // @spec: 012-retroactive-recalculation (acceptance 2.6: the hint after a tariff's plots or exclusions change)
  // @regression
  it('reminds a holder of RECALCULATION_RUN that past charges are unchanged after the scope is saved', async () => {
    permissions = ['TARIFFS_VIEW', 'TARIFFS_MANAGE', 'RECALCULATION_RUN'];
    const user = userEvent.setup();
    renderEditor();
    expect(screen.queryByRole('link', { name: 'Recalculation' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Change' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await act(async () => {
      await options.mutation.onSuccess();
    });
    expect(screen.getByText(/Past charges have not changed/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Recalculation' })).toHaveAttribute(
      'href',
      '/charges/recalculations/new',
    );
  });

  // @spec: 012-retroactive-recalculation
  // @regression
  it('shows no recalculation hint after the scope is saved without RECALCULATION_RUN', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(screen.getByRole('button', { name: 'Change' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await act(async () => {
      await options.mutation.onSuccess();
    });
    expect(screen.getByRole('button', { name: 'Change' })).toBeInTheDocument();
    expect(screen.queryByText(/Past charges have not changed/)).not.toBeInTheDocument();
  });
});
