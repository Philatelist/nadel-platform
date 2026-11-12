import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { ProfilePage } from './ProfilePage';

const ok = (data: unknown) => ({ data, error: null, isPending: false, isError: false, refetch: vi.fn() });
let permissions: string[] = [];

vi.mock('../me', () => ({
  useCan: () => false,
  useOptionalMe: () => null,
  useMe: () => ({
    memberId: 'm1',
    roles: [{ id: 'r1', name: 'Member', baseCode: 'MEMBER', nameCustomized: false }],
    permissions,
    tenant: { name: 'Demo' },
    ownedPlots: [],
  }),
}));
vi.mock('../components/account/NotificationsPanel', () => ({
  NotificationsPanel: () => <p>notifications</p>,
}));
vi.mock('../components/account/HowToInstallLink', () => ({ HowToInstallLink: () => <p>how to install</p> }));
vi.mock('../components/MemberDetails', () => ({ MemberDetails: () => <p>details</p> }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMember: () => ok({ id: 'm1' }),
}));

describe('ProfilePage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    permissions = [];
  });

  it('shows the details, notifications and the install link, and no permissions to a plain member', () => {
    renderWithApp(<ProfilePage />);
    expect(screen.getByRole('heading', { level: 1, name: 'My details' })).toBeInTheDocument();
    expect(screen.getByText('details')).toBeInTheDocument();
    expect(screen.getByText('notifications')).toBeInTheDocument();
    expect(screen.getByText('how to install')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'To my account' })).toHaveAttribute('href', '/account');
    expect(screen.queryByRole('heading', { name: 'Roles' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'What you can do' })).toBeNull();
  });

  it('shows roles, cooperative and permissions to a board user', () => {
    permissions = ['PLOTS_VIEW'];
    renderWithApp(<ProfilePage />);
    expect(screen.getByRole('heading', { name: 'Roles' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'What you can do' })).toBeInTheDocument();
    expect(screen.getByText('Demo')).toBeInTheDocument();
  });
});
