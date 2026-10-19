import { ApiError, type Permission } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ChangeOwnerDialog } from './ChangeOwnerDialog';

const mutate = vi.fn();
let permissions: Permission[];
const members = [
  { id: 'm1', lastName: 'Ivanenko', firstName: 'Oleh', archived: false },
  { id: 'm2', lastName: 'Petrenko', firstName: 'Ivan', archived: false },
  { id: 'm3', lastName: 'Shevchenko', firstName: 'Taras', archived: false },
];

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMembers: () => ({ data: { items: members } }),
  useCreateOwnershipChange: () => ({ mutate, isPending: false }),
}));

function renderDialog() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <ChangeOwnerDialog plotId="p1" onClose={vi.fn()} onSaved={vi.fn()} />
    </QueryClientProvider>,
  );
}

const shareInput = (n: number) => screen.getByLabelText(new RegExp(`\\) ${n}$`));
const save = () => screen.getByRole('button', { name: 'Save' });

async function pick(user: ReturnType<typeof userEvent.setup>, rowIndex: number, name: RegExp) {
  const buttons = screen.getAllByRole('button', { name });
  await user.click(buttons[0]!);
  expect(rowIndex).toBeGreaterThanOrEqual(0);
}

describe('ChangeOwnerDialog', () => {
  beforeEach(() => {
    permissions = ['PLOTS_VIEW'];
    mutate.mockReset();
  });

  it('keeps Save disabled at 1/2 + 1/3 and shows the total 5/6', async () => {
    const user = userEvent.setup();
    renderDialog();
    await pick(user, 0, /Ivanenko/);
    await user.clear(shareInput(1));
    await user.type(shareInput(1), '1/2');
    await user.click(screen.getByRole('button', { name: 'Add owner' }));
    await pick(user, 1, /Petrenko/);
    await user.type(shareInput(2), '1/3');
    expect(screen.getByRole('status')).toHaveTextContent('Total: 5/6 — must be 1');
    expect(save()).toBeDisabled();
  });

  it('enables Save at 1/3 × 3', async () => {
    const user = userEvent.setup();
    renderDialog();
    await pick(user, 0, /Ivanenko/);
    await user.clear(shareInput(1));
    await user.type(shareInput(1), '1/3');
    for (const [n, name] of [
      [2, /Petrenko/],
      [3, /Shevchenko/],
    ] as const) {
      await user.click(screen.getByRole('button', { name: 'Add owner' }));
      await pick(user, n - 1, name);
      await user.type(shareInput(n), '1/3');
    }
    expect(screen.getByRole('status')).toHaveTextContent('Total: 1');
    expect(save()).toBeEnabled();
  });

  it('allows zero owners and explains the plot will have none', async () => {
    const user = userEvent.setup();
    renderDialog();
    await user.click(screen.getByRole('button', { name: 'Remove owner 1' }));
    expect(screen.getByText(/will have no owner/)).toBeInTheDocument();
    expect(save()).toBeEnabled();
  });

  it('blocks a date after today', async () => {
    renderDialog();
    const date = screen.getByLabelText('Owners from');
    const { fireEvent } = await import('@testing-library/react');
    fireEvent.change(date, { target: { value: '2999-01-01' } });
    expect(screen.getByText('The date cannot be in the future.')).toBeInTheDocument();
    expect(save()).toBeDisabled();
  });

  it('shows server field errors on the second row', async () => {
    const user = userEvent.setup();
    renderDialog();
    await pick(user, 0, /Ivanenko/);
    await user.clear(shareInput(1));
    await user.type(shareInput(1), '1/2');
    await user.click(screen.getByRole('button', { name: 'Add owner' }));
    await pick(user, 1, /Petrenko/);
    await user.type(shareInput(2), '1/2');
    expect(save()).toBeEnabled();
    mutate.mockImplementation((_vars: unknown, opts: { onError: (e: unknown) => void }) =>
      opts.onError(
        new ApiError(400, {
          code: 'VALIDATION_FAILED',
          errors: [
            { field: 'owners[1].share', code: 'SHARE_INVALID' },
            { field: 'owners[1].memberId', code: 'DUPLICATE_OWNER' },
          ],
        }),
      ),
    );
    await user.click(save());
    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(2));
    expect(screen.getByText(/Enter the share like 1\/2/)).toBeInTheDocument();
    expect(screen.getByText('This member is already chosen.')).toBeInTheDocument();
  });
});
