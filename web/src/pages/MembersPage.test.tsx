import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { MembersPage } from './MembersPage';

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMembers: () => ({
    data: { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 },
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

vi.mock('../me', () => ({
  useOptionalMe: () => null,
  useCan: () => false,
}));

describe('MembersPage search', () => {
  it('shows the search hint and links it to the field', () => {
    renderWithApp(<MembersPage />);
    const field = screen.getByRole('searchbox', { name: 'Search by name, phone or email' });
    const hint = screen.getByText('Name, full phone or last 4 digits, exact email');
    expect(field).toHaveAttribute('aria-describedby', hint.id);
  });
});
