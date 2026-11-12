import { cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Me, Permission } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { Layout } from './Layout';

let me: Me;

vi.mock('../me', () => ({ useMe: () => me, useOptionalMe: () => me }));
vi.mock('../help/useHelpBundle', () => ({
  useHelpBundle: () => ({ data: undefined, error: null, isPending: true, isError: false, refetch: vi.fn() }),
}));
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
    expect(link).toHaveAttribute('href', '/account');
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
    expect(link).toHaveAttribute('href', '/account');
    expect(link).toHaveAttribute('title', 'Nadel');
    expect(screen.getAllByText('Nadel')).toHaveLength(1);
  });

  it('gives a plot owner without permissions only My account: no Plots, no Help in the menu', () => {
    me = { ...meWith([]), ownedPlots: [{ plotId: 'p1', number: '7' }] } as unknown as Me;
    renderWithApp(<Layout />);
    const nav = screen.getByRole('navigation', { name: 'Main menu' });
    const links = nav.querySelectorAll('a');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute('href', '/account');
    expect(links[0]).toHaveTextContent('My account');
    expect(screen.queryByRole('link', { name: 'Plots' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Help' })).toBeNull();
    expect(document.querySelector('a[href="/help"]')).toBeNull();
    expect(screen.getByRole('button', { name: 'Menu' })).toBeInTheDocument();
    // the name links to «My details» from the header (desktop) and from the menu (phone)
    for (const link of screen.getAllByRole('link', { name: /Test User/ })) {
      expect(link).toHaveAttribute('href', '/account/profile');
    }
  });

  it('puts the person, the language switch and Sign out into the menu for a phone', () => {
    renderWithApp(<Layout />);
    const menu = document.getElementById('app-menu')!;
    const inMenu = within(menu);
    expect(inMenu.getByRole('link', { name: /Test User/ })).toHaveAttribute('href', '/account/profile');
    expect(inMenu.getByRole('group', { name: 'Language' })).toBeInTheDocument();
    expect(inMenu.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    const menuButton = screen.getByRole('button', { name: 'Menu' });
    expect(menuButton).toHaveAttribute('aria-controls', 'app-menu');
    expect(menuButton).toHaveAttribute('aria-expanded', 'false');
  });

  it('puts Plots first in the menu', () => {
    me = meWith(['PLOTS_VIEW', 'MEMBERS_VIEW', 'ROLES_MANAGE', 'AUDIT_VIEW']);
    renderWithApp(<Layout />);
    const hrefs = Array.from(screen.getByRole('navigation', { name: 'Main menu' }).querySelectorAll('a')).map(
      (a) => a.getAttribute('href'),
    );
    expect(hrefs).toEqual(['/plots', '/members', '/roles', '/audit']);
  });

  it('shows Payments after Charges, only with PAYMENTS_VIEW', () => {
    me = meWith(['PLOTS_VIEW', 'CHARGES_VIEW', 'PAYMENTS_VIEW', 'REPORTS_VIEW']);
    const { unmount } = renderWithApp(<Layout />);
    const hrefs = () =>
      Array.from(screen.getByRole('navigation', { name: 'Main menu' }).querySelectorAll('a')).map((a) =>
        a.getAttribute('href'),
      );
    expect(hrefs()).toEqual(['/plots', '/charges', '/payments', '/reports', '/documents/certificates']);
    unmount();
    me = meWith(['PLOTS_VIEW', 'CHARGES_VIEW']);
    renderWithApp(<Layout />);
    expect(hrefs()).toEqual(['/plots', '/charges', '/documents/certificates']);
  });

  it('shows Reports after Charges, only with REPORTS_VIEW', () => {
    me = meWith(['PLOTS_VIEW', 'CHARGES_VIEW', 'REPORTS_VIEW']);
    const { unmount } = renderWithApp(<Layout />);
    const hrefs = () =>
      Array.from(screen.getByRole('navigation', { name: 'Main menu' }).querySelectorAll('a')).map((a) =>
        a.getAttribute('href'),
      );
    expect(hrefs()).toEqual(['/plots', '/charges', '/reports', '/documents/certificates']);
    unmount();
    me = meWith(['PLOTS_VIEW', 'CHARGES_VIEW']);
    renderWithApp(<Layout />);
    expect(hrefs()).toEqual(['/plots', '/charges', '/documents/certificates']);
  });

  it('shows Reports with CASHBOOK_VIEW alone', () => {
    me = meWith(['CASHBOOK_VIEW']);
    renderWithApp(<Layout />);
    const links = Array.from(screen.getByRole('navigation', { name: 'Main menu' }).querySelectorAll('a')).map(
      (a) => a.getAttribute('href'),
    );
    expect(links).toContain('/reports');
  });

  it('shows Cooperative settings last, only with SETTINGS_MANAGE', () => {
    me = meWith(['PLOTS_VIEW', 'MEMBERS_VIEW', 'SETTINGS_MANAGE']);
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

  it('shows Settings with METERS_MANAGE alone', () => {
    me = meWith(['METERS_VIEW', 'METERS_MANAGE']);
    renderWithApp(<Layout />);
    const hrefs = Array.from(screen.getByRole('navigation', { name: 'Main menu' }).querySelectorAll('a')).map(
      (a) => a.getAttribute('href'),
    );
    expect(hrefs).toContain('/settings');
  });

  it('shows Import after Members, only with IMPORT_RUN', () => {
    me = meWith(['PLOTS_VIEW', 'MEMBERS_VIEW', 'IMPORT_RUN', 'ROLES_MANAGE']);
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
    me = meWith(['PLOTS_VIEW', 'MEMBERS_VIEW', 'TARIFFS_VIEW', 'IMPORT_RUN']);
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

  it('renders the theme switch in the header (md+) and in the menu (below md)', () => {
    me = meWith(['MEMBERS_VIEW']);
    renderWithApp(<Layout />);
    const groups = screen.getAllByRole('group', { name: 'Theme' });
    expect(groups).toHaveLength(2);
    const inHeader = groups.find((g) => g.closest('header'))!;
    const inMenu = groups.find((g) => g.closest('#app-menu'))!;
    expect(inHeader.parentElement).toHaveClass('hidden', 'md:flex');
    expect(inMenu.closest('.md\\:hidden')).not.toBeNull();
  });

  it('shows Meters in the menu only with METERS_VIEW', () => {
    me = meWith([]);
    const { unmount } = renderWithApp(<Layout />);
    expect(screen.queryByRole('link', { name: 'Meters' })).toBeNull();
    unmount();
    me = meWith(['METERS_VIEW']);
    renderWithApp(<Layout />);
    expect(screen.getByRole('link', { name: 'Meters' })).toHaveAttribute('href', '/meters');
  });

  it('shows Readings only with READINGS_ENTER', () => {
    renderWithApp(<Layout />);
    expect(screen.queryByRole('link', { name: 'Readings' })).toBeNull();
    cleanup();
    me = meWith(['READINGS_ENTER']);
    renderWithApp(<Layout />);
    expect(screen.getByRole('link', { name: 'Readings' })).toHaveAttribute('href', '/readings');
  });

  it('shows To approve only with READINGS_APPROVE', () => {
    renderWithApp(<Layout />);
    expect(screen.queryByRole('link', { name: 'To approve' })).toBeNull();
    cleanup();
    me = meWith(['READINGS_APPROVE']);
    renderWithApp(<Layout />);
    expect(screen.getByRole('link', { name: 'To approve' })).toHaveAttribute('href', '/readings/approvals');
  });

  it('gives the electrician without a plot Readings and Meters only, and makes Readings their home', () => {
    me = meWith(['READINGS_ENTER', 'METERS_VIEW']);
    renderWithApp(<Layout />);
    const nav = screen.getByRole('navigation', { name: 'Main menu' });
    expect(Array.from(nav.querySelectorAll('a')).map((a) => a.getAttribute('href'))).toEqual([
      '/readings',
      '/meters',
    ]);
    expect(screen.getByRole('link', { name: 'Coop' })).toHaveAttribute('href', '/readings');
  });

  it('gives a person with no plot and no permission an empty menu', () => {
    renderWithApp(<Layout />);
    const nav = screen.getByRole('navigation', { name: 'Main menu' });
    expect(nav.querySelectorAll('a')).toHaveLength(0);
  });

  it('hides Plots from a meter reader who owns a plot (the plots are on the account) and keeps it with PLOTS_VIEW', () => {
    me = { ...meWith(['METERS_VIEW']), ownedPlots: [{ plotId: 'p1', number: '7' }] } as unknown as Me;
    const { unmount } = renderWithApp(<Layout />);
    expect(screen.queryByRole('link', { name: 'Plots' })).toBeNull();
    expect(screen.getByRole('link', { name: 'My account' })).toBeInTheDocument();
    unmount();
    me = meWith(['METERS_VIEW', 'PLOTS_VIEW']);
    renderWithApp(<Layout />);
    expect(screen.getByRole('link', { name: 'Plots' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'My account' })).toBeNull();
  });

  it('shows Help in the menu to a board user only, and points the logo to the home screen', () => {
    me = meWith(['PLOTS_VIEW', 'MEMBERS_VIEW']);
    renderWithApp(<Layout />);
    expect(screen.getByRole('link', { name: 'Help' })).toHaveAttribute('href', '/help');
    expect(screen.getByRole('link', { name: 'Coop' })).toHaveAttribute('href', '/plots');
  });
});
