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
    ownedPlots: [],
  } as Me;
}

describe('Layout', () => {
  beforeEach(() => {
    me = meWith([]);
  });

  it('titles the header with the cooperative name, keeps the product name as a muted label', () => {
    renderWithApp(<Layout />);
    const link = screen.getByRole('link', { name: 'Coop' });
    expect(link).toHaveAttribute('href', '/');
    expect(link).toHaveAttribute('title', 'Coop');
    expect(link).toHaveClass('truncate', 'font-serif');
    const product = screen.getByText('Nadel');
    expect(product.tagName).toBe('SPAN');
    expect(product).toHaveClass('text-ink-muted');
  });

  it('falls back to the product name as the title link without a tenant', () => {
    me = { ...meWith([]), tenant: undefined } as Me;
    renderWithApp(<Layout />);
    const link = screen.getByRole('link', { name: 'Nadel' });
    expect(link).toHaveAttribute('href', '/');
    expect(link).toHaveAttribute('title', 'Nadel');
    expect(screen.getAllByText('Nadel')).toHaveLength(1);
  });

  it('gives a member without permissions a menu with only Plots', () => {
    renderWithApp(<Layout />);
    const nav = screen.getByRole('navigation', { name: 'Main menu' });
    const links = nav.querySelectorAll('a');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute('href', '/plots');
    expect(screen.getByRole('button', { name: 'Menu' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Test User' })).toHaveAttribute('href', '/account');
  });

  it('puts Plots first in the menu', () => {
    me = meWith(['MEMBERS_VIEW', 'ROLES_MANAGE', 'AUDIT_VIEW']);
    renderWithApp(<Layout />);
    const hrefs = Array.from(screen.getByRole('navigation', { name: 'Main menu' }).querySelectorAll('a')).map(
      (a) => a.getAttribute('href'),
    );
    expect(hrefs).toEqual(['/plots', '/members', '/roles', '/audit']);
  });

  it('shows Cooperative settings last, only with SETTINGS_MANAGE', () => {
    me = meWith(['MEMBERS_VIEW', 'SETTINGS_MANAGE']);
    const { unmount } = renderWithApp(<Layout />);
    const hrefs = () =>
      Array.from(screen.getByRole('navigation', { name: 'Main menu' }).querySelectorAll('a')).map((a) =>
        a.getAttribute('href'),
      );
    expect(hrefs()).toEqual(['/plots', '/members', '/settings']);
    unmount();
    me = meWith(['MEMBERS_VIEW', 'ROLES_MANAGE', 'AUDIT_VIEW']);
    renderWithApp(<Layout />);
    expect(hrefs()).not.toContain('/settings');
  });

  it('shows Import after Members, only with IMPORT_RUN', () => {
    me = meWith(['MEMBERS_VIEW', 'IMPORT_RUN', 'ROLES_MANAGE']);
    const { unmount } = renderWithApp(<Layout />);
    const hrefs = () =>
      Array.from(screen.getByRole('navigation', { name: 'Main menu' }).querySelectorAll('a')).map((a) =>
        a.getAttribute('href'),
      );
    expect(hrefs()).toEqual(['/plots', '/members', '/imports', '/roles']);
    unmount();
    me = meWith(['MEMBERS_VIEW', 'ROLES_MANAGE']);
    renderWithApp(<Layout />);
    expect(hrefs()).not.toContain('/imports');
  });

  it('shows Tariffs before Import, only with TARIFFS_VIEW', () => {
    me = meWith(['MEMBERS_VIEW', 'TARIFFS_VIEW', 'IMPORT_RUN']);
    const { unmount } = renderWithApp(<Layout />);
    const hrefs = () =>
      Array.from(screen.getByRole('navigation', { name: 'Main menu' }).querySelectorAll('a')).map((a) =>
        a.getAttribute('href'),
      );
    expect(hrefs()).toEqual(['/plots', '/members', '/tariffs', '/imports']);
    expect(screen.getByRole('link', { name: 'Tariffs' })).toBeInTheDocument();
    unmount();
    me = meWith(['MEMBERS_VIEW', 'TARIFFS_MANAGE', 'IMPORT_RUN']);
    renderWithApp(<Layout />);
    expect(hrefs()).not.toContain('/tariffs');
  });

  it('lists permitted sections and toggles the menu; Escape closes it', async () => {
    me = meWith(['MEMBERS_VIEW', 'ROLES_MANAGE', 'AUDIT_VIEW']);
    const user = userEvent.setup();
    renderWithApp(<Layout />);
    const nav = screen.getByRole('navigation', { name: 'Main menu' });
    expect(nav.querySelectorAll('a')).toHaveLength(4);

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

  it('renders the theme switch in the header (md+) and in the menu (below md)', () => {
    me = meWith(['MEMBERS_VIEW']);
    renderWithApp(<Layout />);
    const groups = screen.getAllByRole('group', { name: 'Theme' });
    expect(groups).toHaveLength(2);
    const inHeader = groups.find((g) => g.closest('header'))!;
    const inMenu = groups.find((g) => g.closest('#app-menu'))!;
    expect(inHeader.parentElement).toHaveClass('hidden', 'md:flex');
    expect(inMenu.parentElement).toHaveClass('md:hidden');
  });
});
