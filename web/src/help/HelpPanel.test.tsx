import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HelpBundle } from '../../help/types';
import { renderWithApp } from '../test/render';
import { HelpButton } from './HelpButton';
import { HelpBackLink } from './helpOrigin';
import { HelpViewerProvider } from './helpViewer';
import type { HelpViewer } from './helpVisibility';

let bundle: HelpBundle;

vi.mock('./useHelpBundle', () => ({
  useHelpBundle: () => ({ data: bundle, error: null, isPending: false, isError: false, refetch: vi.fn() }),
}));

const treasurer: HelpViewer = { kind: 'user', permissions: ['PAYMENTS_VIEW'], ownsPlot: false };

function render(route: string, viewer: HelpViewer = treasurer) {
  return renderWithApp(
    <HelpViewerProvider viewer={viewer}>
      <HelpButton />
      <Routes>
        <Route path="*" element={<p>Екран за панеллю</p>} />
      </Routes>
    </HelpViewerProvider>,
    { route },
  );
}

function page(id: string, title: string, routes: string[], permissions: string[]) {
  return {
    id,
    locale: 'uk' as const,
    title,
    description: '',
    kind: 'screen' as const,
    routes,
    permissions,
    section: 'payments-cash' as const,
    order: 1,
    shots: [],
    headings: [],
    body: [{ type: 'p' as const, children: [{ type: 'text' as const, text: `Текст: ${title}` }] }],
  };
}

beforeEach(() => {
  bundle = {
    locale: 'uk',
    search: null,
    pages: [
      page('payments', 'Платежі', ['/payments'], ['PAYMENTS_VIEW']),
      page('audit', 'Журнал дій', ['/audit'], ['AUDIT_VIEW']),
    ],
  };
});

beforeEach(() => window.localStorage.setItem('nadel.locale', 'uk'));

const panelName = (title: string) => ({ name: title });

describe('HelpPanel', () => {
  it('opens the page of the current screen beside it and keeps the screen usable', async () => {
    render('/payments');
    const open = screen.getByRole('button', { name: 'Довідка про цей екран' });
    await userEvent.click(open);
    const panel = screen.getByRole('complementary', panelName('Платежі'));
    expect(panel).toHaveFocus();
    expect(screen.getByText('Текст: Платежі')).toBeInTheDocument();
    expect(screen.getByText('Екран за панеллю')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
    // not modal: nothing is inert or hidden from assistive technology
    expect(document.querySelector('[inert]')).toBeNull();
    expect(screen.getByText('Екран за панеллю').closest('[aria-hidden="true"]')).toBeNull();
    expect(open).toHaveAttribute('aria-expanded', 'true');
  });

  it('keeps links outside the panel clickable and navigates with the panel open', async () => {
    renderWithApp(
      <HelpViewerProvider viewer={treasurer}>
        <HelpButton />
        <Link to="/audit">В журнал</Link>
        <Link to="/payments">До платежів</Link>
        <Routes>
          <Route path="*" element={<p>Екран за панеллю</p>} />
        </Routes>
      </HelpViewerProvider>,
      { route: '/payments' },
    );
    await userEvent.click(screen.getByRole('button', { name: 'Довідка про цей екран' }));
    expect(screen.getByRole('complementary', panelName('Платежі'))).toBeInTheDocument();
    // a screen without a visible page closes the panel (and hides «?»)
    await userEvent.click(screen.getByRole('link', { name: 'В журнал' }));
    expect(screen.queryByRole('complementary')).toBeNull();
    // coming back does not reopen the old panel
    await userEvent.click(screen.getByRole('link', { name: 'До платежів' }));
    expect(screen.queryByRole('complementary')).toBeNull();
    expect(screen.getByRole('button', { name: 'Довідка про цей екран' })).toBeInTheDocument();
  });

  it('shows the help of the new screen when the route changes to a screen that has a page', async () => {
    bundle.pages.push(page('tariffs', 'Тарифи', ['/tariffs'], ['PAYMENTS_VIEW']));
    renderWithApp(
      <HelpViewerProvider viewer={treasurer}>
        <HelpButton />
        <Link to="/tariffs">До тарифів</Link>
        <Routes>
          <Route path="*" element={<p>Екран за панеллю</p>} />
        </Routes>
      </HelpViewerProvider>,
      { route: '/payments' },
    );
    await userEvent.click(screen.getByRole('button', { name: 'Довідка про цей екран' }));
    await userEvent.click(screen.getByRole('link', { name: 'До тарифів' }));
    expect(screen.getByRole('complementary', panelName('Тарифи'))).toBeInTheDocument();
    expect(screen.queryByText('Текст: Платежі')).toBeNull();
  });

  it('links to the full page and passes the screen it was opened from', async () => {
    renderWithApp(
      <HelpViewerProvider viewer={treasurer}>
        <HelpButton />
        <Routes>
          <Route path="/help/:id" element={<HelpBackLink />} />
          <Route path="*" element={<p>Екран за панеллю</p>} />
        </Routes>
      </HelpViewerProvider>,
      { route: '/payments' },
    );
    await userEvent.click(screen.getByRole('button', { name: 'Довідка про цей екран' }));
    const full = screen.getByRole('link', { name: 'Відкрити повністю' });
    expect(full).toHaveAttribute('href', '/help/payments');
    await userEvent.click(full);
    expect(screen.getByRole('link', { name: '← Назад до «Платежі»' })).toHaveAttribute('href', '/payments');
  });

  it('closes with the close button, the phone back button and Escape, returning focus to «?»', async () => {
    render('/payments');
    const open = screen.getByRole('button', { name: 'Довідка про цей екран' });
    await userEvent.click(open);
    await userEvent.click(screen.getByRole('button', { name: 'Закрити' }));
    expect(screen.queryByRole('complementary')).toBeNull();
    expect(open).toHaveFocus();
    await userEvent.click(open);
    await userEvent.click(screen.getByRole('button', { name: '← Назад' }));
    expect(screen.queryByRole('complementary')).toBeNull();
    await userEvent.click(open);
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('complementary')).toBeNull();
    expect(open).toHaveFocus();
  });

  it('has no button on a screen without a visible page', () => {
    render('/audit');
    expect(screen.queryByRole('button', { name: 'Довідка про цей екран' })).toBeNull();
  });

  it('has no button on a screen no page covers', () => {
    render('/nowhere');
    expect(screen.queryByRole('button', { name: 'Довідка про цей екран' })).toBeNull();
  });
});
