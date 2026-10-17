import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Me, Permission } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { Layout } from './Layout';

let me: Me;

vi.mock('../me', () => ({ useMe: () => me }));
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ signoutRedirect: vi.fn() }) }));

function meWith(permissions: Permission[]): Me {
  return {
    userId: 'u1',
    email: 'a@example.test',
    fullName: 'Test User',
    platformOperator: false,
    roles: [{ id: 'r1', name: 'Member', baseCode: 'MEMBER', nameCustomized: false }],
    permissions,
    tenant: { id: 't1', name: 'Coop' },
  } as Me;
}

describe('Layout', () => {
  beforeEach(() => {
    me = meWith([]);
  });

  it('shows no navigation and no Menu button without sections', () => {
    renderWithApp(<Layout />);
    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Menu' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Test User' })).toHaveAttribute('href', '/account');
  });

  it('lists permitted sections and toggles the menu; Escape closes it', async () => {
    me = meWith(['MEMBERS_VIEW', 'ROLES_MANAGE', 'AUDIT_VIEW']);
    const user = userEvent.setup();
    renderWithApp(<Layout />);
    const nav = screen.getByRole('navigation', { name: 'Main menu' });
    expect(nav.querySelectorAll('a')).toHaveLength(3);

    const button = screen.getByRole('button', { name: 'Menu' });
    expect(button).toHaveAttribute('aria-controls', 'app-menu');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    await user.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    await user.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'false');
    await user.click(button);
    await user.keyboard('{Escape}');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveFocus();
  });
});
