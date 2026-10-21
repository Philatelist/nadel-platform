import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type ChargeType, type Permission } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { ChargeTypesPage } from './ChargeTypesPage';
import { hasText } from '../test/hasText';

interface Options {
  onSuccess: (...args: unknown[]) => unknown;
  onError: (error: unknown) => unknown;
}

let types: ChargeType[];
let permissions: Permission[];
let create: Options;
let reorder: Options;
const createMutate = vi.fn();
const updateMutate = vi.fn();
const reorderMutate = vi.fn();
const archiveMutate = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListChargeTypes: () => ({
    data: types,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useCreateChargeType: (o: { mutation: Options }) => {
    create = o.mutation;
    return { mutate: createMutate, isPending: false };
  },
  useUpdateChargeType: () => {
    return { mutate: updateMutate, isPending: false };
  },
  useReorderChargeTypes: (o: { mutation: Options }) => {
    reorder = o.mutation;
    return { mutate: reorderMutate, isPending: false };
  },
  useArchiveChargeType: () => {
    return { mutate: archiveMutate, isPending: false };
  },
}));
vi.mock('../me', () => ({
  useOptionalMe: () => null,
  useCan: (permission: Permission) => permissions.includes(permission),
}));

function renderPage() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <ChargeTypesPage />
    </QueryClientProvider>,
  );
}

const row = (name: string) => screen.getByText(name).closest('li')!;

describe('ChargeTypesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['TARIFFS_VIEW', 'TARIFFS_MANAGE'];
    types = [
      {
        id: 'c1',
        code: 'MEMBERSHIP',
        name: 'Membership fee',
        nameCustomized: false,
        active: true,
        position: 1,
      },
      {
        id: 'c2',
        code: 'ELECTRICITY',
        name: 'Electricity',
        nameCustomized: true,
        description: 'Per kWh',
        active: true,
        position: 2,
      },
      { id: 'c3', code: 'WATER', name: 'Water', nameCustomized: false, active: false, position: 3 },
    ];
  });

  it('lists the types in order with description and active marks', () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Charge types' })).toBeInTheDocument();
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(within(row('Electricity')).getByText('Per kWh')).toBeInTheDocument();
    expect(within(row('Water')).getByText('Inactive')).toBeInTheDocument();
    expect(within(row('Membership fee')).getByText('Active')).toBeInTheDocument();
  });

  it('adds a type through the dialog', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: 'Add charge type' }));
    await user.type(screen.getByLabelText('Name'), 'Road repair');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(createMutate).toHaveBeenCalledWith({ data: { name: 'Road repair', description: undefined } });
  });

  it('shows the name-taken message in the dialog and keeps it open', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('button', { name: 'Add charge type' }));
    await user.type(screen.getByLabelText('Name'), 'Water');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    act(() => create.onError(new ApiError(409, { code: 'CHARGE_TYPE_NAME_TAKEN' })));
    expect(await screen.findByRole('alert')).toHaveTextContent('A charge type with this name already exists');
    expect(screen.getByLabelText('Name')).toHaveValue('Water');
  });

  it('edits a type, sending the displayed default name of a seeded one unchanged', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(within(row('Water')).getByRole('button', { name: 'Edit' }));
    expect(screen.getByLabelText('Name')).toHaveValue('Water');
    await user.click(screen.getByRole('checkbox', { name: /Active/ }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(updateMutate).toHaveBeenCalledWith({
      id: 'c3',
      data: { name: 'Water', description: undefined, active: true },
    });
  });

  it('sends the whole new id list when a type moves up', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(within(row('Water')).getByRole('button', { name: 'Move up' }));
    expect(reorderMutate).toHaveBeenCalledWith({ data: { ids: ['c1', 'c3', 'c2'] } });
  });

  it('sends the whole new id list when a type moves down and disables the edges', async () => {
    const user = userEvent.setup();
    renderPage();
    expect(within(row('Membership fee')).getByRole('button', { name: 'Move up' })).toBeDisabled();
    expect(within(row('Water')).getByRole('button', { name: 'Move down' })).toBeDisabled();
    await user.click(within(row('Membership fee')).getByRole('button', { name: 'Move down' }));
    expect(reorderMutate).toHaveBeenCalledWith({ data: { ids: ['c2', 'c1', 'c3'] } });
  });

  it('shows the reload message when the order is stale', () => {
    renderPage();
    act(() => void reorder.onError(new ApiError(409, { code: 'CHARGE_TYPE_ORDER_STALE' })));
    expect(screen.getByRole('alert')).toHaveTextContent('The list of charge types has changed');
  });

  it('archives only after confirmation', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(within(row('Water')).getByRole('button', { name: 'Archive' }));
    expect(archiveMutate).not.toHaveBeenCalled();
    expect(screen.getByText(hasText(/Archive "Water"\?/))).toBeInTheDocument();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Archive' }));
    expect(archiveMutate).toHaveBeenCalledWith({ id: 'c3' });
  });

  it('is read-only without TARIFFS_MANAGE', () => {
    permissions = ['TARIFFS_VIEW'];
    renderPage();
    expect(screen.getByText('Membership fee')).toBeInTheDocument();
    for (const name of ['Add charge type', 'Edit', 'Archive', 'Move up', 'Move down']) {
      expect(screen.queryByRole('button', { name })).toBeNull();
    }
  });
});
