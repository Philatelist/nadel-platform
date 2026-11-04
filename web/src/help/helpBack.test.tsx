import { render, screen } from '@testing-library/react';
import type { Me } from '@nadel/api-client';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HelpBundle, HelpBundlePage } from '../../help/types';
import { I18nProvider } from '../i18n';
import { HelpIndexPage } from './HelpIndexPage';
import { HelpPageView } from './HelpPageView';
import { HelpViewerProvider } from './helpViewer';
import { ANONYMOUS_VIEWER, viewerFromMe } from './helpVisibility';

let me: Pick<Me, 'permissions' | 'ownedPlots'> | null;
vi.mock('../me', () => ({ useOptionalMe: () => me, useMe: () => me }));

const page: HelpBundlePage = {
  id: 'sample',
  locale: 'uk',
  title: 'Тестова сторінка',
  description: '',
  kind: 'topic',
  routes: [],
  permissions: [],
  section: 'getting-started',
  order: 1,
  shots: [],
  headings: [],
  body: [{ type: 'p', children: [{ type: 'text', text: 'Текст.' }] }],
};
const bundle: HelpBundle = { locale: 'uk', search: null, pages: [page] };

vi.mock('./useHelpBundle', () => ({
  useHelpBundle: () => ({ data: bundle, error: null, isPending: false, isError: false, refetch: vi.fn() }),
}));

function renderAt(entry: { pathname: string; state?: unknown }, anonymous = false) {
  const viewer = anonymous || !me ? ANONYMOUS_VIEWER : viewerFromMe(me);
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <I18nProvider>
        <HelpViewerProvider viewer={viewer}>
          <Routes>
            <Route path="/help" element={<HelpIndexPage />} />
            <Route path="/help/:id" element={<HelpPageView />} />
          </Routes>
        </HelpViewerProvider>
      </I18nProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  window.localStorage.setItem('nadel.locale', 'uk');
  me = { permissions: [], ownedPlots: [] };
});

describe('the way back from the help', () => {
  it('goes to the screen the help was opened from, named by the state', () => {
    renderAt({
      pathname: '/help/sample',
      state: { helpOrigin: { from: '/payments?x=1', label: 'Платежі' } },
    });
    expect(screen.getByRole('link', { name: '← Назад до «Платежі»' })).toHaveAttribute(
      'href',
      '/payments?x=1',
    );
  });

  it('has a plain «← Назад» when the origin has no name', () => {
    renderAt({ pathname: '/help', state: { helpOrigin: { from: '/payments' } } });
    expect(screen.getByRole('link', { name: '← Назад' })).toHaveAttribute('href', '/payments');
  });

  it('ignores an origin that is not an in-app path', () => {
    renderAt({ pathname: '/help', state: { helpOrigin: { from: 'https://evil.test', label: 'X' } } });
    expect(screen.getByRole('link', { name: '← Назад до «Мій кабінет»' })).toHaveAttribute(
      'href',
      '/account',
    );
  });

  it('opened directly, a member goes back to My account (page and contents)', () => {
    const { unmount } = renderAt({ pathname: '/help/sample' });
    expect(screen.getByRole('link', { name: '← Назад до «Мій кабінет»' })).toHaveAttribute(
      'href',
      '/account',
    );
    unmount();
    renderAt({ pathname: '/help' });
    expect(screen.getByRole('link', { name: '← Назад до «Мій кабінет»' })).toHaveAttribute(
      'href',
      '/account',
    );
  });

  it('opened directly, a board user goes back to the first menu screen', () => {
    me = { permissions: ['PLOTS_VIEW'], ownedPlots: [] };
    renderAt({ pathname: '/help/sample' });
    expect(screen.getByRole('link', { name: '← Назад до «Ділянки»' })).toHaveAttribute('href', '/plots');
  });

  it('public help: the contents link back to the app, a page keeps only its link to the contents', () => {
    me = null;
    const { unmount } = renderAt({ pathname: '/help' }, true);
    expect(screen.getByRole('link', { name: '← Назад до «Наділ»' })).toHaveAttribute('href', '/');
    unmount();
    renderAt({ pathname: '/help/sample' }, true);
    expect(screen.queryByRole('link', { name: /^← Назад/ })).toBeNull();
    expect(screen.getByRole('link', { name: 'До змісту довідки' })).toHaveAttribute('href', '/help');
  });
});
