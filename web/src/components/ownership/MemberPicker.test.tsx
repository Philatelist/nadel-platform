import type { Permission } from '@nadel/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { MemberPicker } from './MemberPicker';

const listMembers = vi.fn();
let permissions: Permission[];

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMembers: (params: unknown) => listMembers(params),
}));

beforeEach(() => {
  permissions = [];
  listMembers.mockReset();
  listMembers.mockReturnValue({
    data: {
      items: [
        { id: 'm1', lastName: 'Ivanenko', firstName: 'Oleh', archived: false },
        { id: 'm2', lastName: 'Petrenko', firstName: 'Ivan', archived: false },
        { id: 'm9', lastName: 'Old', firstName: 'Timer', archived: true },
      ],
    },
  });
});

describe('MemberPicker', () => {
  it('debounces the search and leaves out archived and already chosen members', async () => {
    const user = userEvent.setup();
    renderWithApp(<MemberPicker label="Member" value={null} excludeIds={['m1']} onChange={vi.fn()} />);
    expect(screen.queryByText('Ivanenko Oleh')).not.toBeInTheDocument();
    expect(screen.queryByText('Old Timer')).not.toBeInTheDocument();
    expect(screen.getByText('Petrenko Ivan')).toBeInTheDocument();
    await user.type(screen.getByRole('searchbox'), 'Pet');
    expect(listMembers).not.toHaveBeenCalledWith(expect.objectContaining({ q: 'Pet' }));
    await waitFor(() => expect(listMembers).toHaveBeenCalledWith(expect.objectContaining({ q: 'Pet' })));
  });

  it('shows the chosen name with a change button, and the add link only with MEMBERS_EDIT', async () => {
    const onChange = vi.fn();
    const { rerender } = renderWithApp(
      <MemberPicker
        label="Member"
        value={{ id: 'm2', name: 'Petrenko Ivan' }}
        excludeIds={[]}
        onChange={onChange}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Change' }));
    expect(onChange).toHaveBeenCalledWith(null);
    rerender(<MemberPicker label="Member" value={null} excludeIds={[]} onChange={onChange} />);
    expect(screen.queryByRole('link', { name: 'Add member' })).not.toBeInTheDocument();
  });

  it('links to /members/new in a new tab for MEMBERS_EDIT', () => {
    permissions = ['MEMBERS_EDIT'];
    renderWithApp(<MemberPicker label="Member" value={null} excludeIds={[]} onChange={vi.fn()} />);
    const link = screen.getByRole('link', { name: 'Add member' });
    expect(link).toHaveAttribute('href', '/members/new');
    expect(link).toHaveAttribute('target', '_blank');
  });
});
