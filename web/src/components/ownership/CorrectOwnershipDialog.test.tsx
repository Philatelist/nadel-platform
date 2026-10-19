import { ApiError, type Ownership, type Permission, type PlotOwnerships } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { CorrectOwnershipDialog } from './CorrectOwnershipDialog';

const mutate = vi.fn();
const permissions: Permission[] = ['PLOTS_VIEW'];

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMembers: () => ({ data: { items: [] } }),
  useCreateOwnershipCorrection: () => ({ mutate, isPending: false }),
}));

const base = { memberArchived: false, validFrom: '2025-06-01' };
const target: Ownership = {
  ...base,
  id: 'o1',
  memberId: 'm1',
  memberName: 'Ivanenko Oleh',
  memberArchived: true,
  share: '1/2',
  basis: 'Sale',
};
const coOwner: Ownership = { ...base, id: 'o2', memberId: 'm2', memberName: 'Petrenko Ivan', share: '1/2' };
const older: Ownership = {
  ...base,
  id: 'o0',
  memberId: 'm3',
  memberName: 'Older Owner',
  share: '1',
  validFrom: '2010-01-01',
  validTo: '2025-05-31',
};
const ownerships: PlotOwnerships = { plotId: 'p1', current: [target, coOwner], history: [older] };

function renderDialog() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <CorrectOwnershipDialog
        plotId="p1"
        ownership={target}
        ownerships={ownerships}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    </QueryClientProvider>,
  );
}

const save = () => screen.getByRole('button', { name: 'Save' });
const ownShare = () => screen.getByLabelText('Share (like 1/2)');
const coShare = () => screen.getByLabelText('Share of Petrenko Ivan (like 1/2)');

describe('CorrectOwnershipDialog', () => {
  beforeEach(() => {
    mutate.mockReset();
  });

  it('is prefilled from the record and lists only overlapping co-owners', () => {
    renderDialog();
    expect(screen.getByText('Ivanenko Oleh')).toBeInTheDocument();
    expect(ownShare()).toHaveValue('1/2');
    expect(screen.getByLabelText('Owner from')).toHaveValue('2025-06-01');
    expect(screen.getByLabelText('Owner until (empty for a current owner)')).toHaveValue('');
    expect(screen.getByLabelText(/^Basis/)).toHaveValue('Sale');
    expect(coShare()).toHaveValue('1/2');
    expect(screen.queryByLabelText(/Older Owner/)).toBeNull();
  });

  it('requires a reason', async () => {
    const user = userEvent.setup();
    renderDialog();
    expect(save()).toBeDisabled();
    const reason = screen.getByLabelText('Reason');
    await user.type(reason, 'x');
    expect(save()).toBeEnabled();
    await user.clear(reason);
    expect(screen.getByText('This field is required.')).toBeInTheDocument();
    expect(save()).toBeDisabled();
  });

  it('enables Save at 2/3 + 1/3 and blocks 1/2 + 1/3 showing 5/6', async () => {
    const user = userEvent.setup();
    renderDialog();
    await user.type(screen.getByLabelText('Reason'), 'Wrong share');
    await user.clear(ownShare());
    await user.type(ownShare(), '2/3');
    await user.clear(coShare());
    await user.type(coShare(), '1/3');
    expect(screen.getByRole('status')).toHaveTextContent('Total: 1');
    expect(save()).toBeEnabled();
    await user.clear(ownShare());
    await user.type(ownShare(), '1/2');
    expect(screen.getByRole('status')).toHaveTextContent('Total: 5/6 — must be 1');
    expect(save()).toBeDisabled();
  });

  it('sends the full replacement with the co-owner shares', async () => {
    const user = userEvent.setup();
    renderDialog();
    await user.type(screen.getByLabelText('Reason'), ' Wrong share ');
    await user.click(save());
    expect(mutate.mock.calls[0]?.[0]).toEqual({
      id: 'o1',
      data: {
        memberId: 'm1',
        share: '1/2',
        validFrom: '2025-06-01',
        basis: 'Sale',
        reason: 'Wrong share',
        coOwnerShares: [{ ownershipId: 'o2', share: '1/2' }],
      },
    });
  });

  it('shows a server error on the co-owner row', async () => {
    const user = userEvent.setup();
    renderDialog();
    await user.type(screen.getByLabelText('Reason'), 'Why');
    mutate.mockImplementation((_vars: unknown, opts: { onError: (e: unknown) => void }) =>
      opts.onError(
        new ApiError(400, {
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'coOwnerShares[0].share', code: 'SHARE_INVALID' }],
        }),
      ),
    );
    await user.click(save());
    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(1));
    expect(coShare()).toHaveAccessibleDescription(/Enter the share like 1\/2/);
  });
});
