import { ApiError, type Member, type Permission } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, screen } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { MemberPage } from './MemberPage';

const permissions: Permission[] = ['MEMBERS_EDIT'];
type Opts = { mutation: { onError: (e: unknown) => void } };
let archiveOptions: Opts;

const member = {
  id: 'm1',
  lastName: 'Shevchenko',
  firstName: 'Taras',
  archived: false,
  phones: [],
  roles: [],
} as unknown as Member;

vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
  useMe: () => ({ memberId: 'me', ownedPlots: [] }),
}));
vi.mock('../components/ownership/MemberPlotsPanel', () => ({ MemberPlotsPanel: () => null }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMember: () => ({ data: member, error: null, isPending: false, isError: false, refetch: vi.fn() }),
  useArchiveMember: (options: Opts) => {
    archiveOptions = options;
    return { mutate: vi.fn(), isPending: false };
  },
}));

describe('MemberPage archive', () => {
  it('explains that a member who owns a plot cannot be archived', () => {
    renderWithApp(
      <QueryClientProvider client={new QueryClient()}>
        <Routes>
          <Route path="/members/:id" element={<MemberPage />} />
        </Routes>
      </QueryClientProvider>,
      { route: '/members/m1' },
    );
    act(() => archiveOptions.mutation.onError(new ApiError(409, { code: 'MEMBER_OWNS_PLOT' })));
    expect(screen.getByText('This member owns a plot — change the owner first')).toBeInTheDocument();
  });
});
