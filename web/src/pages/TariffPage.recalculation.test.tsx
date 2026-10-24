import { ApiError, type Permission, type Tariff } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { preview } from '../components/recalculation/recalcFixtures';
import { renderWithApp } from '../test/render';
import { TariffPage } from './TariffPage';

const addMutate = vi.fn();
const updateMutate = vi.fn();
const deleteMutate = vi.fn();
const runAsync = vi.fn();
let updateOptions: { mutation: { onError: (e: unknown) => void; onSuccess: () => void } };
let deleteOptions: { mutation: { onError: (e: unknown) => void } };
let permissions: Permission[];
let tariff: Tariff;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetTariff: () => ({ data: tariff, error: null, isPending: false, isError: false, refetch: vi.fn() }),
  useListPlots: () => ({ data: { items: [] } }),
  useUpdateTariffScope: () => ({ mutate: vi.fn(), isPending: false }),
  useUpdateTariffVersion: (o: typeof updateOptions) => {
    updateOptions = o;
    return { mutate: updateMutate, isPending: false };
  },
  useDeleteTariffVersion: (o: typeof deleteOptions) => {
    deleteOptions = o;
    return { mutate: deleteMutate, isPending: false };
  },
  useAddTariffVersion: () => ({ mutate: addMutate, isPending: false }),
  useRunRecalculation: () => ({ mutateAsync: runAsync, isPending: false }),
}));
vi.mock('../me', () => ({
  useOptionalMe: () => null,
  useCan: (permission: Permission) => permissions.includes(permission),
}));

function renderPage() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <Routes>
        <Route path="/tariffs/:id" element={<TariffPage />} />
      </Routes>
    </QueryClientProvider>,
    { route: '/tariffs/t1' },
  );
}

const version = {
  basis: 'PER_AREA_SOTKA',
  frequency: 'MONTHLY',
  pastDated: true,
} as const;

const booked = preview({ id: 'r1', preview: false, booked: true, changeApplied: true });
const empty = preview({ lines: [], totals: [], total: '0.00', plotCount: 0 });

/** Previews answer with `plan`, a booking with `booked`. */
function answer(plan = preview(), result = booked) {
  runAsync.mockImplementation(({ params }: { params: { preview: boolean } }) =>
    Promise.resolve(params.preview ? plan : result),
  );
}

describe('TariffPage: correcting versions through a recalculation', () => {
  beforeEach(() => {
    addMutate.mockClear();
    updateMutate.mockClear();
    deleteMutate.mockClear();
    runAsync.mockReset();
    permissions = ['TARIFFS_VIEW', 'TARIFFS_MANAGE', 'RECALCULATION_RUN'];
    tariff = {
      id: 't1',
      name: 'Per sotka',
      scopeKind: 'ALL_PLOTS',
      selectedPlots: [],
      exclusions: [],
      conflicts: [],
      chargeType: {
        id: 'c1',
        code: 'MEMBERSHIP',
        name: 'Membership fee',
        nameCustomized: false,
        active: true,
        position: 1,
      },
      versions: [
        { ...version, id: 'v2', validFrom: '2026-07-01', rate: '4.3200', note: 'Old note' },
        { ...version, id: 'v1', validFrom: '2026-01-01', validTo: '2026-06-30', rate: '4.0000' },
      ],
    };
  });

  async function addPastDated(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText('Valid from'), '2020-01-01');
    await user.clear(screen.getByLabelText('Rate, ₴'));
    await user.type(screen.getByLabelText('Rate, ₴'), '6');
    await user.click(screen.getByRole('button', { name: 'Add version' }));
  }

  it('shows the preview first for a version that starts before next month and books it with the reason', async () => {
    answer();
    const user = userEvent.setup();
    renderPage();
    await addPastDated(user);
    const versionData = {
      validFrom: '2020-01-01',
      basis: 'PER_AREA_SOTKA',
      frequency: 'MONTHLY',
      rate: '6',
      dueDate: undefined,
      note: undefined,
    };
    const cause = { type: 'TARIFF_VERSION_ADD', tariffVersion: { tariffId: 't1', version: versionData } };
    expect(addMutate).not.toHaveBeenCalled();
    expect(runAsync).toHaveBeenCalledWith({ data: { cause }, params: { preview: true } });
    await user.type(await screen.findByLabelText(/^Reason/), 'Back-dated rate');
    await user.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(await screen.findByText('Version added, recalculation booked')).toBeInTheDocument();
    expect(runAsync).toHaveBeenLastCalledWith({
      data: { cause, digest: 'digest-1', reason: 'Back-dated rate' },
      params: { preview: false },
    });
  });

  it('saves a version with an empty plan through the same endpoint, without a reason', async () => {
    answer(empty, preview({ preview: false, booked: false, changeApplied: true }));
    const user = userEvent.setup();
    renderPage();
    await addPastDated(user);
    expect(await screen.findByText('Version added')).toBeInTheDocument();
    expect(runAsync).toHaveBeenCalledTimes(2);
    expect(runAsync.mock.calls[1]?.[0]).toMatchObject({
      data: { digest: 'digest-1' },
      params: { preview: false },
    });
    expect(runAsync.mock.calls[1]?.[0].data).not.toHaveProperty('reason');
    expect(addMutate).not.toHaveBeenCalled();
  });

  it('saves a version that starts next month or later plainly', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.type(screen.getByLabelText('Valid from'), '2999-01-01');
    await user.clear(screen.getByLabelText('Rate, ₴'));
    await user.type(screen.getByLabelText('Rate, ₴'), '6');
    await user.click(screen.getByRole('button', { name: 'Add version' }));
    expect(addMutate).toHaveBeenCalledTimes(1);
    expect(runAsync).not.toHaveBeenCalled();
  });

  it('saves a past-dated version plainly without RECALCULATION_RUN and keeps the warning', async () => {
    permissions = ['TARIFFS_VIEW', 'TARIFFS_MANAGE'];
    const user = userEvent.setup();
    renderPage();
    await addPastDated(user);
    expect(addMutate).toHaveBeenCalledTimes(1);
    expect(runAsync).not.toHaveBeenCalled();
    expect(screen.getByText('Charges for past periods will be recalculated')).toBeInTheDocument();
  });

  it('puts a rejected field of the recalculation request on the form field', async () => {
    runAsync.mockRejectedValue(
      new ApiError(400, {
        code: 'VALIDATION_FAILED',
        status: 400,
        title: 'x',
        errors: [{ field: 'cause.tariffVersion.version.rate', code: 'RATE_INVALID' }],
      }),
    );
    const user = userEvent.setup();
    renderPage();
    await addPastDated(user);
    expect(await screen.findByText(/rate/i, { selector: '[id$="-error"], p, span' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Book the recalculation' })).toBeNull();
  });

  it('opens step 2 for a used version on 409 and books the update with the digest and the reason', async () => {
    answer();
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[0]!;
    await user.click(within(first).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit version' });
    await user.clear(within(form).getByLabelText('Rate, ₴'));
    await user.type(within(form).getByLabelText('Rate, ₴'), '5');
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    act(() => updateOptions.mutation.onError(new ApiError(409, { code: 'TARIFF_VERSION_IN_USE' })));
    await user.type(await screen.findByLabelText(/^Reason/), 'Wrong rate');
    await user.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(await screen.findByText('Version corrected, recalculation booked')).toBeInTheDocument();
    expect(runAsync).toHaveBeenLastCalledWith({
      data: {
        cause: {
          type: 'TARIFF_VERSION_UPDATE',
          tariffVersion: {
            tariffId: 't1',
            versionId: 'v2',
            version: expect.objectContaining({ validFrom: '2026-07-01', rate: '5' }),
          },
        },
        digest: 'digest-1',
        reason: 'Wrong rate',
      },
      params: { preview: false },
    });
  });

  it('goes back from step 2 to the filled edit form', async () => {
    answer();
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[0]!;
    await user.click(within(first).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit version' });
    await user.clear(within(form).getByLabelText('Rate, ₴'));
    await user.type(within(form).getByLabelText('Rate, ₴'), '5');
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    act(() => updateOptions.mutation.onError(new ApiError(409, { code: 'TARIFF_VERSION_IN_USE' })));
    await user.click(await screen.findByRole('button', { name: 'Back' }));
    expect(within(screen.getByRole('form', { name: 'Edit version' })).getByLabelText('Rate, ₴')).toHaveValue(
      '5',
    );
  });

  it('opens step 2 for the deletion of a used version and books it', async () => {
    answer();
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[0]!;
    await user.click(within(first).getByRole('button', { name: 'Delete' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    act(() => deleteOptions.mutation.onError(new ApiError(409, { code: 'TARIFF_VERSION_IN_USE' })));
    await user.type(await screen.findByLabelText(/^Reason/), 'Entered by mistake');
    await user.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(await screen.findByText('Version deleted, recalculation booked')).toBeInTheDocument();
    expect(runAsync).toHaveBeenLastCalledWith({
      data: {
        cause: { type: 'TARIFF_VERSION_DELETE', tariffVersion: { tariffId: 't1', versionId: 'v2' } },
        digest: 'digest-1',
        reason: 'Entered by mistake',
      },
      params: { preview: false },
    });
  });

  it('keeps a note-only edit a plain save', async () => {
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[0]!;
    await user.click(within(first).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit version' });
    await user.type(within(form).getByLabelText('Note'), ' extended');
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    expect(updateMutate).toHaveBeenCalledTimes(1);
    await act(async () => updateOptions.mutation.onSuccess());
    await waitFor(() => expect(screen.getByText('Version corrected')).toBeInTheDocument());
    expect(runAsync).not.toHaveBeenCalled();
  });

  it('gives a user without RECALCULATION_RUN the 409 message and no step 2', async () => {
    permissions = ['TARIFFS_VIEW', 'TARIFFS_MANAGE'];
    const user = userEvent.setup();
    renderPage();
    const first = within(screen.getByRole('list')).getAllByRole('listitem')[0]!;
    await user.click(within(first).getByRole('button', { name: 'Edit' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    act(() => updateOptions.mutation.onError(new ApiError(409, { code: 'TARIFF_VERSION_IN_USE' })));
    expect(await screen.findByText(/already used in charges/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Book the recalculation' })).toBeNull();
    expect(runAsync).not.toHaveBeenCalled();
  });
});
