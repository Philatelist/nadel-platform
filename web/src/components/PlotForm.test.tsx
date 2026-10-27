import { ApiError, type PlotDetails } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { convertAreaText, PlotForm } from './PlotForm';

let canRecalculate = false;
vi.mock('../me', () => ({
  useCan: () => canRecalculate,
  useMe: () => ({ permissions: [] }),
  useOptionalMe: () => null,
}));

const createMutate = vi.fn();
const updateMutate = vi.fn();
let updateOptions: { mutation: { onError: (e: unknown) => void } };
let createOptions: { mutation: { onError: (e: unknown) => void } };

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useCreatePlot: (options: typeof createOptions) => {
    createOptions = options;
    return { mutate: createMutate, isPending: false };
  },
  useUpdatePlot: (options: typeof updateOptions) => {
    updateOptions = options;
    return { mutate: updateMutate, isPending: false };
  },
}));

const existing: PlotDetails = {
  id: 'p1',
  number: '12',
  kind: 'PLOT',
  status: 'GREY',
  archived: false,
  areaM2: 650,
};

function renderForm(plot?: PlotDetails) {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <PlotForm plot={plot} onSaved={vi.fn()} onCancel={vi.fn()} />
    </QueryClientProvider>,
  );
}

function conflict(code: string, field: string): ApiError {
  return new ApiError(409, { code, errors: [{ field, code }] });
}

describe('convertAreaText', () => {
  it('converts between units and keeps invalid text', () => {
    expect(convertAreaText('6,5', 'SOTKA', 'M2')).toBe('650');
    expect(convertAreaText('650', 'M2', 'SOTKA')).toBe('6.5');
    expect(convertAreaText('abc', 'M2', 'SOTKA')).toBe('abc');
  });
});

describe('PlotForm', () => {
  beforeEach(() => {
    canRecalculate = false;
    createMutate.mockClear();
    updateMutate.mockClear();
  });

  it('submits the area in square metres converted from sotkas', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText('Number'), '12');
    await user.type(screen.getByLabelText('Area'), '6,5');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(createMutate).toHaveBeenCalledWith({
      data: expect.objectContaining({ number: '12', kind: 'PLOT', areaM2: 650 }),
    });
  });

  it('converts the shown value when the unit changes', async () => {
    const user = userEvent.setup();
    renderForm(existing);
    expect(screen.getByLabelText('Area')).toHaveValue('6.5');
    await user.selectOptions(screen.getByLabelText('Unit'), 'M2');
    expect(screen.getByLabelText('Area')).toHaveValue('650');
  });

  it('checks required fields, cadastral format and a positive plot area on the client', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText('Cadastral number'), '123');
    await user.type(screen.getByLabelText('Area'), '0');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByText('Number is required.')).toBeInTheDocument();
    expect(screen.getByText('Use the form 1234567890:12:345:6789.')).toBeInTheDocument();
    expect(screen.getByText(/Enter a valid area/)).toBeInTheDocument();
    expect(createMutate).not.toHaveBeenCalled();
  });

  it('allows zero area for common land', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText('Number'), 'R-1');
    await user.selectOptions(screen.getByLabelText('Kind'), 'COMMON');
    await user.type(screen.getByLabelText('Area'), '0');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(createMutate).toHaveBeenCalledWith({ data: expect.objectContaining({ areaM2: 0 }) });
  });

  it('maps server conflicts onto the number and cadastral number fields', async () => {
    renderForm();
    createOptions.mutation.onError(conflict('PLOT_NUMBER_TAKEN', 'number'));
    expect(await screen.findByText('Another active plot already has this number.')).toBeInTheDocument();
    createOptions.mutation.onError(conflict('CADASTRAL_NUMBER_TAKEN', 'cadastralNumber'));
    expect(
      await screen.findByText('Another plot of the cooperative already has this cadastral number.'),
    ).toBeInTheDocument();
  });

  it('shows the ownership-history conflict under the kind field', async () => {
    renderForm(existing);
    updateOptions.mutation.onError(conflict('PLOT_HAS_OWNERSHIP_HISTORY', 'kind'));
    expect(
      await screen.findByText('This plot has had owners and cannot become common land'),
    ).toBeInTheDocument();
  });

  it('shows the water-connection conflict under the kind field', async () => {
    renderForm(existing);
    updateOptions.mutation.onError(conflict('PLOT_HAS_WATER_CONNECTION', 'kind'));
    expect(
      await screen.findByText(
        'The plot has a lasting water connection and cannot become common land — disconnect it first',
      ),
    ).toBeInTheDocument();
  });

  it('reminds holders of RECALCULATION_RUN that past charges stay as they are, when a plot is edited', () => {
    canRecalculate = true;
    renderForm(existing);
    expect(screen.getByRole('link', { name: 'Recalculation' })).toHaveAttribute(
      'href',
      '/charges/recalculations/new',
    );
  });

  it('shows no recalculation hint to others or on a new plot', () => {
    canRecalculate = true;
    renderForm();
    expect(screen.queryByRole('link', { name: 'Recalculation' })).not.toBeInTheDocument();
    canRecalculate = false;
    renderForm(existing);
    expect(screen.queryAllByRole('link', { name: 'Recalculation' })).toHaveLength(0);
  });
});
