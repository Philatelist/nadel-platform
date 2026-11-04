import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { HelpBundle } from '../../help/types';
import { renderWithApp } from '../test/render';
import { PublicHelpApp } from './PublicHelpApp';

const bundle: HelpBundle = {
  locale: 'uk',
  search: null,
  pages: [
    {
      id: 'sample',
      locale: 'uk',
      title: 'Тестова сторінка',
      description: 'Опис.',
      kind: 'topic',
      routes: [],
      permissions: [],
      section: 'getting-started',
      order: 1,
      shots: [],
      headings: [],
      body: [],
    },
  ],
};

vi.mock('./useHelpBundle', () => ({
  useHelpBundle: () => ({ data: bundle, error: null, isPending: false, isError: false, refetch: vi.fn() }),
}));

vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ isAuthenticated: false, isLoading: true, signinRedirect: vi.fn() }),
}));

describe('PublicHelpApp', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the contents and the sign-in button without a user and makes no request', async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    window.history.replaceState({}, '', '/help');
    renderWithApp(<PublicHelpApp />, { route: '/help' });
    expect(await screen.findByRole('link', { name: 'Тестова сторінка' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Увійти' })).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
